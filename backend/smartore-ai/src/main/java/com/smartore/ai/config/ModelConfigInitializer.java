package com.smartore.ai.config;

import cn.hutool.core.util.StrUtil;
import com.smartore.ai.entity.AiModelConfig;
import com.smartore.ai.mapper.AiModelConfigMapper;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/**
 * 模型配置冷启动初始化：环境变量里有密钥而库里没有可用配置时，
 * 自动写入一条启用配置（密钥经 AES-GCM 加密落库）。仓库与 SQL 都不携带任何真实密钥。
 */
@Component
public class ModelConfigInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ModelConfigInitializer.class);

    @Value("${smartore.model.chat.api-key:}")
    private String chatApiKey;
    @Value("${smartore.model.chat.base-url:https://api.deepseek.com/v1}")
    private String chatBaseUrl;
    @Value("${smartore.model.chat.model-name:deepseek-chat}")
    private String chatModelName;
    @Value("${smartore.model.embedding.api-key:}")
    private String embeddingApiKey;
    @Value("${smartore.model.embedding.base-url:https://dashscope.aliyuncs.com/compatible-mode/v1}")
    private String embeddingBaseUrl;
    @Value("${smartore.model.embedding.model-name:text-embedding-v4}")
    private String embeddingModelName;

    @Resource
    private AiModelConfigMapper mapper;
    @Resource
    private com.smartore.ai.service.AiModelConfigService configService;

    @Override
    public void run(ApplicationArguments args) {
        if (StrUtil.isNotBlank(chatApiKey)) {
            upsert("CHAT", "DeepSeek", chatModelName, chatBaseUrl, chatApiKey);
        }
        if (StrUtil.isNotBlank(embeddingApiKey)) {
            upsert("EMBEDDING", "阿里百炼", embeddingModelName, embeddingBaseUrl, embeddingApiKey);
        }
    }

    private void upsert(String modelType, String provider, String modelName, String baseUrl, String apiKey) {
        AiModelConfig condition = new AiModelConfig();
        condition.setModelType(modelType);
        List<AiModelConfig> existing = mapper.selectAll(condition);
        // 占位密钥（种子数据）不算可用配置
        boolean usable = existing.stream().anyMatch(c -> c.getIsEnabled() != null && c.getIsEnabled() == 1
                && StrUtil.isNotBlank(c.getApiKey()) && !c.getApiKey().contains("请填写"));
        if (usable) {
            return;
        }
        if (!existing.isEmpty()) {
            // 已有占位行：原地升级为真实配置（保持主键，避免重复行）
            AiModelConfig row = existing.get(0);
            AiModelConfig update = new AiModelConfig();
            update.setId(row.getId());
            update.setModelType(modelType);
            update.setProvider(provider);
            update.setModelName(modelName);
            update.setBaseUrl(baseUrl);
            update.setApiKey(apiKey);
            update.setIsEnabled(1);
            update.setUpdateTime(cn.hutool.core.date.DateUtil.now());
            try {
                configService.updateById(update);
                log.info("已用环境变量升级{}模型配置：{}", modelType, modelName);
            } catch (Exception e) {
                log.warn("{}模型配置升级失败：{}", modelType, e.getMessage());
            }
            return;
        }
        AiModelConfig config = new AiModelConfig();
        config.setProvider(provider);
        config.setModelType(modelType);
        config.setModelName(modelName);
        config.setBaseUrl(baseUrl);
        config.setApiKey(apiKey);
        config.setTemperature(new BigDecimal("0.70"));
        config.setMaxTokens(modelType.equals("CHAT") ? 2048 : null);
        config.setIsEnabled(1);
        config.setRemark("由环境变量初始化（" + provider + "）");
        try {
            configService.add(config);
            log.info("已从环境变量初始化{}模型配置：{}", modelType, modelName);
        } catch (Exception e) {
            log.warn("{}模型配置初始化失败（可能缺少加密密钥）：{}", modelType, e.getMessage());
        }
    }
}
