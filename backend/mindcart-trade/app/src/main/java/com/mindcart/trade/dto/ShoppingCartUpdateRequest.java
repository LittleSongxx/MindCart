package com.mindcart.trade.dto;

import com.mindcart.trade.entity.ShoppingCart;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

/**
 * 修改购物车行请求（改数量 / 改勾选）。
 *
 * productId 保留：语音导购面板改勾选时会连商品一起回传，且服务层只按 id 定位行、
 * 不用 productId 做归属判定，收下不影响正确性。归属校验仍在服务层（requireOwned）。
 */
@Getter
@Setter
public class ShoppingCartUpdateRequest {

    @NotNull(message = "购物车行ID不能为空")
    private Integer id;

    @NotNull(message = "数量不能为空")
    @Min(value = 1, message = "数量最少为1")
    @Max(value = 99, message = "单个商品最多加购99件")
    private Integer quantity;

    @Min(value = 0, message = "勾选状态只能是0或1")
    @Max(value = 1, message = "勾选状态只能是0或1")
    private Integer selected;

    private Integer productId;

    public ShoppingCart toEntity() {
        ShoppingCart cart = new ShoppingCart();
        cart.setId(id);
        cart.setQuantity(quantity);
        cart.setSelected(selected);
        cart.setProductId(productId);
        return cart;
    }
}
