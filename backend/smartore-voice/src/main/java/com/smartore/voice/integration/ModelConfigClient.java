package com.smartore.voice.integration;

import com.smartore.common.result.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;

import java.math.BigDecimal;
import java.util.Map;

/**
 * ai 域模型配置下发契约（集群内 Feign）。语音链路启动时拉取当前启用的
 * CHAT/EMBEDDING 配置，跟随后台【AI模型配置】的切换；
 * 拉取失败或未配置时由 LlmConfigResolver 回退到本地 yml 默认值。
 */
@FeignClient(name = "smartore-ai", contextId = "voiceModelConfigClient")
public interface ModelConfigClient {

    @GetMapping("/internal/model-config/active")
    Result<Map<String, ActiveModelConfigVO>> active();

    class ActiveModelConfigVO {
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
}
