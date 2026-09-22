package com.mindcart.ai.entity;

public class EmbeddingSearchRequest {

    /** 检索通道：dense（仅向量，对照用）| hybrid（BM25+向量 RRF 融合，默认） */
    private String mode;

    private String queryText;
    private Integer topK;
    private Integer productId;

    public String getQueryText() {
        return queryText;
    }

    public void setQueryText(String queryText) {
        this.queryText = queryText;
    }

    public Integer getTopK() {
        return topK;
    }

    public void setTopK(Integer topK) {
        this.topK = topK;
    }

    public Integer getProductId() {
        return productId;
    }

    public void setProductId(Integer productId) {
        this.productId = productId;
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }
}
