package com.smartore.user;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * scanBasePackages 覆盖 com.smartore.common（全局异常/上下文过滤器/Feign 透传）与本服务包。
 */
@SpringBootApplication(scanBasePackages = "com.smartore")
@MapperScan("com.smartore.user.mapper")
public class SmartoreUserApplication {

    public static void main(String[] args) {
        SpringApplication.run(SmartoreUserApplication.class, args);
    }
}
