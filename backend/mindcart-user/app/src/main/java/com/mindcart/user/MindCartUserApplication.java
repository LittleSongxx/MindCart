package com.mindcart.user;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * scanBasePackages 覆盖 com.mindcart.common（全局异常/上下文过滤器/Feign 透传）与本服务包。
 */
@SpringBootApplication(scanBasePackages = "com.mindcart")
@MapperScan("com.mindcart.user.mapper")
public class MindCartUserApplication {

    public static void main(String[] args) {
        SpringApplication.run(MindCartUserApplication.class, args);
    }
}
