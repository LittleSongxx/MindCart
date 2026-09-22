package com.mindcart.ai.mapper;

import com.mindcart.ai.entity.ShoppingQa;
import java.util.List;

public interface ShoppingQaMapper {

    int insert(ShoppingQa shoppingQa);

    void deleteById(Integer id);

    @org.apache.ibatis.annotations.Select("select * from shopping_qa where id = #{id}")
    com.mindcart.ai.entity.ShoppingQa selectById(Integer id);

    List<ShoppingQa> selectAll(ShoppingQa shoppingQa);
}
