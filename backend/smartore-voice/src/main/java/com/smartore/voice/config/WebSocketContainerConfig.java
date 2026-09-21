package com.smartore.voice.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

/**
 * WebSocket 容器缓冲区配置。
 * <p>
 * Spring/Tomcat 默认文本消息缓冲区仅 8KB（8192），本服务的下行 JSON
 * （商品卡片 recommendation、长话术 caption）在实测中会超过该上限，
 * Tomcat 随即以 1009（buffer too small）关闭连接——表现为"服务端发出音频前
 * 连接静默断开、客户端 60s 无响应"。
 * <p>
 * 生产必须显式放大：文本 1MB（JSON 卡片）、二进制 1MB（音频帧）、
 * 空闲 10 分钟、异步发送超时 30s（"No async message support"告警的对应配置）。
 */
@Configuration
@Slf4j
public class WebSocketContainerConfig {

    @Bean
    public ServletServerContainerFactoryBean createWebSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxTextMessageBufferSize(1024 * 1024);
        container.setMaxBinaryMessageBufferSize(1024 * 1024);
        container.setMaxSessionIdleTimeout(600_000L);
        container.setAsyncSendTimeout(30_000L);
        log.info("[WS] 容器缓冲区已放大：text/binary=1MB, idle=10min, asyncSend=30s");
        return container;
    }
}
