package com.smartore.goods.mapper;

import com.smartore.goods.entity.ProductParam;
import java.util.List;

public interface ProductParamMapper {

    int insert(ProductParam productParam);

    void updateById(ProductParam productParam);

    void deleteById(Integer id);

    List<ProductParam> selectAll(ProductParam productParam);
}
