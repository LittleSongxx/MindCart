package com.mindcart.common.feign;

import com.mindcart.common.constant.HeaderNames;
import com.mindcart.common.context.UserContext;
import feign.RequestInterceptor;
import feign.RequestTemplate;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.stereotype.Component;

/**
 * 服务间 Feign 调用的上下文透传：用户身份、内部令牌、traceId 原样带给下游，
 * 下游服务的鉴权与日志口径与入口请求保持一致。
 */
@Component
@ConditionalOnClass(RequestInterceptor.class)
public class ContextRelayInterceptor implements RequestInterceptor {

    private static final String MDC_TRACE_ID = "traceId";

    @Value("${mindcart.internal-token:}")
    private String internalToken;

    @Override
    public void apply(RequestTemplate template) {
        Integer userId = UserContext.getUserIdOrNull();
        if (userId != null) {
            template.header(HeaderNames.X_USER_ID, String.valueOf(userId));
        }
        String role = UserContext.getRoleOrNull();
        if (role != null) {
            template.header(HeaderNames.X_USER_ROLE, role);
        }
        if (internalToken != null && !internalToken.isBlank()) {
            template.header(HeaderNames.X_INTERNAL_TOKEN, internalToken);
        }
        String traceId = MDC.get(MDC_TRACE_ID);
        if (traceId != null) {
            template.header(HeaderNames.X_TRACE_ID, traceId);
        }
    }
}
