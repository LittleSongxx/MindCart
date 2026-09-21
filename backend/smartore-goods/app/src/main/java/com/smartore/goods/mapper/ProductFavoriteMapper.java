package com.smartore.goods.mapper;

import com.smartore.goods.entity.ProductFavorite;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface ProductFavoriteMapper {

    int insert(ProductFavorite productFavorite);

    void deleteById(Integer id);

    @Select("select * from product_favorite where user_id = #{userId} and product_id = #{productId}")
    ProductFavorite selectByUserIdAndProductId(@Param("userId") Integer userId, @Param("productId") Integer productId);

    List<ProductFavorite> selectAll(ProductFavorite productFavorite);

    @Select("select * from product_favorite where id = #{id}")
    ProductFavorite selectById(Integer id);
}
