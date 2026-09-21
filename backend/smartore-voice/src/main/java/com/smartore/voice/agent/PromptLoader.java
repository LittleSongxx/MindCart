package com.smartore.voice.agent;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Component
public class PromptLoader {

    public String load(String path) {
        try {
            // 必须走流式读取：ClassPathResource.getFile() 在 Spring Boot 嵌套 jar 里
            // 拿不到真实文件（IDE 里能跑、容器里炸），getInputStream 对两种环境都成立
            try (var in = new ClassPathResource(path).getInputStream()) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            throw new RuntimeException("加载 prompt 失败：" + path, e);
        }
    }
}