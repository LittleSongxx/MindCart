package com.mindcart.ai.entity;

public class ProductKnowledgeEmbedding {

    private Integer id;
    private Integer chunkId;
    private Integer knowledgeId;
    private Integer productId;
    private String embeddingModel;
    private Integer vectorDimension;
    private String vectorText;
    private String embeddingStatus;
    private String createTime;
    private String updateTime;
    private String productName;
    private String productNo;
    private String knowledgeTitle;
    private String chunkTitle;
    private String chunkContent;
    private Double similarityScore;
    /** 是否被 BM25 关键词通道命中（混合检索的展示/门控信号，不落库） */
    private Boolean keywordHit;

    public Integer getId() {
        return id;
    }

    public void setId(Integer id) {
        this.id = id;
    }

    public Integer getChunkId() {
        return chunkId;
    }

    public void setChunkId(Integer chunkId) {
        this.chunkId = chunkId;
    }

    public Integer getKnowledgeId() {
        return knowledgeId;
    }

    public void setKnowledgeId(Integer knowledgeId) {
        this.knowledgeId = knowledgeId;
    }

    public Integer getProductId() {
        return productId;
    }

    public void setProductId(Integer productId) {
        this.productId = productId;
    }

    public String getEmbeddingModel() {
        return embeddingModel;
    }

    public void setEmbeddingModel(String embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    public Integer getVectorDimension() {
        return vectorDimension;
    }

    public void setVectorDimension(Integer vectorDimension) {
        this.vectorDimension = vectorDimension;
    }

    public String getVectorText() {
        return vectorText;
    }

    public void setVectorText(String vectorText) {
        this.vectorText = vectorText;
    }

    public String getEmbeddingStatus() {
        return embeddingStatus;
    }

    public void setEmbeddingStatus(String embeddingStatus) {
        this.embeddingStatus = embeddingStatus;
    }

    public String getCreateTime() {
        return createTime;
    }

    public void setCreateTime(String createTime) {
        this.createTime = createTime;
    }

    public String getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(String updateTime) {
        this.updateTime = updateTime;
    }

    public String getProductName() {
        return productName;
    }

    public void setProductName(String productName) {
        this.productName = productName;
    }

    public String getProductNo() {
        return productNo;
    }

    public void setProductNo(String productNo) {
        this.productNo = productNo;
    }

    public String getKnowledgeTitle() {
        return knowledgeTitle;
    }

    public void setKnowledgeTitle(String knowledgeTitle) {
        this.knowledgeTitle = knowledgeTitle;
    }

    public String getChunkTitle() {
        return chunkTitle;
    }

    public void setChunkTitle(String chunkTitle) {
        this.chunkTitle = chunkTitle;
    }

    public String getChunkContent() {
        return chunkContent;
    }

    public void setChunkContent(String chunkContent) {
        this.chunkContent = chunkContent;
    }

    public Double getSimilarityScore() {
        return similarityScore;
    }

    public Boolean getKeywordHit() {
        return keywordHit;
    }

    public void setKeywordHit(Boolean keywordHit) {
        this.keywordHit = keywordHit;
    }

    public void setSimilarityScore(Double similarityScore) {
        this.similarityScore = similarityScore;
    }
}
