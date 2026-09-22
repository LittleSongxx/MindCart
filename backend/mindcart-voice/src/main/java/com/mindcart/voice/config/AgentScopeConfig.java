package com.mindcart.voice.config;

import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.OpenAIChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

@Configuration
public class AgentScopeConfig {

    /** base-url/api-key/模型名统一由 LlmConfigResolver 解析（ai 后台配置优先，yml 兜底） */
    private final LlmConfigResolver llmConfigResolver;

    @Value("${voice-shopping.llm.enable-thinking:false}")
    private boolean enableThinking;

    public AgentScopeConfig(LlmConfigResolver llmConfigResolver) {
        this.llmConfigResolver = llmConfigResolver;
    }

    /**
     * 思考型模型（qwen3.8 系默认开思考）的思考过程会垫在首 token 前面：
     * 语音链路实测端到端首响 P50 19.7s，关思考后单次分类延迟 1.75s→0.69s。
     * 分类与话术任务不需要显式思考，默认关闭；需要时用 LLM_ENABLE_THINKING=true 打开（消融用）。
     */
    private GenerateOptions options() {
        return GenerateOptions.builder()
                .additionalBodyParams(Map.of("enable_thinking", enableThinking))
                .build();
    }

    /**
     * 主对话模型（推荐、情感）。流式由 ChatModelBase.stream 统一支持。
     * <p>
     * LLM 统一走 OpenAI 兼容端点（百炼 compatible-mode）：网关型 key 只放行
     * /compatible-mode 路径，DashScope 原生 /api/v1 text-generation 会被拒
     * （InvalidParameter: url error），DashScopeChatModel 不可用。
     */
    @Bean("mainChatModel")
    public Model mainChatModel() {
        return OpenAIChatModel.builder()
                .baseUrl(llmConfigResolver.chatBaseUrl())
                .apiKey(llmConfigResolver.chatApiKey())
                .modelName(llmConfigResolver.chatModelName())
                .generateOptions(options())
                .build();
    }

    /**
     * 轻量模型（意图识别、分类任务）
     */
    @Bean("lightChatModel")
    public Model lightChatModel() {
        return OpenAIChatModel.builder()
                .baseUrl(llmConfigResolver.chatBaseUrl())
                .apiKey(llmConfigResolver.chatApiKey())
                .modelName(llmConfigResolver.chatModelName())
                // 意图输出是短 JSON：限 token 防跑飞 + 温度 0（分类任务要确定性，评测可复现）
                .generateOptions(GenerateOptions.builder()
                        .additionalBodyParams(Map.of("enable_thinking", enableThinking))
                        .maxTokens(256)
                        .temperature(0.0)
                        .build())
                .build();
    }
}
