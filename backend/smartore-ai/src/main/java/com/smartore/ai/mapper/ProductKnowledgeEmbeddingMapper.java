package com.smartore.ai.mapper;

import com.smartore.ai.entity.ProductKnowledgeEmbedding;

import java.util.List;

public interface ProductKnowledgeEmbeddingMapper {

    int insert(ProductKnowledgeEmbedding productKnowledgeEmbedding);

    void deleteById(Integer id);

    void deleteByChunkId(Integer chunkId);

    void deleteByKnowledgeId(Integer knowledgeId);

    void deleteBatch(List<Integer> ids);

    List<ProductKnowledgeEmbedding> selectAll(ProductKnowledgeEmbedding productKnowledgeEmbedding);
}
