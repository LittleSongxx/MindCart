package com.smartore.voice.service;

import com.alibaba.csp.sentinel.Entry;
import com.alibaba.csp.sentinel.SphU;
import com.alibaba.csp.sentinel.Tracer;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.smartore.voice.config.ResilienceConfig;
import com.smartore.voice.repository.SessionRepository;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatUsage;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * LLM 调用的统一弹性入口：Sentinel 熔断降级 + 轻量重试 + token 用量计量。
 * <p>
 * 所有同步 LLM 调用以 {@link #call(Supplier, Supplier)} 一行接入：
 * 被熔断/失败时返回调用方给的降级结果（各服务复用已有降级逻辑），
 * 而不是把异常抛进语音主链路。
 * <p>
 * token 计量：返回值是 {@link Msg} 且携带 {@link ChatUsage} 时，自动累计
 * Micrometer 计数器与 session.total_tokens——记账失败只记日志，绝不影响主流程。
 */
@Component
@Slf4j
public class LlmGuard {

    private final Counter flowBlockedCounter;
    private final Counter circuitBlockedCounter;
    private final Counter llmFailureCounter;
    private final Counter llmRetryCounter;
    private final Counter inputTokenCounter;
    private final Counter outputTokenCounter;
    private final Timer llmCallTimer;
    private final SessionRepository sessionRepo;

    @Value("${voice-shopping.llm.pricing.input-per-1k:0.04}")
    private double inputPricePer1k;

    @Value("${voice-shopping.llm.pricing.output-per-1k:0.12}")
    private double outputPricePer1k;

    public LlmGuard(MeterRegistry registry, SessionRepository sessionRepo) {
        this.sessionRepo = sessionRepo;
        this.flowBlockedCounter = Counter.builder("llm.guard.blocked")
                .tag("type", "flow").register(registry);
        this.circuitBlockedCounter = Counter.builder("llm.guard.blocked")
                .tag("type", "circuit").register(registry);
        this.llmFailureCounter = Counter.builder("llm.call.failure").register(registry);
        this.llmRetryCounter = Counter.builder("llm.call.retry").register(registry);
        this.inputTokenCounter = Counter.builder("llm.tokens.input").register(registry);
        this.outputTokenCounter = Counter.builder("llm.tokens.output").register(registry);
        this.llmCallTimer = Timer.builder("llm.call.duration").register(registry);
    }

    /** 熔断器开路标记：与普通失败区分，触发降级而非重试 */
    private static class CircuitOpenException extends RuntimeException {
        CircuitOpenException(BlockException cause) { super(cause); }
    }

    /**
     * 同步 LLM 调用：LLM 资源熔断统计 → 网络类异常重试一次 → 失败/熔断走 fallback。
     */
    public <T> T call(Supplier<T> llmCall, Supplier<T> fallback) {
        return call(null, llmCall, fallback);
    }

    /**
     * 带会话上下文的同步调用：额外把 token 用量累计到 session.total_tokens。
     *
     * @param sessionId 会话 ID；为 null 时只做指标计量不写库
     */
    public <T> T call(String sessionId, Supplier<T> llmCall, Supplier<T> fallback) {
        try {
            return callWithRetry(sessionId, llmCall);
        } catch (CircuitOpenException e) {
            circuitBlockedCounter.increment();
            log.warn("LLM 调用被熔断拦截，走降级: {}", e.getCause().getClass().getSimpleName());
            return fallback.get();
        } catch (Exception e) {
            log.error("LLM 调用失败（重试后仍不可用），走降级", e);
            return fallback.get();
        }
    }

    /**
     * 语音交互入口的流控准入：并发/QPS 超限时返回 fallback（礼貌降级文案），
     * 正常时执行主流程。RT 不参与 LLM 熔断统计（业务耗时不等于 LLM 健康度）。
     */
    public <T> T guardEntry(Supplier<T> mainFlow, Supplier<T> fallback) {
        Entry entry;
        try {
            entry = SphU.entry(ResilienceConfig.RES_ENTRY);
        } catch (BlockException e) {
            flowBlockedCounter.increment();
            log.warn("请求被流控拦截: {}", e.getClass().getSimpleName());
            return fallback.get();
        }
        try {
            return mainFlow.get();
        } finally {
            entry.exit();
        }
    }

    /** 流式 LLM 调用出错时计入失败指标（流式路径不走熔断准入，只做计量） */
    public void onStreamError(Throwable t) {
        llmFailureCounter.increment();
        log.warn("流式 LLM 调用失败: {}", t.toString());
    }

    private <T> T callWithRetry(String sessionId, Supplier<T> llmCall) {
        try {
            return callOnce(sessionId, llmCall);
        } catch (CircuitOpenException e) {
            throw e;   // 熔断不重试：重试只会对故障供应商继续加压
        } catch (Exception first) {
            if (!isRetryable(first)) throw first;
            llmRetryCounter.increment();
            log.warn("LLM 调用网络异常，500ms 后重试一次: {}", first.toString());
            sleepQuietly(500);
            return callOnce(sessionId, llmCall);
        }
    }

    private <T> T callOnce(String sessionId, Supplier<T> llmCall) {
        long start = System.nanoTime();
        Entry entry;
        try {
            entry = SphU.entry(ResilienceConfig.RES_LLM);
        } catch (BlockException e) {
            throw new CircuitOpenException(e);
        }
        try {
            T result = llmCall.get();
            recordUsage(sessionId, result);
            return result;
        } catch (RuntimeException e) {
            llmFailureCounter.increment();
            Tracer.traceEntry(e, entry);   // 计入 Sentinel 异常比例熔断统计
            throw e;
        } finally {
            entry.exit();
            llmCallTimer.record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
        }
    }

    /**
     * 从返回值提取 token 用量：AgentScope 的响应 Msg 携带 ChatUsage。
     * 记账（指标 + session 表累加）失败只记日志，不影响主流程。
     */
    private void recordUsage(String sessionId, Object result) {
        if (!(result instanceof Msg msg)) return;
        ChatUsage usage = msg.getChatUsage();
        if (usage == null) return;
        try {
            inputTokenCounter.increment(usage.getInputTokens());
            outputTokenCounter.increment(usage.getOutputTokens());
            double cost = usage.getInputTokens() * inputPricePer1k / 1000.0
                    + usage.getOutputTokens() * outputPricePer1k / 1000.0;
            log.info("Token 用量 | 输入：{} | 输出：{} | 本次费用约：¥{}",
                    usage.getInputTokens(), usage.getOutputTokens(), String.format("%.4f", cost));
            if (sessionId != null && usage.getTotalTokens() > 0) {
                sessionRepo.addTotalTokens(sessionId, usage.getTotalTokens());
            }
        } catch (Exception e) {
            log.warn("token 记账失败（不影响主流程）: {}", e.toString());
        }
    }

    /**
     * 仅网络类异常值得重试（连接/读取超时、IO 错误）；限流、鉴权、内容审核类
     * 重试无意义且放大故障。按类名判断以兼容各 SDK 的异常包装。
     */
    private boolean isRetryable(Throwable t) {
        while (t != null) {
            String name = t.getClass().getSimpleName();
            if (name.contains("RateLimit") || name.contains("Authentication")
                    || name.contains("ContentFilter") || name.contains("Invalid")) {
                return false;
            }
            if (t instanceof java.io.IOException || name.contains("Network")
                    || name.contains("Timeout") || name.contains("Connect")) {
                return true;
            }
            t = t.getCause();
        }
        return false;
    }

    private void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
