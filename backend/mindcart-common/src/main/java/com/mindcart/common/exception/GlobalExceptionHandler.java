package com.mindcart.common.exception;

import cn.hutool.core.util.StrUtil;
import com.mindcart.common.result.Result;
import com.mindcart.common.result.ResultCodeEnum;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.stream.Collectors;

/**
 * 全局异常处理：所有服务共享。
 * 业务异常原样透出（信息对用户有意义），并按错误码映射真实 HTTP 状态码
 * （监控/WAF/网关按状态码统计才有意义，全部 200 会掩盖 401/403/409）；
 * 未知异常只记日志、对外返回统一系统异常，避免堆栈和内部细节泄漏。
 *
 * 校验失败（Bean Validation / 参数绑定）一律归为「参数错误 4002」并带上字段名。
 * 这一步是引入 Bean Validation 的前置条件：@Valid 触发的 MethodArgumentNotValidException
 * 若不单独接管，会落到兜底分支变成 500——加了校验反而把 400 变成 500。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 字段级约束文案本就很短，超长只可能是异常信息混入，截断避免回显内部细节 */
    private static final int MSG_MAX_LENGTH = 200;

    @ExceptionHandler(CustomException.class)
    public ResponseEntity<Result<Void>> handleCustom(CustomException e) {
        return ResponseEntity.status(httpStatusOf(e.getCode()))
                .body(Result.error(e.getCode(), e.getMsg()));
    }

    /** 唯一约束冲突统一转为"操作冲突"，幂等重放场景在上层已提前处理，落到这里的都是真冲突 */
    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<Result<Void>> handleDuplicateKey(DuplicateKeyException e) {
        log.warn("唯一约束冲突：{}", e.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Result.error(ResultCodeEnum.CONFLICT));
    }

    /** @Valid @RequestBody 校验失败 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<Void>> handleMethodArgumentNotValid(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": "
                        + StrUtil.blankToDefault(error.getDefaultMessage(), "参数不合法"))
                .distinct()
                .collect(Collectors.joining("；"));
        return badRequest(detail);
    }

    /** 查询参数绑定到对象时的校验失败（GET 接口把实体当查询条件） */
    @ExceptionHandler(BindException.class)
    public ResponseEntity<Result<Void>> handleBind(BindException e) {
        String detail = e.getBindingResult().getAllErrors().stream()
                .map(MessageSourceResolvable::getDefaultMessage)
                .filter(StrUtil::isNotBlank)
                .distinct()
                .collect(Collectors.joining("；"));
        return badRequest(detail);
    }

    /** 控制器方法参数上的内联约束失败（@RequestParam @Max 等，Spring 6.1+ 内建方法校验） */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<Result<Void>> handleHandlerMethodValidation(HandlerMethodValidationException e) {
        String detail = e.getAllValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream())
                .map(MessageSourceResolvable::getDefaultMessage)
                .filter(StrUtil::isNotBlank)
                .distinct()
                .collect(Collectors.joining("；"));
        return badRequest(detail);
    }

    /** @Validated 类级校验（AOP 代理路径）失败 */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Result<Void>> handleConstraintViolation(ConstraintViolationException e) {
        String detail = e.getConstraintViolations().stream()
                .map(this::describe)
                .distinct()
                .collect(Collectors.joining("；"));
        return badRequest(detail);
    }

    /**
     * 请求体/参数不可解析：JSON 语法错误、缺必填 query 参数、类型不匹配。
     * 三类都是调用方的问题，按 400 返回；落到兜底分支会误报 500 并污染错误率告警。
     */
    @ExceptionHandler({
            HttpMessageNotReadableException.class,
            MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class
    })
    public ResponseEntity<Result<Void>> handleUnreadable(Exception e) {
        log.warn("请求参数不可解析：{}", e.getMessage());
        return badRequest("请求参数格式不正确");
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Result<Void> handleUnknown(Exception e) {
        log.error("未处理异常", e);
        return Result.error(ResultCodeEnum.SYSTEM_ERROR);
    }

    private ResponseEntity<Result<Void>> badRequest(String detail) {
        String msg = StrUtil.blankToDefault(detail, ResultCodeEnum.PARAM_ERROR.getMsg());
        if (msg.length() > MSG_MAX_LENGTH) {
            msg = msg.substring(0, MSG_MAX_LENGTH);
        }
        log.warn("参数校验失败：{}", msg);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Result.error(ResultCodeEnum.PARAM_ERROR, msg));
    }

    /** 校验器默认只给属性路径，补上最后一段字段名更易定位（loginRequest.password → password） */
    private String describe(ConstraintViolation<?> violation) {
        String path = violation.getPropertyPath() == null ? "" : violation.getPropertyPath().toString();
        int dot = path.lastIndexOf('.');
        String field = dot >= 0 ? path.substring(dot + 1) : path;
        return StrUtil.isBlank(field) ? violation.getMessage() : field + ": " + violation.getMessage();
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
