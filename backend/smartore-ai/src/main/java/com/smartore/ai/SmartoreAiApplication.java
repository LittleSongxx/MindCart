package com.smartore.ai;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "com.smartore")
@EnableFeignClients(basePackages = "com.smartore")
@EnableScheduling
@MapperScan("com.smartore.ai.mapper")
public class SmartoreAiApplication {

    public static void main(String[] args) {
        SpringApplication.run(SmartoreAiApplication.class, args);
    }
}
