package com.smartore.goods;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

@SpringBootApplication(scanBasePackages = "com.smartore")
@EnableFeignClients(basePackages = "com.smartore")
@MapperScan("com.smartore.goods.mapper")
public class SmartoreGoodsApplication {

    public static void main(String[] args) {
        SpringApplication.run(SmartoreGoodsApplication.class, args);
    }
}
