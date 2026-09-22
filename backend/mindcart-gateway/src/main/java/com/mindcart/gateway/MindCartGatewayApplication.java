package com.mindcart.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

@SpringBootApplication
@EnableDiscoveryClient
public class MindCartGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(MindCartGatewayApplication.class, args);
    }
}
