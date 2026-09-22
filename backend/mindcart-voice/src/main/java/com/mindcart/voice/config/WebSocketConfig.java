package com.mindcart.voice.config;

import com.mindcart.voice.controller.VoiceWebSocketHandler;
import com.mindcart.voice.security.AuthHandshakeInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.*;

@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketConfigurer {

    private final VoiceWebSocketHandler handler;
    private final AuthHandshakeInterceptor authHandshakeInterceptor;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // 路径与网关 voice-ws 路由（lb:ws://mindcart-voice, Path=/voice/ws）对应
        registry.addHandler(handler, "/voice/ws")
                .addInterceptors(authHandshakeInterceptor)
                .setAllowedOriginPatterns("*");
    }
}
