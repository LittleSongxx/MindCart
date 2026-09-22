package com.mindcart.goods.mapper;

import com.mindcart.goods.entity.ProductCategory;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface ProductCategoryMapper {

    int insert(ProductCategory productCategory);

    void updateById(ProductCategory productCategory);

    void deleteById(Integer id);

    List<ProductCategory> selectAll(ProductCategory productCategory);
}
