package com.mindcart.common.context;

import com.mindcart.common.constant.HeaderNames;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * 服务入口统一过滤器（双凭证信任模型）：
 * 1. X-Gateway-Token：网关注入，证明用户流量通过了网关 JWT 认证；
 * 2. X-Internal-Token：集群内 Feign 调用携带，/internal/** 唯一接受的凭证——
 *    用户流量即使因路由误配/路径穿越到达内部接口也会被拒；
 * 3. 两个令牌启动期强制校验非空（dev.sh bootstrap 自动生成），杜绝"未配置即放行"的 fail-open；
 * 4. 装载 UserContext 供业务读取当前用户；
 * 5. traceId 进 MDC，日志全链路可追踪；网关没带就自己生成兜底。
 */
@Component
public class UserContextFilter extends OncePerRequestFilter implements org.springframework.beans.factory.InitializingBean {

    private static final String MDC_TRACE_ID = "traceId";

    @Value("${mindcart.internal-token:}")
    private String internalToken;

    @Value("${mindcart.gateway-token:}")
    private String gatewayToken;

    @Override
    public void afterPropertiesSet() {
        if (internalToken == null || internalToken.isBlank() || gatewayToken == null || gatewayToken.isBlank()) {
            throw new IllegalStateException(
                    "mindcart.internal-token 与 mindcart.gateway-token 必须配置（scripts/dev.sh bootstrap 自动生成）");
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // 监控探活走集群内网，不参与令牌校验
        return request.getRequestURI().startsWith("/actuator/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            String uri = request.getRequestURI();
            boolean internalCall = uri.startsWith("/internal/") || uri.equals("/internal");
            boolean fromCluster = constantTimeEquals(internalToken, request.getHeader(HeaderNames.X_INTERNAL_TOKEN));
            // /internal/** 只认集群内凭证；普通接口接受网关转发或集群内调用
            boolean fromGateway = constantTimeEquals(gatewayToken, request.getHeader(HeaderNames.X_GATEWAY_TOKEN));
            boolean allowed = internalCall ? fromCluster : (fromCluster || fromGateway);
            if (!allowed) {
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                return;
            }
            // 通过凭证校验后，信任网关注入的身份头
            String userId = sanitize(request.getHeader(HeaderNames.X_USER_ID));
            String role = sanitize(request.getHeader(HeaderNames.X_USER_ROLE));
            UserContext.set(parseUserId(userId), role);

            String traceId = sanitize(request.getHeader(HeaderNames.X_TRACE_ID));
            if (traceId == null || traceId.isBlank()) {
                traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
            }
            MDC.put(MDC_TRACE_ID, traceId);
            response.setHeader(HeaderNames.X_TRACE_ID, traceId);

            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_TRACE_ID);
            UserContext.clear();
        }
    }

    private Integer parseUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(userId);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 去掉可能的换行/首尾空白，防止头注入 */
    private String sanitize(String value) {
        if (value == null) {
            return null;
        }
        String cleaned = value.trim();
        return cleaned.contains("\n") || cleaned.contains("\r") ? null : cleaned;
    }

    /** 常数时间比较，避免令牌比对的时序侧信道 */
    private boolean constantTimeEquals(String expected, String provided) {
        if (expected == null || provided == null) {
            return false;
        }
        return java.security.MessageDigest.isEqual(
                expected.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                provided.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
