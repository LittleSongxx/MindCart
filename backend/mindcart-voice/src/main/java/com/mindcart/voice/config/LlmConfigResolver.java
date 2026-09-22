package com.mindcart.voice.config;

import com.mindcart.common.result.Result;
import com.mindcart.voice.integration.ModelConfigClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 语音链路 LLM 配置解析：优先用 ai 服务后台【AI模型配置】里当前启用的配置
 * （/internal/model-config/active），拉不到或字段不全时回退到本地 yml 默认值。
 * <p>
 * 解析只在首次使用时发生一次并缓存（chat 模型 Bean 构建即触发），运行期不热更新——
 * ai 侧改了配置后 voice 需要重启才跟随，这是有意取舍：语音会话进行中途换模型
 * 会造成同一会话前后行为不一致。
 * <p>
 * EMBEDDING 只在远端是 DashScope 系端点时采纳：voice 的向量走 DashScope 原生 SDK
 * （TextEmbedding），换成别家端点会直接调用失败，此时保留本地配置更安全。
 */
@Slf4j
@Component
public class LlmConfigResolver {

    private final ModelConfigClient modelConfigClient;

    @Value("${voice-shopping.llm.base-url}")
    private String localBaseUrl;

    @Value("${voice-shopping.llm.api-key}")
    private String localApiKey;

    @Value("${voice-shopping.llm.main-model}")
    private String localChatModel;

    @Value("${agentscope.dashscope.api-key}")
    private String localEmbedApiKey;

    @Value("${voice-shopping.embedding.model}")
    private String localEmbedModel;

    /** 一次性解析结果；null 表示还没拉取过 */
    private volatile Map<String, ModelConfigClient.ActiveModelConfigVO> remote;

    public LlmConfigResolver(ModelConfigClient modelConfigClient) {
        this.modelConfigClient = modelConfigClient;
    }

    public String chatBaseUrl() {
        ModelConfigClient.ActiveModelConfigVO cfg = remote("CHAT");
        return cfg != null && notBlank(cfg.getBaseUrl()) ? cfg.getBaseUrl() : localBaseUrl;
    }

    public String chatApiKey() {
        ModelConfigClient.ActiveModelConfigVO cfg = remote("CHAT");
        return cfg != null && notBlank(cfg.getApiKey()) ? cfg.getApiKey() : localApiKey;
    }

    /** main/mid/light 三档统一跟随远端 CHAT 模型名：分档是本地抽象，模型选择归后台管 */
    public String chatModelName() {
        ModelConfigClient.ActiveModelConfigVO cfg = remote("CHAT");
        return cfg != null && notBlank(cfg.getModelName()) ? cfg.getModelName() : localChatModel;
    }

    public String embedApiKey() {
        ModelConfigClient.ActiveModelConfigVO cfg = remoteDashscopeEmbedding();
        return cfg != null ? cfg.getApiKey() : localEmbedApiKey;
    }

    public String embedModel() {
        ModelConfigClient.ActiveModelConfigVO cfg = remoteDashscopeEmbedding();
        return cfg != null ? cfg.getModelName() : localEmbedModel;
    }

    /** 远端 EMBEDDING 仅当是 DashScope 端点时采纳（原生 SDK 限制），否则 null → 用本地 */
    private ModelConfigClient.ActiveModelConfigVO remoteDashscopeEmbedding() {
        ModelConfigClient.ActiveModelConfigVO cfg = remote("EMBEDDING");
        if (cfg == null || !notBlank(cfg.getApiKey()) || !notBlank(cfg.getModelName())) {
            return null;
        }
        String url = cfg.getBaseUrl() == null ? "" : cfg.getBaseUrl();
        if (!url.contains("dashscope")) {
            log.warn("远端 EMBEDDING 端点 {} 非 DashScope，voice 向量仍用本地配置 {}", url, localEmbedModel);
            return null;
        }
        return cfg;
    }

    /** 首次解析最多重试 5 次 × 4s：容器编排里 ai 与 voice 常同时拉起，一次失败就锁死
     *  本地兜底会让"跟随后台配置"形同虚设；重试只在首次使用时发生一次 */
    private static final int FETCH_MAX_ATTEMPTS = 5;
    private static final long FETCH_RETRY_INTERVAL_MS = 4000;

    private ModelConfigClient.ActiveModelConfigVO remote(String type) {
        Map<String, ModelConfigClient.ActiveModelConfigVO> r = remote;
        if (r == null) {
            synchronized (this) {
                if (remote == null) {
                    remote = fetchWithRetry();
                }
                r = remote;
            }
        }
        return r.get(type);
    }

    private Map<String, ModelConfigClient.ActiveModelConfigVO> fetchWithRetry() {
        for (int attempt = 1; attempt <= FETCH_MAX_ATTEMPTS; attempt++) {
            Map<String, ModelConfigClient.ActiveModelConfigVO> result = fetch();
            if (!result.isEmpty()) {
                return result;
            }
            if (attempt < FETCH_MAX_ATTEMPTS) {
                try {
                    Thread.sleep(FETCH_RETRY_INTERVAL_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        log.warn("mindcart-ai 模型配置 {} 次拉取均失败，voice 本次运行用本地 yml 兜底", FETCH_MAX_ATTEMPTS);
        return Map.of();
    }

    private Map<String, ModelConfigClient.ActiveModelConfigVO> fetch() {
        try {
            Result<Map<String, ModelConfigClient.ActiveModelConfigVO>> res = modelConfigClient.active();
            if (res != null && "200".equals(res.getCode()) && res.getData() != null) {
                log.info("已从 mindcart-ai 拉取模型配置：{}", res.getData().keySet());
                return res.getData();
            }
            log.warn("mindcart-ai 模型配置返回异常（{}），voice 用本地 yml 兜底", res == null ? "null" : res.getMsg());
        } catch (Exception e) {
            log.warn("拉取 mindcart-ai 模型配置失败（{}），voice 用本地 yml 兜底", e.getMessage());
        }
        return Map.of();
    }

    private boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
