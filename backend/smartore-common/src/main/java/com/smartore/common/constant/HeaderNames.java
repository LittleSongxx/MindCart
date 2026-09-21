package com.smartore.common.constant;

/**
 * 网关与内部服务之间的信任头约定。
 *
 * 信任边界（双凭证，见 UserContextFilter）：
 * - X-Gateway-Token：只由网关注入，证明请求通过了网关的 JWT 认证（用户流量）；
 * - X-Internal-Token：只在服务间 Feign 调用中携带（ContextRelayInterceptor），证明请求来自集群内。
 * /internal/** 只接受内部令牌——用户流量无论因何种路由缺陷到达内部接口，都会因缺少内部令牌被拒。
 */
public final class HeaderNames {

    /** 网关完成 JWT 校验后注入的用户 ID */
    public static final String X_USER_ID = "X-User-Id";

    /** 网关完成 JWT 校验后注入的用户角色（USER / ADMIN） */
    public static final String X_USER_ROLE = "X-User-Role";

    /** 网关与服务共享的网关令牌：证明请求经网关转发（非集群内部调用） */
    public static final String X_GATEWAY_TOKEN = "X-Gateway-Token";

    /** 服务间共享的内部令牌：证明请求来自集群内 Feign 调用，/internal/** 唯一接受的凭证 */
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
