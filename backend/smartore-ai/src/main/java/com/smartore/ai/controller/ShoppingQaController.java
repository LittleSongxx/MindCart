package com.smartore.ai.controller;

import com.smartore.common.result.Result;
import com.smartore.ai.entity.ShoppingQa;
import com.smartore.ai.entity.ShoppingQaRequest;
import com.smartore.ai.service.ShoppingQaService;
import com.github.pagehelper.PageInfo;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

@RestController
@RequestMapping("/shoppingQa")
public class ShoppingQaController {

    @Resource
    private ShoppingQaService shoppingQaService;

    @PostMapping("/ask")
    public Result ask(@RequestBody ShoppingQaRequest request) {
        ShoppingQa shoppingQa = shoppingQaService.ask(request);
        return Result.success(shoppingQa);
    }

    @DeleteMapping("/delete/{id}")
    public Result delete(@PathVariable Integer id) {
        shoppingQaService.deleteById(id);
        return Result.success();
    }

    @DeleteMapping("/delete/batch")
    public Result delete(@RequestBody List<Integer> ids) {
        shoppingQaService.deleteBatch(ids);
        return Result.success();
    }

    @GetMapping("/selectPage")
    public Result selectPage(ShoppingQa shoppingQa,
                             @RequestParam(defaultValue = "1") Integer pageNum,
                             @RequestParam(defaultValue = "10") Integer pageSize) {
        PageInfo<ShoppingQa> pageInfo = shoppingQaService.selectPage(shoppingQa, pageNum, pageSize);
        return Result.success(pageInfo);
    }

    /**
     * 流式问答（SSE）：增量文本以 delta 事件推送，complete 事件携带完整 QA 实体（含 conversationId）。
     * 事件类型约定：delta=文本增量 / complete=最终结果 / error=失败原因 /（注释行=心跳）。
     */
    @GetMapping(value = "/askStream", produces = org.springframework.http.MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter askStream(@RequestParam(required = false) Integer userId,
                                @RequestParam String questionType,
                                @RequestParam String questionText,
                                @RequestParam(required = false) Integer productId,
                                @RequestParam(required = false) String productName,
                                @RequestParam(required = false) Integer conversationId,
                                jakarta.servlet.http.HttpServletResponse response) {
        // 身份必须在容器线程内取（异步体里 UserContext 已被过滤器清理）；
        // 客户端传的 userId 参数不信任，一律以网关注入的当前用户为准
        Integer currentUserId = com.smartore.common.context.UserContext.requireUserId();
        SseEmitter emitter = new SseEmitter(120_000L);
        // 反代（nginx）默认缓冲响应会杀死"流式"——该响应头让本连接逐段透传
        response.setHeader("X-Accel-Buffering", "no");

        ShoppingQaRequest request = new ShoppingQaRequest();
        request.setUserId(currentUserId);
        request.setQuestionType(questionType);
        request.setQuestionText(questionText);
        request.setProductId(productId);
        request.setProductName(productName);
        request.setConversationId(conversationId);

        // 心跳：首 delta 前的检索阶段可达数十秒，无字节流动会被反代/浏览器空闲超时掐断。
        // 注释行对 EventSource 透明，只用于保活。
        final java.util.concurrent.ScheduledFuture<?> heartbeat = HEARTBEAT_SCHEDULER.scheduleAtFixedRate(() -> {
            try {
                emitter.send(SseEmitter.event().comment("ping"));
            } catch (Exception heartbeatStopped) {
                // 发送失败即连接已断，心跳由 onCompletion 取消
            }
        }, 15, 15, java.util.concurrent.TimeUnit.SECONDS);
        Runnable stopHeartbeat = () -> heartbeat.cancel(false);
        emitter.onCompletion(stopHeartbeat);
        emitter.onTimeout(stopHeartbeat);

        STREAM_EXECUTOR.submit(() -> {
            try {
                ShoppingQa qa = shoppingQaService.ask(request, chunk -> {
                    try {
                        emitter.send(SseEmitter.event().name("delta").data(chunk));
                    } catch (Exception ignored) {
                        // 客户端断开：停止转发增量，后台继续完成并落库（连接不是任务状态）
                    }
                });
                emitter.send(SseEmitter.event().name("complete").data(qa));
                emitter.complete();
            } catch (Exception e) {
                try {
                    emitter.send(SseEmitter.event().name("error").data(
                            cn.hutool.core.util.StrUtil.maxLength(String.valueOf(e.getMessage()), 300)));
                } catch (Exception ignored) {
                }
                emitter.completeWithError(e);
            }
        });
        return emitter;
    }

    /**
     * SSE 长任务线程池（daemon，全局共享）。
     *
     * 这里**必须是有界池**：原先用的是 newCachedThreadPool（无界），一串并发 SSE 请求会
     * 无限起线程、每个线程还各自打一次 LLM 调用——既是线程耗尽的入口，也是费用失控的入口。
     * 现在固定核心/最大线程数 + 有界队列；队列打满时由 CallerRunsPolicy 回落到调用线程执行，
     * 形成天然背压（Tomcat 线程被占住 → 新连接排队），而不是把任务无限堆在内存里。
     * 容量通过环境变量可调，按机器规格与模型配额定。
     */
    private static final java.util.concurrent.ThreadPoolExecutor STREAM_EXECUTOR;

    static {
        int core = Integer.getInteger("smartore.qa.sse.core-threads", 8);
        int max = Integer.getInteger("smartore.qa.sse.max-threads", 16);
        int queue = Integer.getInteger("smartore.qa.sse.queue-capacity", 64);
        STREAM_EXECUTOR = new java.util.concurrent.ThreadPoolExecutor(
                core, max, 60L, java.util.concurrent.TimeUnit.SECONDS,
                new java.util.concurrent.LinkedBlockingQueue<>(queue),
                r -> {
                    Thread t = new Thread(r, "qa-sse-worker");
                    t.setDaemon(true);
                    return t;
                },
                new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());
    }

    /** 心跳调度器（daemon，全局共享） */
    private static final java.util.concurrent.ScheduledExecutorService HEARTBEAT_SCHEDULER =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "qa-sse-heartbeat");
                t.setDaemon(true);
                return t;
            });

    /** 我的会话列表（多轮上下文入口） */
    @GetMapping("/conversations")
    public com.smartore.common.result.Result<java.util.List<com.smartore.ai.entity.QaConversation>> conversations() {
        return com.smartore.common.result.Result.success(
                shoppingQaService.myConversations(com.smartore.common.context.UserContext.requireUserId()));
    }
}
