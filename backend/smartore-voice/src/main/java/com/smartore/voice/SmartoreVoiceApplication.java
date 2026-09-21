package com.smartore.voice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * smartore-voice：语音导购通道服务。
 * 只扫描自身与 com.smartore.common（UserContextFilter/Result/异常/Feign 透传拦截器），
 * Feign 契约来自 user/goods/trade 的 api 模块。
 */
@EnableAsync
@EnableScheduling
@SpringBootApplication(scanBasePackages = {"com.smartore.voice", "com.smartore.common"})
@EnableFeignClients(basePackages = "com.smartore")
@EnableCaching
public class SmartoreVoiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(SmartoreVoiceApplication.class, args);
    }
}
