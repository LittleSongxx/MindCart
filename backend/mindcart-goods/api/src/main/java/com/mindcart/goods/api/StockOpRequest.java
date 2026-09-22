package com.mindcart.goods.api;

import java.util.List;

/**
 * 库存 Saga 步骤请求：以 orderNo 为幂等键，一次覆盖订单全部行。
 */
public class StockOpRequest {

    /** 幂等键（订单号） */
    private String orderNo;
    private List<StockItem> items;

    public static class StockItem {
        private Integer productId;
        private Integer quantity;

        public Integer getProductId() {
            return productId;
        }

        public void setProductId(Integer productId) {
            this.productId = productId;
        }

        public Integer getQuantity() {
            return quantity;
        }

        public void setQuantity(Integer quantity) {
            this.quantity = quantity;
        }
    }

    public String getOrderNo() {
        return orderNo;
    }

    public void setOrderNo(String orderNo) {
        this.orderNo = orderNo;
    }

    public List<StockItem> getItems() {
        return items;
    }

    public void setItems(List<StockItem> items) {
        this.items = items;
    }
}
