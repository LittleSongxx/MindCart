package com.smartore.trade.api;

import java.math.BigDecimal;

/** 用户订单汇总（AI 用户画像/成长报告素材） */
public class OrderStatsVO {

    private Integer orderCount;
    private BigDecimal paidAmount;
    private BigDecimal refundedAmount;
    private String latestOrderStatus;
    private String latestOrderNo;

    public Integer getOrderCount() {
        return orderCount;
    }

    public void setOrderCount(Integer orderCount) {
        this.orderCount = orderCount;
    }

    public BigDecimal getPaidAmount() {
        return paidAmount;
    }

    public void setPaidAmount(BigDecimal paidAmount) {
        this.paidAmount = paidAmount;
    }

    public BigDecimal getRefundedAmount() {
        return refundedAmount;
    }

    public void setRefundedAmount(BigDecimal refundedAmount) {
        this.refundedAmount = refundedAmount;
    }

    public String getLatestOrderStatus() {
        return latestOrderStatus;
    }

    public void setLatestOrderStatus(String latestOrderStatus) {
        this.latestOrderStatus = latestOrderStatus;
    }

    public String getLatestOrderNo() {
        return latestOrderNo;
    }

    public void setLatestOrderNo(String latestOrderNo) {
        this.latestOrderNo = latestOrderNo;
    }
}
