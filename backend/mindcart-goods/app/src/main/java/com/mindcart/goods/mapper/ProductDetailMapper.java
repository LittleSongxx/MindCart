package com.mindcart.goods.mapper;

import com.mindcart.goods.entity.ProductDetail;
import org.apache.ibatis.annotations.Select;

public interface ProductDetailMapper {

    int insert(ProductDetail productDetail);

    void updateById(ProductDetail productDetail);

    @Select("select * from product_detail where product_id = #{productId}")
    ProductDetail selectByProductId(Integer productId);
}
