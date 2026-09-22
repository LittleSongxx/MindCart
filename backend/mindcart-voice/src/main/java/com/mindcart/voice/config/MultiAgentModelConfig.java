package com.mindcart.voice.config;

import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.OpenAIChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

@Configuration
public class MultiAgentModelConfig {

    /** base-url/api-key/模型名统一由 LlmConfigResolver 解析（ai 后台配置优先，yml 兜底） */
    private final LlmConfigResolver llmConfigResolver;

    @Value("${voice-shopping.llm.enable-thinking:false}")
    private boolean enableThinking;

    public MultiAgentModelConfig(LlmConfigResolver llmConfigResolver) {
        this.llmConfigResolver = llmConfigResolver;
    }

    /**
     * 点评团三人组用的均衡档模型。
     * MsgHub 的广播是客户端行为（把发言写进各 Agent 的本地记忆），
     * 模型侧只要标准 chat 接口即可，不需要 DashScope 专有的多 Agent 格式化器。
     */
    @Bean("multiAgentChatModel")
    public Model multiAgentChatModel() {
        return OpenAIChatModel.builder()
                .baseUrl(llmConfigResolver.chatBaseUrl())
                .apiKey(llmConfigResolver.chatApiKey())
                .modelName(llmConfigResolver.chatModelName())
                .generateOptions(GenerateOptions.builder()
                        .additionalBodyParams(Map.of("enable_thinking", enableThinking))
                        .build())
                .build();
    }
}
