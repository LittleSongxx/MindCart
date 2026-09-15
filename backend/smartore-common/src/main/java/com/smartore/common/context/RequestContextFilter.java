package com.smartore.common.context;

import com.smartore.common.constant.HeaderNames;
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
 * 服务入口统一过滤器：
 * 1. 剥掉外部请求伪造的信任头（X-User-Id / X-User-Role），身份只能来自网关；
 * 2. 若配置了内部令牌（生产/集群必须配置），强校验请求确实来自网关；
 * 3. 装载 UserContext 供业务读取当前用户；
 * 4. traceId 进 MDC，日志全链路可追踪；网关没带就自己生成兜底。
 */
@Component
public class RequestContextFilter extends OncePerRequestFilter {

    private static final String MDC_TRACE_ID = "traceId";

    @Value("${smartore.internal-token:}")
    private String internalToken;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            // 内部令牌校验：配置了就必须匹配，保证绕不过网关直连服务
            if (internalToken != null && !internalToken.isBlank()) {
                String provided = request.getHeader(HeaderNames.X_INTERNAL_TOKEN);
                if (!internalToken.equals(provided)) {
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    return;
                }
            }
            // 通过校验（或开发态未配置令牌）后，信任网关注入的身份头
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
}
