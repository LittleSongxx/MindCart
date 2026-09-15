package com.smartore.ai.mapper;

import com.smartore.ai.entity.ShoppingGrowthReport;
import java.util.List;

public interface ShoppingGrowthReportMapper {

    int insert(ShoppingGrowthReport shoppingGrowthReport);

    void deleteById(Integer id);

    List<ShoppingGrowthReport> selectAll(ShoppingGrowthReport shoppingGrowthReport);
}
