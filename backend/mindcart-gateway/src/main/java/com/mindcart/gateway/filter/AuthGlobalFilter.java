package com.mindcart.gateway.filter;

import cn.hutool.core.util.StrUtil;
import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.mindcart.gateway.config.AuthProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/**
 * 网关认证过滤器 —— 鉴权是网关的单一职责：
 * 1. 公开白名单（登录/注册/图片下载）直接放行；
 * 2. /internal/** 一律拒绝（内部接口不出网关，纵深防御）；
 * 3. 其余请求校验 JWT（HMAC 固定密钥，不再用用户密码当签名）；
 * 4. RBAC：集中式规则表（yml 配置，方法+ANT 路径）匹配则要求 ADMIN；
 * 5. 剥掉外部伪造的信任头，注入真实的用户身份头 + traceId 给下游。
 */
@Component
public class AuthGlobalFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(AuthGlobalFilter.class);

    private static final String HEADER_TOKEN = "token";
    private static final String X_USER_ID = "X-User-Id";
    private static final String X_USER_ROLE = "X-User-Role";
    private static final String X_TRACE_ID = "X-Trace-Id";
    /** EventSource/WebSocket 无法携带自定义请求头，token 走 query 参数仅限这些端点（避免 JWT 落入日志/Referer） */
    private static final java.util.Set<String> QUERY_TOKEN_PATHS = java.util.Set.of("/shoppingQa/askStream", "/voice/ws");

    private final AuthProperties authProperties;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();
    private final JWTVerifier verifier;

    @org.springframework.beans.factory.annotation.Value("${mindcart.gateway-token:}")
    private String gatewayToken;

    public AuthGlobalFilter(AuthProperties authProperties) {
        this.authProperties = authProperties;
        this.verifier = JWT.require(Algorithm.HMAC256(authProperties.getJwtSecret())).build();
    }

    @PostConstruct
    public void checkSecret() {
        if (StrUtil.isBlank(authProperties.getJwtSecret()) || authProperties.getJwtSecret().length() < 32) {
            throw new IllegalStateException("mindcart.auth.jwt-secret 必须配置且至少 32 字符");
        }
        if (StrUtil.isBlank(gatewayToken)) {
            throw new IllegalStateException("mindcart.gateway-token 必须配置（scripts/dev.sh bootstrap 自动生成）");
        }
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getURI().getPath();
        HttpMethod method = request.getMethod();

        // 0. 路径规范化：拒绝可被下游容器二次解释的相对段/空段/反斜杠。
        //    否则 /user/../internal/x 不命中下方前缀判断，却会被下游 Tomcat 归一化后映射到内部接口。
        String normalized = org.springframework.util.StringUtils.cleanPath(path);
        if (!normalized.equals(path) || path.contains("\\") || path.contains("//")) {
            return reject(exchange, HttpStatus.BAD_REQUEST, "400", "非法请求路径");
        }

        // 1. 内部接口永不出网关（normalized 已等于 path，此处双保险）
        if (normalized.startsWith("/internal/") || normalized.equals("/internal")) {
            return reject(exchange, HttpStatus.NOT_FOUND, "404", "接口不存在");
        }

        // 2. 公开白名单
        if (matches(authProperties.getPublicPaths(), method, path)) {
            return chain.filter(sanitize(exchange, null));
        }

        // 3. JWT 认证（query token 仅对无法携带请求头的 SSE 端点开放）
        String token = request.getHeaders().getFirst(HEADER_TOKEN);
        if (token == null && QUERY_TOKEN_PATHS.contains(path)) {
            token = request.getQueryParams().getFirst(HEADER_TOKEN);
        }
        if (StrUtil.isBlank(token)) {
            return reject(exchange, HttpStatus.UNAUTHORIZED, "401", "未登录或 Token 缺失");
        }
        Integer userId;
        String role;
        try {
            DecodedJWT decoded = verifier.verify(token);
            userId = decoded.getClaim("uid").asInt();
            role = decoded.getClaim("role").asString();
        } catch (JWTVerificationException | NullPointerException e) {
            return reject(exchange, HttpStatus.UNAUTHORIZED, "401", "Token 无效或已过期");
        }
        if (userId == null) {
            return reject(exchange, HttpStatus.UNAUTHORIZED, "401", "Token 无效或已过期");
        }
        // 供限流 KeyResolver 使用
        exchange.getAttributes().put("userId", userId);

        // 4. RBAC：命中管理规则表要求 ADMIN
        if (matches(authProperties.getAdminRules(), method, path) && !"ADMIN".equals(role)) {
            log.warn("非管理员访问管理接口被拒绝：userId={} {} {}", userId, method, path);
            return reject(exchange, HttpStatus.FORBIDDEN, "403", "没有权限执行该操作");
        }

        // 5. 注入信任头（先剥外部伪造的同名头）。
        //    注意：这里不注入 X-Internal-Token —— 集群内部凭证只由服务间 Feign 调用
        //    （ContextRelayInterceptor）携带，用户流量即使因路由误配到达 /internal/**
        //    也会因缺少该头被下游 UserContextFilter 拒绝。
        return chain.filter(sanitize(exchange, userId + "|" + StrUtil.nullToEmpty(role)));
    }

    /** 规则匹配：支持 "GET /a/**"（带方法）与 "/a/**"（任意方法）两种写法 */
    private boolean matches(List<String> rules, HttpMethod method, String path) {
        for (String rule : rules) {
            String trimmed = rule.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String rulePath;
            if (Character.isLetter(trimmed.charAt(0)) && trimmed.contains(" ")) {
                int space = trimmed.indexOf(' ');
                String ruleMethod = trimmed.substring(0, space);
                if (method != null && !ruleMethod.equalsIgnoreCase(method.name())) {
                    continue;
                }
                rulePath = trimmed.substring(space + 1).trim();
            } else {
                rulePath = trimmed;
            }
            if (pathMatcher.match(rulePath, path)) {
                return true;
            }
        }
        return false;
    }

    /** 移除外部传入的信任头；网关令牌始终注入（证明经网关转发），认证通过时另注入真实身份 + traceId */
    private ServerWebExchange sanitize(ServerWebExchange exchange, String identity) {
        ServerHttpRequest.Builder builder = exchange.getRequest().mutate()
                .headers(headers -> {
                    headers.remove(X_USER_ID);
                    headers.remove("X-User-Role");
                    headers.remove("X-Gateway-Token");
                    headers.remove("X-Internal-Token");
                    headers.remove(X_TRACE_ID);
                });
        // 网关凭证：下游 UserContextFilter 据此放行"经网关转发"的流量（含公开白名单路径）。
        // 集群内部凭证 X-Internal-Token 由服务间 Feign 调用自行携带，网关永远不注入。
        builder.header("X-Gateway-Token", gatewayToken);
        if (identity != null) {
            String[] parts = identity.split("\\|");
            builder.header(X_USER_ID, parts[0]);
            if (parts.length > 1 && !parts[1].isEmpty()) {
                builder.header("X-User-Role", parts[1]);
            }
        }
        String traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        builder.header(X_TRACE_ID, traceId);
        return exchange.mutate().request(builder.build()).build();
    }

    private Mono<Void> reject(ServerWebExchange exchange, HttpStatus status, String code, String msg) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"code\":\"" + code + "\",\"msg\":\"" + msg + "\",\"data\":null}";
        DataBuffer buffer = response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buffer));
    }

    @Override
    public int getOrder() {
        return -100;
    }
}
