package com.mindcart.voice.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.UUID;

/**
 * WebSocket 握手鉴权：身份只能来自网关注入的信任头，绝不接受客户端自报 userId。
 * <p>
 * 链路：浏览器 → 网关 AuthGlobalFilter（验 JWT，剥伪造头，注入 X-Gateway-Token +
 * X-User-Id/X-User-Role）→ 本拦截器。JWT 本身经 query 参数传给网关
 * （浏览器 WebSocket API 无法自定义请求头，网关对 /voice/ws 开了 query-token 白名单）；
 * 到达本服务时 token 已被网关消费完毕，这里只认网关凭证：
 * 1) X-Gateway-Token 与配置常数时间比对——证明请求确实穿过网关；
 * 2) X-User-Id 必须存在——证明网关 JWT 校验通过。
 * 绕过网关直连本服务（无 X-Gateway-Token）一律 401。
 */
@Component
public class AuthHandshakeInterceptor implements HandshakeInterceptor {

    @Value("${mindcart.gateway-token:}")
    private String gatewayToken;

    @Override
    public boolean beforeHandshake(ServerHttpRequest request,
                                   ServerHttpResponse response,
                                   WebSocketHandler handler,
                                   Map<String, Object> attributes) {
        String presented = request.getHeaders().getFirst("X-Gateway-Token");
        String userId = request.getHeaders().getFirst("X-User-Id");
        if (gatewayToken == null || gatewayToken.isBlank()
                || presented == null || userId == null || userId.isBlank()
                || !MessageDigest.isEqual(gatewayToken.getBytes(StandardCharsets.UTF_8),
                                          presented.getBytes(StandardCharsets.UTF_8))) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
        try {
            attributes.put("userId", Long.parseLong(userId.trim()));
        } catch (NumberFormatException e) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
        String role = request.getHeaders().getFirst("X-User-Role");
        attributes.put("userRole", role == null ? "USER" : role);

        String sessionId = getParam(request, "sessionId");
        attributes.put("sessionId",
                sessionId == null || sessionId.isBlank() ? UUID.randomUUID().toString() : sessionId);

        // 渠道归因：前端从商详页进入时带 channel=PRODUCT_PAGE&productId=<id>，
        // 落到 session.channel / bound_product_id，供运营统计与"这件商品"指代解析
        String channel = getParam(request, "channel");
        attributes.put("channel", channel == null || channel.isBlank() ? "HOME_ENTRY" : channel);
        String productId = getParam(request, "productId");
        if (productId != null && !productId.isBlank()) {
            try {
                attributes.put("boundProductId", Long.parseLong(productId.trim()));
            } catch (NumberFormatException ignore) {
                // 非法 productId 直接忽略，不影响握手
            }
        }
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest r, ServerHttpResponse rp,
                               WebSocketHandler h, Exception e) {
    }

    private String getParam(ServerHttpRequest request, String name) {
        URI uri = request.getURI();
        if (uri.getQuery() == null || uri.getQuery().isEmpty()) return null;
        for (String kv : uri.getQuery().split("&")) {
            int i = kv.indexOf('=');
            if (i < 0) continue;
            String k = java.net.URLDecoder.decode(kv.substring(0, i), StandardCharsets.UTF_8);
            if (!name.equals(k)) continue;
            return java.net.URLDecoder.decode(kv.substring(i + 1), StandardCharsets.UTF_8);
        }
        return null;
    }
}
