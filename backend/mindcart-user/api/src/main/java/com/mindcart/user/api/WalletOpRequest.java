package com.mindcart.user.api;

import java.math.BigDecimal;

/**
 * 钱包 Saga 步骤请求。所有操作以 orderNo 为幂等键：同单重放不产生二次资金变动。
 */
public class WalletOpRequest {

    /** 幂等键（订单号） */
    private String orderNo;
    /** 目标用户（平台侧操作为空，服务端取第一个管理员作为平台账户） */
    private Integer userId;
    /** 金额（正数，方向由操作语义决定） */
    private BigDecimal amount;
    private String remark;

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

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }
}
