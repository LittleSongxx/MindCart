package com.mindcart.trade.dto;

import com.mindcart.trade.entity.ShoppingCart;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

/**
 * 加入购物车请求。
 * 不含 userId（服务层取当前登录用户）、不含 createTime/updateTime（服务端生成）。
 * 上限 99 与服务层既有口径一致，提前到入口拦掉。
 */
@Getter
@Setter
public class ShoppingCartAddRequest {

    @NotNull(message = "商品ID不能为空")
    private Integer productId;

    @NotNull(message = "数量不能为空")
    @Min(value = 1, message = "数量最少为1")
    @Max(value = 99, message = "单个商品最多加购99件")
    private Integer quantity;

    /** 加购后是否勾选，默认勾选；语音代下单流程会显式置 0/1 */
    @Min(value = 0, message = "勾选状态只能是0或1")
    @Max(value = 1, message = "勾选状态只能是0或1")
    private Integer selected;

    public ShoppingCart toEntity() {
        ShoppingCart cart = new ShoppingCart();
        cart.setProductId(productId);
        cart.setQuantity(quantity);
        cart.setSelected(selected);
        return cart;
    }
}
