package com.mindcart.voice.debug;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * P0/P1 联调用回声端点：/voice/ws。
 * P2 移植正式 VoiceWebSocketHandler 后，本端点默认关闭（voice.debug-echo.enabled=false），
 * 仅在需要排查网关 WS 链路时打开。
 */
@Configuration
@EnableWebSocket
@ConditionalOnProperty(name = "voice.debug-echo.enabled", havingValue = "true")
public class EchoWebSocketConfig implements WebSocketConfigurer {

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(new EchoWebSocketHandler(), "/voice/echo-ws")
                .setAllowedOrigins("*");
    }
}
