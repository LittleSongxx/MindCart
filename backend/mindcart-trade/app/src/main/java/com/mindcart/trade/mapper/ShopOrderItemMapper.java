package com.mindcart.trade.mapper;

import com.mindcart.trade.entity.ShopOrderItem;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface ShopOrderItemMapper {

    int insert(ShopOrderItem shopOrderItem);

    @Select("select * from shop_order_item where id = #{id}")
    ShopOrderItem selectById(Integer id);

    List<ShopOrderItem> selectByOrderId(Integer orderId);
}
