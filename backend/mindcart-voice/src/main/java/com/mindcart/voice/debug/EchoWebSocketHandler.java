package com.mindcart.voice.debug;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.List;

/**
 * P0 验证用回声 handler：证明 WebSocket 能穿过网关（lb:ws://）到达本服务，
 * 且网关注入的身份头（X-User-Id/X-User-Role/X-Gateway-Token）在握手时可见。
 * 仅 voice.debug-echo.enabled=true 时注册，P2 后仅作为联调工具保留。
 */
public class EchoWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(EchoWebSocketHandler.class);

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        List<String> headers = List.of("X-User-Id", "X-User-Role", "X-Gateway-Token", "X-Trace-Id");
        StringBuilder sb = new StringBuilder("voice ws echo connected, headers: ");
        for (String h : headers) {
            String v = session.getHandshakeHeaders().getFirst(h);
            // 网关令牌只记录是否存在，不记录值
            sb.append(h).append('=').append("X-Gateway-Token".equals(h) ? (v != null ? "<present>" : "<missing>") : v).append(' ');
        }
        log.info(sb.toString());
        session.sendMessage(new TextMessage("{\"type\":\"echo_ready\"}"));
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        session.sendMessage(new TextMessage("{\"type\":\"echo\",\"payload\":" + quote(message.getPayload()) + "}"));
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        log.info("voice ws echo closed: {}", status);
    }

    private static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
