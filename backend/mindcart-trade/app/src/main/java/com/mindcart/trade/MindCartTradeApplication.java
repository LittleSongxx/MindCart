package com.mindcart.trade;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "com.mindcart")
@EnableFeignClients(basePackages = "com.mindcart")
@EnableScheduling
@MapperScan("com.mindcart.trade.mapper")
public class MindCartTradeApplication {

    public static void main(String[] args) {
        SpringApplication.run(MindCartTradeApplication.class, args);
    }
}
