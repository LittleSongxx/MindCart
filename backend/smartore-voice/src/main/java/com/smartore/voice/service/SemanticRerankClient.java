package com.smartore.voice.service;

import com.smartore.voice.dto.RecommendedItem;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * qwen3.7-text-rerank 语义重排客户端（DashScope 原生 rerank 端点，网关实测可达）。
 * 对规则重排后的 Top8 做跨向量语义精排（cross-encoder 对 query-商品文本对打分），
 * 区分度远超向量召回（实测"缓震跑鞋"0.90 vs 口红 0.0007）。
 * 默认关闭：每次调用 +300~500ms，与语音首响目标冲突；文本场景或评测时开启。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SemanticRerankClient {

    private static final com.fasterxml.jackson.databind.json.JsonMapper JSON = com.fasterxml.jackson.databind.json.JsonMapper.builder().build();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    private final LlmGuard llmGuard;

    @Value("${agentscope.dashscope.api-key}")
    private String apiKey;

    @Value("${voice-shopping.recommend.semantic-rerank.model:qwen3.7-text-rerank}")
    private String model;

    /**
     * 对 items 做语义重排（失败降级返回原顺序——旁路增强不做阻塞依赖）。
     */
    public List<RecommendedItem> rerank(String query, List<RecommendedItem> items, int topN) {
        // 兼容旧签名：文档退化为 name+attributes（信息量低，优先用富文本重载）
        List<String> docs = new ArrayList<>();
        for (RecommendedItem it : items) docs.add(it.name() + " " + it.attributes());
        return rerank(query, items, docs, topN);
    }

    /**
     * 富文本重载：docs 与 items 按下标对应，应为与向量库同源的完整商品文本
     * （ProductTextBuilder 产物）——reranker 对文档信息量极其敏感，贫文本会显著降低精排质量。
     */
    public List<RecommendedItem> rerank(String query, List<RecommendedItem> items,
                                        List<String> docs, int topN) {
        if (items.size() <= 1) return items;
        // 熔断/超时/失败统一降级为原顺序（旁路增强不做阻塞依赖）；
        // 失败同时计入 LLM 熔断统计，rerank 端点故障不会持续拖慢推荐链路
        return llmGuard.call(() -> doRerank(query, items, docs, topN),
                () -> items.subList(0, Math.min(topN, items.size())));
    }

    private List<RecommendedItem> doRerank(String query, List<RecommendedItem> items,
                                           List<String> docs, int topN) {
        try {
            String body = "{\"model\":\"" + model + "\",\"input\":{\"query\":"
                    + JSON.writeValueAsString(query)
                    + ",\"documents\":" + JSON.writeValueAsString(docs)
                    + "},\"parameters\":{\"return_documents\":false}}";
            HttpRequest req = HttpRequest.newBuilder(URI.create(
                            "https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank"))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(5))
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                throw new IllegalStateException("rerank HTTP " + resp.statusCode());
            }
            var root = JSON.readTree(resp.body());
            var results = root.path("output").path("results");
            List<RecommendedItem> out = new ArrayList<>();
            for (int i = 0; i < results.size() && out.size() < topN; i++) {
                int idx = results.get(i).path("index").asInt();
                if (idx >= 0 && idx < items.size()) out.add(items.get(idx));
            }
            return out.isEmpty() ? items.subList(0, Math.min(topN, items.size())) : out;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("rerank 调用失败: " + e.getMessage(), e);
        }
    }
}
