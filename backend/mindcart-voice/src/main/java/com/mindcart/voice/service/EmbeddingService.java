package com.mindcart.voice.service;

import com.alibaba.dashscope.embeddings.TextEmbedding;
import com.alibaba.dashscope.embeddings.TextEmbeddingParam;
import com.alibaba.dashscope.embeddings.TextEmbeddingResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@lombok.RequiredArgsConstructor
public class EmbeddingService {

    private final LlmGuard llmGuard;

    /** apiKey/model 统一由 LlmConfigResolver 解析（ai 后台 EMBEDDING 配置优先，yml 兜底） */
    private final com.mindcart.voice.config.LlmConfigResolver llmConfigResolver;

    private final TextEmbedding embedding = new TextEmbedding();

    /**
     * 返回 float[]，pgvector Java 客户端要的就是这种类型。
     * sync = true：热点文本缓存过期瞬间只放一个线程回源（单 JVM 单飞防击穿），
     * 避免并发请求同时打 Embedding API。
     */
    // 缓存 key 带模型名：换向量模型后旧缓存的向量维度/语义都失效，必须自然隔离
    @Cacheable(value = "embed", sync = true,
            key = "#root.target.currentModel() + ':' + T(org.springframework.util.DigestUtils).md5DigestAsHex(#text.getBytes())")
    public float[] embed(String text) {
        // 纳入 LLM 熔断统计：Embedding 服务故障时熔断期内快速失败，
        // 不让每一次向量检索都排队等 SDK 超时；降级策略是原样抛出（无合理兜底值）
        return llmGuard.call(() -> doEmbed(text),
                () -> { throw new RuntimeException("Embedding 调用失败（熔断或超时降级）"); });
    }

    /** 供 @Cacheable 的 key 表达式读取当前生效的向量模型名 */
    public String currentModel() {
        return llmConfigResolver.embedModel();
    }

    private float[] doEmbed(String text) {
        try {
            TextEmbeddingParam param = TextEmbeddingParam.builder()
                    .apiKey(llmConfigResolver.embedApiKey())
                    .model(llmConfigResolver.embedModel())
                    .text(text)
                    .build();
            TextEmbeddingResult result = embedding.call(param);
            List<Double> raw = result.getOutput().getEmbeddings().get(0).getEmbedding();
            float[] out = new float[raw.size()];
            for (int i = 0; i < raw.size(); i++) out[i] = raw.get(i).floatValue();
            return out;
        } catch (Exception e) {
            log.error("Embedding 失败：{}", e.getMessage(), e);
            throw new RuntimeException("Embedding 调用失败", e);
        }
    }
}