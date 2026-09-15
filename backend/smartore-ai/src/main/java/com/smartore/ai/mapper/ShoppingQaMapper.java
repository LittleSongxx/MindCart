package com.smartore.ai.mapper;

import com.smartore.ai.entity.ShoppingQa;
import java.util.List;

public interface ShoppingQaMapper {

    int insert(ShoppingQa shoppingQa);

    void deleteById(Integer id);

    List<ShoppingQa> selectAll(ShoppingQa shoppingQa);
}
