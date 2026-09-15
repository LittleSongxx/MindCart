package com.smartore.ai.mapper;

import com.smartore.ai.entity.ProductKnowledgeChunk;

import java.util.List;

public interface ProductKnowledgeChunkMapper {

    int insert(ProductKnowledgeChunk productKnowledgeChunk);

    void deleteById(Integer id);

    void deleteByKnowledgeId(Integer knowledgeId);

    void deleteBatch(List<Integer> ids);

    List<ProductKnowledgeChunk> selectAll(ProductKnowledgeChunk productKnowledgeChunk);
}
