package com.mindcart.ai.internal;

import com.mindcart.ai.entity.AiModelConfig;
import com.mindcart.ai.service.AiChatService;
import com.mindcart.common.result.Result;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

/**
 * 集群内模型配置下发（仅 Feign，网关不路由 /internal/**）。
 * <p>
 * mindcart-voice 启动时从这里拉取当前启用的 CHAT/EMBEDDING 配置（含解密后的 apiKey），
 * 让语音链路跟随后台【AI模型配置】的切换，不再各自维护一套静态模型配置。
 * 返回 null 值字段表示该类型未配置，由调用方决定回退策略。
 */
@RestController
@RequestMapping("/internal/model-config")
public class ModelConfigInternalController {

    @Resource
    private AiChatService aiChatService;

    public static class ActiveModelConfig {
        private String provider;
        private String modelName;
        private String baseUrl;
        private String apiKey;
        private BigDecimal temperature;
        private Integer maxTokens;

        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }
        public String getModelName() { return modelName; }
        public void setModelName(String modelName) { this.modelName = modelName; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public BigDecimal getTemperature() { return temperature; }
        public void setTemperature(BigDecimal temperature) { this.temperature = temperature; }
        public Integer getMaxTokens() { return maxTokens; }
        public void setMaxTokens(Integer maxTokens) { this.maxTokens = maxTokens; }
    }

    @GetMapping("/active")
    public Result<Map<String, ActiveModelConfig>> active() {
        Map<String, ActiveModelConfig> out = new HashMap<>();
        putIfPresent(out, "CHAT", aiChatService.findEnabledConfig("CHAT"));
        putIfPresent(out, "EMBEDDING", aiChatService.findEnabledConfig("EMBEDDING"));
        return Result.success(out);
    }

    private void putIfPresent(Map<String, ActiveModelConfig> out, String type, AiModelConfig config) {
        if (config == null) {
            return;
        }
        ActiveModelConfig dto = new ActiveModelConfig();
        dto.setProvider(config.getProvider());
        dto.setModelName(config.getModelName());
        dto.setBaseUrl(config.getBaseUrl());
        dto.setApiKey(config.getApiKey());
        dto.setTemperature(config.getTemperature());
        dto.setMaxTokens(config.getMaxTokens());
        out.put(type, dto);
    }
}
