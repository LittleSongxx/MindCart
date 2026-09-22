package com.mindcart.trade.entity;

/** 交易事件账本（Outbox）：与订单状态变更加同一本地事务写入，投递失败由中继重试 */
public class TradeEventLedger {

    public static final String PENDING = "PENDING";
    public static final String PUBLISHED = "PUBLISHED";

    private Integer id;
    /** 事件ID = eventType:orderNo，唯一，天然幂等 */
    private String eventId;
    private String eventType;
    private String orderNo;
    private Integer userId;
    private String payload;
    private String publishStatus;
    private Integer publishAttempts;
    private String createTime;
    private String publishTime;

    public Integer getId() {
        return id;
    }

    public void setId(Integer id) {
        this.id = id;
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

    public String getPayload() {
        return payload;
    }

    public void setPayload(String payload) {
        this.payload = payload;
    }

    public String getPublishStatus() {
        return publishStatus;
    }

    public void setPublishStatus(String publishStatus) {
        this.publishStatus = publishStatus;
    }

    public Integer getPublishAttempts() {
        return publishAttempts;
    }

    public void setPublishAttempts(Integer publishAttempts) {
        this.publishAttempts = publishAttempts;
    }

    public String getCreateTime() {
        return createTime;
    }

    public void setCreateTime(String createTime) {
        this.createTime = createTime;
    }

    public String getPublishTime() {
        return publishTime;
    }

    public void setPublishTime(String publishTime) {
        this.publishTime = publishTime;
    }
}
