package com.smartore.user.entity;

import java.math.BigDecimal;

public class WalletRechargeRequest {

    private Integer userId;
    private BigDecimal amount;
    private String remark;
    /** 客户端生成的充值幂等键（UUID）：双击/重试同一键只入账一次；空则退化为随机单号 */
    private String requestId;

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

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }
}
