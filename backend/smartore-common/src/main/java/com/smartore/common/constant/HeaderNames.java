package com.smartore.common.constant;

/**
 * 网关与内部服务之间的信任头约定。
 *
 * 信任边界：这些头只能由网关注入。服务收到请求时会剥掉外部传入的同名头
 * （见 UserContextFilter），防止绕过网关直接调用服务伪造身份。
 */
public final class HeaderNames {

    /** 网关完成 JWT 校验后注入的用户 ID */
    public static final String X_USER_ID = "X-User-Id";

    /** 网关完成 JWT 校验后注入的用户角色（USER / ADMIN） */
    public static final String X_USER_ROLE = "X-User-Role";

    /** 网关与服务共享的内部令牌，服务侧配置后强校验 */
    public static final String X_INTERNAL_TOKEN = "X-Internal-Token";

    /** 全链路追踪 ID，网关生成、服务与 Feign 透传 */
    public static final String X_TRACE_ID = "X-Trace-Id";

    /** 前端携带 JWT 的请求头名（沿用原前端约定） */
    public static final String TOKEN = "token";

    public static final String ROLE_ADMIN = "ADMIN";
    public static final String ROLE_USER = "USER";

    private HeaderNames() {
    }
}
