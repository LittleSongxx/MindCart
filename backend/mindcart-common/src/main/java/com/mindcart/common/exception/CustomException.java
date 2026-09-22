package com.mindcart.common.exception;

import com.mindcart.common.result.ResultCodeEnum;
import cn.hutool.core.util.StrUtil;

/**
 * 业务异常：带错误码与用户可读信息。
 */
public class CustomException extends RuntimeException {

    private final String code;

    public CustomException(ResultCodeEnum codeEnum) {
        super(codeEnum.getMsg());
        this.code = codeEnum.getCode();
    }

    /** 用错误码语义 + 定制提示（提示优先于枚举默认文案） */
    public CustomException(ResultCodeEnum codeEnum, String msg) {
        super(msg);
        this.code = codeEnum.getCode();
    }

    public CustomException(String code, String msg) {
        super(msg);
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    public String getMsg() {
        return getMessage() == null ? StrUtil.EMPTY : getMessage();
    }
}
