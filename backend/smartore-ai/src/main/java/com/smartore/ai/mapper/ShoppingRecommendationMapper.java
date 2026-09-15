package com.smartore.ai.mapper;

import com.smartore.ai.entity.ShoppingRecommendation;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface ShoppingRecommendationMapper {

    int insert(ShoppingRecommendation shoppingRecommendation);

    void deleteById(Integer id);

    void deleteByTaskId(Integer taskId);

    @Select("select * from shopping_recommendation where id = #{id}")
    ShoppingRecommendation selectById(Integer id);

    List<ShoppingRecommendation> selectAll(ShoppingRecommendation shoppingRecommendation);
}
