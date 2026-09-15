package com.smartore.ai.mapper;

import com.smartore.ai.entity.ProductKnowledge;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface ProductKnowledgeMapper {

    int insert(ProductKnowledge productKnowledge);

    void updateById(ProductKnowledge productKnowledge);

    void deleteById(Integer id);

    @Select("select * from product_knowledge where id = #{id}")
    ProductKnowledge selectById(Integer id);

    List<ProductKnowledge> selectAll(ProductKnowledge productKnowledge);
}
