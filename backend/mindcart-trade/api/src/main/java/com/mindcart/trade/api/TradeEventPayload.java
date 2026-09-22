package com.mindcart.trade.api;

import java.math.BigDecimal;
import java.util.List;

/**
 * 交易域事件（Outbox → RabbitMQ → ai 服务镜像）。事件 ID = eventType:orderNo，天然幂等。
 */
public class TradeEventPayload {

    public static final String TYPE_ORDER_PAID = "ORDER_PAID";
    public static final String TYPE_ORDER_CANCELLED = "ORDER_CANCELLED";

    private String eventId;
    private String eventType;
    private String orderNo;
    private Integer userId;
    private BigDecimal amount;
    private List<ItemBrief> items;
    private String occurredAt;

    public static class ItemBrief {
        private Integer productId;
        private String productName;
        private Integer quantity;
        private BigDecimal price;

        public Integer getProductId() {
            return productId;
        }

        public void setProductId(Integer productId) {
            this.productId = productId;
        }

        public String getProductName() {
            return productName;
        }

        public void setProductName(String productName) {
            this.productName = productName;
        }

        public Integer getQuantity() {
            return quantity;
        }

        public void setQuantity(Integer quantity) {
            this.quantity = quantity;
        }

        public BigDecimal getPrice() {
            return price;
        }

        public void setPrice(BigDecimal price) {
            this.price = price;
        }
    }

    public String getEventId() {
        return eventId;
    }

    public void setEventId(String eventId) {
        this.eventId = eventId;
    }

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public String getOrderNo() {
        return orderNo;
    }

    public void setOrderNo(String orderNo) {
        this.orderNo = orderNo;
    }

    public Integer getUserId() {
        return userId;
    }

    public void setUserId(Integer userId) {
        this.userId = userId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public List<ItemBrief> getItems() {
        return items;
    }

    public void setItems(List<ItemBrief> items) {
        this.items = items;
    }

    public String getOccurredAt() {
        return occurredAt;
    }

    public void setOccurredAt(String occurredAt) {
        this.occurredAt = occurredAt;
    }
}
