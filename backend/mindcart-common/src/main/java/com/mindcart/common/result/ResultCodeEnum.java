package com.mindcart.common.result;

import cn.hutool.core.util.StrUtil;

/**
 * 全局错误码。沿用单体版本的字符串码，前端按 401 跳登录等约定不变。
 */
public enum ResultCodeEnum {

    SUCCESS("200", "成功"),
    SYSTEM_ERROR("500", "系统异常"),
    PARAM_LOST_ERROR("4001", "参数缺失"),
    PARAM_ERROR("4002", "参数错误"),
    PARAM_PASSWORD_ERROR("4003", "原密码错误"),
    UNAUTHORIZED("401", "未登录或身份已失效"),

    TOKEN_INVALID_ERROR("401", "未登录或 Token 缺失"),
    TOKEN_CHECK_ERROR("401", "Token 无效或已过期"),
    FORBIDDEN("403", "没有权限执行该操作"),
    CONFLICT("409", "操作冲突，请刷新后重试"),

    USER_NOT_EXIST_ERROR("1001", "用户不存在"),
    USER_EXIST_ERROR("1002", "用户名已存在"),
    USER_ACCOUNT_ERROR("1003", "账号或密码错误"),
    BALANCE_NOT_ENOUGH("1004", "余额不足"),
    STOCK_NOT_ENOUGH("1005", "商品库存不足"),

    ORDER_STATUS_ERROR("2001", "订单状态不允许该操作"),
    ORDER_NOT_EXIST_ERROR("2002", "订单不存在"),

    AI_MODEL_NOT_CONFIGURED("3001", "未配置启用的 AI 模型"),
    AI_MODEL_CALL_ERROR("3002", "AI 模型调用失败");

    private final String code;
    private final String msg;

    ResultCodeEnum(String code, String msg) {
        this.code = code;
        this.msg = msg;
    }

    public String getCode() {
        return code;
    }

    public String getMsg() {
        return StrUtil.isBlank(msg) ? name() : msg;
    }
}
