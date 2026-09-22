package com.mindcart.goods;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

@SpringBootApplication(scanBasePackages = "com.mindcart")
@EnableFeignClients(basePackages = "com.mindcart")
@MapperScan("com.mindcart.goods.mapper")
public class MindCartGoodsApplication {

    public static void main(String[] args) {
        SpringApplication.run(MindCartGoodsApplication.class, args);
    }
}
