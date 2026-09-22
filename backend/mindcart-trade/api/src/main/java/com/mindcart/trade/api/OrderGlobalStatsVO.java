package com.mindcart.trade.api;

import java.math.BigDecimal;

/** 全店订单统计（AI 成长报告素材） */
public class OrderGlobalStatsVO {

    private Integer orderCount;
    private BigDecimal salesAmount;

    public Integer getOrderCount() {
        return orderCount;
    }

    public void setOrderCount(Integer orderCount) {
        this.orderCount = orderCount;
    }

    public BigDecimal getSalesAmount() {
        return salesAmount;
    }

    public void setSalesAmount(BigDecimal salesAmount) {
        this.salesAmount = salesAmount;
    }
}
