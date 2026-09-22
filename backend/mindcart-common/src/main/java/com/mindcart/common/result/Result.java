package com.mindcart.common.result;

import java.io.Serializable;

/**
 * 统一返回体。code 为字符串错误码，沿用前端已识别的 "200"/"401" 等约定。
 */
public class Result<T> implements Serializable {

    private String code;
    private String msg;
    private T data;

    public static <T> Result<T> success() {
        return build(ResultCodeEnum.SUCCESS.getCode(), ResultCodeEnum.SUCCESS.getMsg(), null);
    }

    public static <T> Result<T> success(T data) {
        return build(ResultCodeEnum.SUCCESS.getCode(), ResultCodeEnum.SUCCESS.getMsg(), data);
    }

    public static <T> Result<T> error(String code, String msg) {
        return build(code, msg, null);
    }

    public static <T> Result<T> error(ResultCodeEnum codeEnum) {
        return build(codeEnum.getCode(), codeEnum.getMsg(), null);
    }

    /** 用错误码语义 + 定制提示（与 CustomException 的同名重载口径一致） */
    public static <T> Result<T> error(ResultCodeEnum codeEnum, String msg) {
        return build(codeEnum.getCode(), msg, null);
    }

    private static <T> Result<T> build(String code, String msg, T data) {
        Result<T> result = new Result<>();
        result.setCode(code);
        result.setMsg(msg);
        result.setData(data);
        return result;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getMsg() {
        return msg;
    }

    public void setMsg(String msg) {
        this.msg = msg;
    }

    public T getData() {
        return data;
    }

    public void setData(T data) {
        this.data = data;
    }
}
