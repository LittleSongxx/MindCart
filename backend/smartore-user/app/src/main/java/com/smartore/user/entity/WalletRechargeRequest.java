package com.smartore.user.entity;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public class WalletRechargeRequest {

    /** 归属由服务层取当前登录用户，请求体里的值不参与判定 */
    private Integer userId;

    @NotNull(message = "充值金额不能为空")
    @DecimalMin(value = "0.01", message = "充值金额必须大于0")
    @DecimalMax(value = "10000", message = "单笔充值上限10000元")
    private BigDecimal amount;

    @Size(max = 100, message = "备注长度不能超过100")
    private String remark;

    /** 客户端生成的充值幂等键（UUID）：双击/重试同一键只入账一次；空则退化为随机单号 */
    @Size(max = 64, message = "幂等键长度不能超过64")
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
