package com.mindcart.trade.entity;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 下单请求。
 * userId 字段保留但服务端不采信（UserContext 取当前登录用户）；
 * requestId 的长度上限与服务层既有校验一致，提前到入口拦掉。
 */
public class OrderCreateRequest {

    /** 幂等键：前端每次"提交订单"生成一次，网络重试/双击复用同一个值 */
    @NotBlank(message = "缺少 requestId（幂等键）")
    @Size(max = 64, message = "requestId 过长（≤64 字符）")
    private String requestId;

    private Integer userId;

    @NotBlank(message = "收货人不能为空")
    @Size(max = 50, message = "收货人姓名长度不能超过50")
    private String receiverName;

    @NotBlank(message = "收货人手机号不能为空")
    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "请输入正确的收货人手机号")
    private String receiverPhone;

    @NotBlank(message = "收货地址不能为空")
    @Size(max = 255, message = "收货地址长度不能超过255")
    private String receiverAddress;

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public Integer getUserId() {
        return userId;
    }

    public void setUserId(Integer userId) {
        this.userId = userId;
    }

    public String getReceiverName() {
        return receiverName;
    }

    public void setReceiverName(String receiverName) {
        this.receiverName = receiverName;
    }

    public String getReceiverPhone() {
        return receiverPhone;
    }

    public void setReceiverPhone(String receiverPhone) {
        this.receiverPhone = receiverPhone;
    }

    public String getReceiverAddress() {
        return receiverAddress;
    }

    public void setReceiverAddress(String receiverAddress) {
        this.receiverAddress = receiverAddress;
    }
}
