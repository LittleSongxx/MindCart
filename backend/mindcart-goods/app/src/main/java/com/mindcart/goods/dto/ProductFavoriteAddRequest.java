package com.mindcart.goods.dto;

import com.mindcart.goods.entity.ProductFavorite;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

/** 收藏请求：归属只认当前登录用户，请求体只提供商品 */
@Getter
@Setter
public class ProductFavoriteAddRequest {

    @NotNull(message = "商品ID不能为空")
    private Integer productId;

    public ProductFavorite toEntity() {
        ProductFavorite favorite = new ProductFavorite();
        favorite.setProductId(productId);
        return favorite;
    }
}
