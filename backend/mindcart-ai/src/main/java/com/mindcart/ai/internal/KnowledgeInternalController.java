package com.mindcart.ai.internal;

import com.mindcart.ai.entity.EmbeddingSearchRequest;
import com.mindcart.ai.entity.ProductKnowledgeEmbedding;
import com.mindcart.ai.service.ProductKnowledgeEmbeddingService;
import com.mindcart.common.result.Result;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * 集群内知识检索（仅 Feign，网关不路由 /internal/**）。
 * <p>
 * 供 mindcart-voice 的语音问答分支复用 ai 侧已有知识资产（139 条切片 + 向量 + 混合检索），
 * 避免语音域另建一套知识库。只读、无副作用；检索与阈值口径与站内文本问答
 * （ShoppingQaService）保持一致——低置信命中宁可不答，避免拿着无关资料硬答。
 */
@RestController
@RequestMapping("/internal/knowledge")
public class KnowledgeInternalController {

    /** 与 ShoppingQaService.MIN_SIMILARITY_SCORE 同口径 */
    private static final double MIN_SIMILARITY_SCORE = 0.5;

    @Resource
    private ProductKnowledgeEmbeddingService embeddingService;

    public static class SearchRequest {
        private String queryText;
        private Integer topK;
        private Double minScore;

        public String getQueryText() { return queryText; }
        public void setQueryText(String queryText) { this.queryText = queryText; }
        public Integer getTopK() { return topK; }
        public void setTopK(Integer topK) { this.topK = topK; }
        public Double getMinScore() { return minScore; }
        public void setMinScore(Double minScore) { this.minScore = minScore; }
    }

    public static class KnowledgeHit {
        private String title;
        private String content;
        private Double score;
        private Integer productId;
        private String productName;
        private Boolean keywordHit;

        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }
        public String getContent() { return content; }
        public void setContent(String content) { this.content = content; }
        public Double getScore() { return score; }
        public void setScore(Double score) { this.score = score; }
        public Integer getProductId() { return productId; }
        public void setProductId(Integer productId) { this.productId = productId; }
        public String getProductName() { return productName; }
        public void setProductName(String productName) { this.productName = productName; }
        public Boolean getKeywordHit() { return keywordHit; }
        public void setKeywordHit(Boolean keywordHit) { this.keywordHit = keywordHit; }
    }

    /** 全库检索（不限定商品）：政策/售后/物流类问题不属于单一商品 */
    @PostMapping("/search")
    public Result<List<KnowledgeHit>> search(@RequestBody SearchRequest req) {
        if (req == null || req.getQueryText() == null || req.getQueryText().isBlank()) {
            return Result.success(List.of());
        }
        double threshold = req.getMinScore() == null ? MIN_SIMILARITY_SCORE : req.getMinScore();

        EmbeddingSearchRequest search = new EmbeddingSearchRequest();
        search.setQueryText(req.getQueryText());
        search.setTopK(req.getTopK() == null ? 3 : req.getTopK());
        // productId 留空 = 全库候选（mapper 的动态条件在 productId 为空时不过滤）

        List<KnowledgeHit> out = new ArrayList<>();
        for (ProductKnowledgeEmbedding e : embeddingService.search(search)) {
            boolean above = e.getSimilarityScore() != null && e.getSimilarityScore() >= threshold;
            if (!above && !Boolean.TRUE.equals(e.getKeywordHit())) {
                continue;   // 与文本问答同口径：低于阈值且无关键词命中 → 不返回
            }
            KnowledgeHit hit = new KnowledgeHit();
            hit.setTitle(e.getChunkTitle() != null ? e.getChunkTitle() : e.getKnowledgeTitle());
            hit.setContent(e.getChunkContent());
            hit.setScore(e.getSimilarityScore());
            hit.setProductId(e.getProductId());
            hit.setProductName(e.getProductName());
            hit.setKeywordHit(e.getKeywordHit());
            out.add(hit);
        }
        return Result.success(out);
    }
}
