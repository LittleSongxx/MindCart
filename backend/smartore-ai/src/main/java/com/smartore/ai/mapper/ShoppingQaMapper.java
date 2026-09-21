package com.smartore.ai.mapper;

import com.smartore.ai.entity.ShoppingQa;
import java.util.List;

public interface ShoppingQaMapper {

    int insert(ShoppingQa shoppingQa);

    void deleteById(Integer id);

    @org.apache.ibatis.annotations.Select("select * from shopping_qa where id = #{id}")
    com.smartore.ai.entity.ShoppingQa selectById(Integer id);

    List<ShoppingQa> selectAll(ShoppingQa shoppingQa);
}
