package com.mindcart.goods.mapper;

import com.mindcart.goods.entity.ProductBrand;
import java.util.List;

public interface ProductBrandMapper {

    int insert(ProductBrand productBrand);

    void updateById(ProductBrand productBrand);

    void deleteById(Integer id);

    List<ProductBrand> selectAll(ProductBrand productBrand);
}
