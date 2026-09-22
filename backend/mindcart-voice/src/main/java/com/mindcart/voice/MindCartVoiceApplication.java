package com.mindcart.voice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * mindcart-voice：语音导购通道服务。
 * 只扫描自身与 com.mindcart.common（UserContextFilter/Result/异常/Feign 透传拦截器），
 * Feign 契约来自 user/goods/trade 的 api 模块。
 */
@EnableAsync
@EnableScheduling
@SpringBootApplication(scanBasePackages = {"com.mindcart.voice", "com.mindcart.common"})
@EnableFeignClients(basePackages = "com.mindcart")
@EnableCaching
public class MindCartVoiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(MindCartVoiceApplication.class, args);
    }
}
