package com.mindcart.ai;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "com.mindcart")
@EnableFeignClients(basePackages = "com.mindcart")
@EnableScheduling
@MapperScan("com.mindcart.ai.mapper")
public class MindCartAiApplication {

    public static void main(String[] args) {
        SpringApplication.run(MindCartAiApplication.class, args);
    }
}
