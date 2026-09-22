package com.mindcart.ai.mapper;

import com.mindcart.ai.entity.ShoppingReviewAnalysis;
import java.util.List;

public interface ShoppingReviewAnalysisMapper {

    int insert(ShoppingReviewAnalysis shoppingReviewAnalysis);

    void deleteById(Integer id);

    void deleteByProductId(Integer productId);

    List<ShoppingReviewAnalysis> selectAll(ShoppingReviewAnalysis shoppingReviewAnalysis);
}
