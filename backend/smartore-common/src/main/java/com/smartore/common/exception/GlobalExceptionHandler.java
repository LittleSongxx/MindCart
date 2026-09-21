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
 * 业务异常原样透出（信息对用户有意义），并按错误码映射真实 HTTP 状态码
 * （监控/WAF/网关按状态码统计才有意义，全部 200 会掩盖 401/403/409）；
 * 未知异常只记日志、对外返回统一系统异常，避免堆栈和内部细节泄漏。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(CustomException.class)
    public org.springframework.http.ResponseEntity<Result<Void>> handleCustom(CustomException e) {
        return org.springframework.http.ResponseEntity.status(httpStatusOf(e.getCode()))
                .body(Result.error(e.getCode(), e.getMsg()));
    }

    /** 唯一约束冲突统一转为"操作冲突"，幂等重放场景在上层已提前处理，落到这里的都是真冲突 */
    @ExceptionHandler(DuplicateKeyException.class)
    public org.springframework.http.ResponseEntity<Result<Void>> handleDuplicateKey(DuplicateKeyException e) {
        log.warn("唯一约束冲突：{}", e.getMessage());
        return org.springframework.http.ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Result.error(ResultCodeEnum.CONFLICT));
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Result<Void> handleUnknown(Exception e) {
        log.error("未处理异常", e);
        return Result.error(ResultCodeEnum.SYSTEM_ERROR);
    }

    /** 业务错误码 → HTTP 状态码：401/403/404/409 原样，4xxx→400，其余→500 */
    private HttpStatus httpStatusOf(String code) {
        if (code != null) {
            switch (code) {
                case "401": return HttpStatus.UNAUTHORIZED;
                case "403": return HttpStatus.FORBIDDEN;
                case "404": return HttpStatus.NOT_FOUND;
                case "409": return HttpStatus.CONFLICT;
                default:
                    if (code.startsWith("4")) {
                        return HttpStatus.BAD_REQUEST;
                    }
            }
        }
        return HttpStatus.INTERNAL_SERVER_ERROR;
    }
}
