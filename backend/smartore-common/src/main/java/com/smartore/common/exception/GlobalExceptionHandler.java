package com.smartore.common.exception;

import com.smartore.common.result.Result;
import com.smartore.common.result.ResultCodeEnum;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理：所有服务共享。
 * 业务异常原样透出（信息对用户有意义）；未知异常只记日志、对外返回统一系统异常，
 * 避免堆栈和内部细节泄漏。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(CustomException.class)
    public Result<Void> handleCustom(CustomException e) {
        return Result.error(e.getCode(), e.getMsg());
    }

    /** 唯一约束冲突统一转为"操作冲突"，幂等重放场景在上层已提前处理，落到这里的都是真冲突 */
    @ExceptionHandler(DuplicateKeyException.class)
    public Result<Void> handleDuplicateKey(DuplicateKeyException e) {
        log.warn("唯一约束冲突：{}", e.getMessage());
        return Result.error(ResultCodeEnum.CONFLICT);
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Result<Void> handleUnknown(Exception e) {
        log.error("未处理异常", e);
        return Result.error(ResultCodeEnum.SYSTEM_ERROR);
    }
}
