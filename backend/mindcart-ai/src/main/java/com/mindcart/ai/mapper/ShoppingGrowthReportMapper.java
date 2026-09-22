package com.mindcart.ai.mapper;

import com.mindcart.ai.entity.ShoppingGrowthReport;
import java.util.List;

public interface ShoppingGrowthReportMapper {

    int insert(ShoppingGrowthReport shoppingGrowthReport);

    void deleteById(Integer id);

    List<ShoppingGrowthReport> selectAll(ShoppingGrowthReport shoppingGrowthReport);
}
