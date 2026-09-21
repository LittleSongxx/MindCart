package com.smartore.trade.entity;

/** 下单幂等记录：request_id 唯一，重放返回原订单（防双击/网络重试重复下单） */
public class OrderRequestIdempotency {

    private Integer id;
    private String requestId;
    /** 请求内容指纹（userId+收货人三要素）。购物车属服务端状态且下单后即清空，不参与指纹；同 requestId 重放=返回原订单，同 requestId 不同内容视为冲突 */
    private String requestHash;
    private Integer orderId;
    /** 关联订单号（恢复任务反查用） */
    private String orderNo;
    private String status;
    private String createTime;
    private String updateTime;

    public Integer getId() {
        return id;
    }

    public void setId(Integer id) {
        this.id = id;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public void setRequestHash(String requestHash) {
        this.requestHash = requestHash;
    }

    public Integer getOrderId() {
        return orderId;
    }

    public void setOrderId(Integer orderId) {
        this.orderId = orderId;
    }

    public String getOrderNo() {
        return orderNo;
    }

    public void setOrderNo(String orderNo) {
        this.orderNo = orderNo;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getCreateTime() {
        return createTime;
    }

    public void setCreateTime(String createTime) {
        this.createTime = createTime;
    }

    public String getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(String updateTime) {
        this.updateTime = updateTime;
    }
}
