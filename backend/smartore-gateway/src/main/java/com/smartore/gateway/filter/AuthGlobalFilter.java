package com.smartore.gateway.filter;

import cn.hutool.core.util.StrUtil;
import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.smartore.gateway.config.AuthProperties;
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

    private final AuthProperties authProperties;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();
    private final JWTVerifier verifier;

    public AuthGlobalFilter(AuthProperties authProperties) {
        this.authProperties = authProperties;
        this.verifier = JWT.require(Algorithm.HMAC256(authProperties.getJwtSecret())).build();
    }

    @PostConstruct
    public void checkSecret() {
        if (StrUtil.isBlank(authProperties.getJwtSecret()) || authProperties.getJwtSecret().length() < 32) {
            throw new IllegalStateException("smartore.auth.jwt-secret 必须配置且至少 32 字符");
        }
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getURI().getPath();
        HttpMethod method = request.getMethod();

        // 0. 内部接口永不出网关
        if (path.startsWith("/internal/")) {
            return reject(exchange, HttpStatus.NOT_FOUND, "404", "接口不存在");
        }

        // 1. 公开白名单
        if (matches(authProperties.getPublicPaths(), method, path)) {
            return chain.filter(sanitize(exchange, null));
        }

        // 2. JWT 认证
        String token = request.getHeaders().getFirst(HEADER_TOKEN);
        if (token == null) {
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

        // 3. RBAC：命中管理规则表要求 ADMIN
        if (matches(authProperties.getAdminRules(), method, path) && !"ADMIN".equals(role)) {
            log.warn("非管理员访问管理接口被拒绝：userId={} {} {}", userId, method, path);
            return reject(exchange, HttpStatus.FORBIDDEN, "403", "没有权限执行该操作");
        }

        // 4. 注入信任头（先剥外部伪造的同名头）
        return chain.filter(sanitize(exchange, userId + "|" + StrUtil.nullToEmpty(role)));
    }

    /** 规则匹配：支持 "GET /a/**"（带方法）与 "/a/**"（任意方法）两种写法 */
    private boolean matches(List<String> rules, HttpMethod method, String path) {
        for (String rule : rules) {
            String trimmed = rule.trim();
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

    /** 移除外部传入的信任头；认证通过时注入真实身份 + traceId */
    private ServerWebExchange sanitize(ServerWebExchange exchange, String identity) {
        ServerHttpRequest.Builder builder = exchange.getRequest().mutate()
                .headers(headers -> {
                    headers.remove(X_USER_ID);
                    headers.remove("X-User-Role");
                    headers.remove("X-Internal-Token");
                    headers.remove("X-Trace-Id");
                });
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
