package com.mindcart.common.audit;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.mindcart.common.context.UserContext;
import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.multipart.MultipartFile;

import java.util.Arrays;

/**
 * 审计切面：把 {@link OperationLog} 标注的调用记成一条留痕。
 *
 * 三条设计约束：
 * 1. 绝不影响业务：记录过程整体 try/catch，任何异常只 WARN 不抛出——
 *    审计是旁路，不能把一次成功的改价变成 500。
 * 2. 失败也要留痕：业务方法内部抛出的异常同样记录（result=FAIL + 原因），
 *    "有人尝试改库存但失败了"本身也是要追溯的信息。
 *    **注意边界**：入参未通过 Bean Validation 时，请求在进入控制器方法之前就被框架拒了，
 *    切面根本不会触发，因此这类 400 不留痕。这是有意为之——没进业务方法的请求没改动任何东西，
 *    记下来只会用噪声淹没真正的改动记录。
 * 3. 入参截断：长文本（商品详情、提示词）按上限截断，
 *    避免审计表被单条大文本撑爆；敏感接口用 recordArgs=false 完全不记。
 *
 * {@code @ConditionalOnClass}：本类在 common 里，会被所有服务扫到；
 * 没有引入 starter-aop 的服务（user/trade/voice）必须跳过它，否则
 * @Aspect 注解类加载不到会导致启动失败。
 */
@Aspect
@Component
@ConditionalOnClass(name = "org.aspectj.lang.annotation.Aspect")
public class OperationLogAspect {

    private static final Logger log = LoggerFactory.getLogger(OperationLogAspect.class);

    /** 入参摘要上限 */
    private static final int ARGS_MAX = 500;
    /** 失败原因上限 */
    private static final int ERROR_MAX = 300;

    private final ObjectProvider<OperLogRecorder> recorderProvider;

    public OperationLogAspect(ObjectProvider<OperLogRecorder> recorderProvider) {
        this.recorderProvider = recorderProvider;
    }

    @Around("@annotation(operationLog)")
    public Object around(ProceedingJoinPoint joinPoint, OperationLog operationLog) throws Throwable {
        long start = System.nanoTime();
        try {
            Object result = joinPoint.proceed();
            record(joinPoint, operationLog, start, "SUCCESS", null);
            return result;
        } catch (Throwable e) {
            record(joinPoint, operationLog, start, "FAIL", e.getMessage());
            throw e;
        }
    }

    private void record(ProceedingJoinPoint joinPoint, OperationLog annotation,
                        long startNanos, String result, String errorMsg) {
        OperLogRecorder recorder = recorderProvider.getIfAvailable();
        if (recorder == null) {
            return;
        }
        try {
            OperLogEntry entry = new OperLogEntry();
            entry.setUserId(UserContext.getUserIdOrNull());
            entry.setRole(UserContext.getRoleOrNull());
            entry.setModule(annotation.module());
            entry.setAction(annotation.action());
            entry.setResult(result);
            entry.setErrorMsg(StrUtil.maxLength(errorMsg, ERROR_MAX));
            entry.setCostMs((System.nanoTime() - startNanos) / 1_000_000);
            if (annotation.recordArgs()) {
                entry.setArgs(briefArgs(joinPoint.getArgs()));
            }
            fillRequest(entry);
            recorder.record(entry);
        } catch (Exception e) {
            // 审计旁路：任何失败都不允许影响业务调用方
            log.warn("审计记录失败（不影响业务）：{}", e.getMessage());
        }
    }

    private void fillRequest(OperLogEntry entry) {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) {
            return;
        }
        HttpServletRequest request = attributes.getRequest();
        entry.setRequestUri(StrUtil.maxLength(request.getRequestURI(), 255));
        entry.setHttpMethod(request.getMethod());
        entry.setClientIp(clientIp(request));
    }

    /**
     * 取客户端 IP。
     *
     * 与网关限流一致：不信任客户端可伪造的 X-Forwarded-For 作为"真实来源"，
     * 但审计场景需要看到代理链，因此 XFF 只作为补充信息保留首跳，
     * 主字段仍用连接对端地址（反代场景下即代理 IP，稳定不可伪造）。
     */
    private String clientIp(HttpServletRequest request) {
        String remote = request.getRemoteAddr();
        String forwarded = request.getHeader("X-Forwarded-For");
        if (StrUtil.isBlank(forwarded)) {
            return remote;
        }
        String first = forwarded.split(",")[0].trim();
        return StrUtil.maxLength(remote + " (" + first + ")", 255);
    }

    /** 入参摘要：跳过无法/不宜序列化的类型，整体转 JSON 后截断 */
    private String briefArgs(Object[] args) {
        if (args == null || args.length == 0) {
            return null;
        }
        Object[] printable = Arrays.stream(args)
                .filter(arg -> !(arg instanceof MultipartFile))
                .filter(arg -> !(arg instanceof HttpServletRequest))
                .filter(arg -> !(arg instanceof jakarta.servlet.http.HttpServletResponse))
                .toArray();
        if (printable.length == 0) {
            return null;
        }
        try {
            return StrUtil.maxLength(JSONUtil.toJsonStr(printable), ARGS_MAX);
        } catch (Exception e) {
            // 入参可能是不可 JSON 化的对象（流、连接等）：退化为类型名，不因为记不下来就报错
            return StrUtil.maxLength(Arrays.toString(Arrays.stream(printable)
                    .map(a -> a == null ? "null" : a.getClass().getSimpleName()).toArray()), ARGS_MAX);
        }
    }
}
