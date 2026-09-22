package com.mindcart.ai.mapper;

import com.mindcart.ai.entity.ProductKnowledgeChunk;

import java.util.List;

public interface ProductKnowledgeChunkMapper {

    int insert(ProductKnowledgeChunk productKnowledgeChunk);

    void deleteById(Integer id);

    void deleteByKnowledgeId(Integer knowledgeId);

    void deleteBatch(List<Integer> ids);

    List<ProductKnowledgeChunk> selectAll(ProductKnowledgeChunk productKnowledgeChunk);
}
