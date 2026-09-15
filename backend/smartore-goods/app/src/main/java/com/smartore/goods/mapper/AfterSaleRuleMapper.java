package com.smartore.goods.mapper;

import com.smartore.goods.entity.AfterSaleRule;
import java.util.List;

public interface AfterSaleRuleMapper {

    int insert(AfterSaleRule afterSaleRule);

    void updateById(AfterSaleRule afterSaleRule);

    void deleteById(Integer id);

    List<AfterSaleRule> selectAll(AfterSaleRule afterSaleRule);
}
