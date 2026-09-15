package com.smartore.goods.mapper;

import com.smartore.goods.entity.ProductCategory;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface ProductCategoryMapper {

    int insert(ProductCategory productCategory);

    void updateById(ProductCategory productCategory);

    void deleteById(Integer id);

    List<ProductCategory> selectAll(ProductCategory productCategory);
}
