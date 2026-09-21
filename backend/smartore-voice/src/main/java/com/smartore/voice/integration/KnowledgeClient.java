package com.smartore.voice.integration;

import com.smartore.common.result.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;

/**
 * ai 域知识检索契约（集群内 Feign）。语音侧不复制知识资产，
 * 政策/售后/物流类问题直接复用 ai 的知识库检索（只读）。
 */
@FeignClient(name = "smartore-ai", contextId = "voiceKnowledgeClient")
public interface KnowledgeClient {

    @PostMapping("/internal/knowledge/search")
    Result<List<KnowledgeHitVO>> search(@RequestBody SearchRequest request);

    class SearchRequest {
        private String queryText;
        private Integer topK;

        public SearchRequest() {}
        public SearchRequest(String queryText, Integer topK) {
            this.queryText = queryText;
            this.topK = topK;
        }
        public String getQueryText() { return queryText; }
        public void setQueryText(String queryText) { this.queryText = queryText; }
        public Integer getTopK() { return topK; }
        public void setTopK(Integer topK) { this.topK = topK; }
    }

    class KnowledgeHitVO {
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
}
