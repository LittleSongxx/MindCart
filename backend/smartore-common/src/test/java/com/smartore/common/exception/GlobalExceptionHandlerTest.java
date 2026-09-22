package com.smartore.common.exception;

import com.smartore.common.result.Result;
import com.smartore.common.result.ResultCodeEnum;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BeanPropertyBindingResult;

import java.lang.reflect.Method;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 校验异常映射的单测。
 *
 * 这些分支的价值在于「加了 @Valid 之后不能把 400 变成 500」：
 * 若处理器漏了某个异常类型，它会落到兜底分支返回 500，监控里的错误率也会被污染。
 * 框架是否会走到这些分支由运行期验证（curl 打真实接口），这里只锁住「映射结果」本身。
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    /** 测试用载体：字段约束覆盖「必填」与「长度」两类 */
    static class Payload {
        @NotBlank(message = "商品名称不能为空")
        private String name;

        @Size(max = 5, message = "商品名称长度不能超过5")
        private String shortName;

        public void setName(String name) {
            this.name = name;
        }

        public String getName() {
            return name;
        }

        public void setShortName(String shortName) {
            this.shortName = shortName;
        }

        public String getShortName() {
            return shortName;
        }
    }

    /** @Valid @RequestBody 校验失败：HTTP 400 + 错误码 4002 + 带字段名的消息 */
    @Test
    void methodArgumentNotValidMapsTo400WithFieldMessage() throws Exception {
        Payload payload = new Payload();
        payload.setShortName("六个字以上");
        BeanPropertyBindingResult binding = new BeanPropertyBindingResult(payload, "payload");
        // 真实场景由校验器填充；这里手工填两条，验证拼装与去重口径
        binding.rejectValue("name", "NotBlank", "商品名称不能为空");
        binding.rejectValue("shortName", "Size", "商品名称长度不能超过5");

        Method method = Payload.class.getDeclaredMethod("getName");
        MethodParameter parameter = new MethodParameter(method, -1);
        ResponseEntity<Result<Void>> response =
                handler.handleMethodArgumentNotValid(new org.springframework.web.bind.MethodArgumentNotValidException(parameter, binding));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        Result<Void> body = response.getBody();
        assertNotNull(body);
        assertEquals(ResultCodeEnum.PARAM_ERROR.getCode(), body.getCode());
        assertTrue(body.getMsg().contains("name: 商品名称不能为空"), "应带字段名：" + body.getMsg());
        assertTrue(body.getMsg().contains("shortName: 商品名称长度不能超过5"), "应带字段名：" + body.getMsg());
        assertTrue(body.getMsg().indexOf("name:") < body.getMsg().indexOf("shortName:"), "字段应保持声明顺序");
    }

    /** @Validated 方法级校验失败：走 ConstraintViolationException 分支，同样 400 + 4002 */
    @Test
    void constraintViolationMapsTo400AndStripsPropertyPath() {
        Payload payload = new Payload();
        payload.setName("有效名称");
        payload.setShortName("超过五个字了");
        Set<ConstraintViolation<Payload>> violations;
        // 用 ParameterMessageInterpolator：common 的测试类路径上没有 EL 实现，
        // 而这里的约束文案都是字面量、不含 {占位符}，不需要 EL 插值
        try (var factory = Validation.byDefaultProvider()
                .configure()
                .messageInterpolator(new org.hibernate.validator.messageinterpolation.ParameterMessageInterpolator())
                .buildValidatorFactory()) {
            Validator validator = factory.getValidator();
            violations = validator.validate(payload);
        }
        assertEquals(1, violations.size(), "只应有 shortName 一条违规");

        ResponseEntity<Result<Void>> response =
                handler.handleConstraintViolation(new ConstraintViolationException(violations));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        Result<Void> body = response.getBody();
        assertNotNull(body);
        assertEquals(ResultCodeEnum.PARAM_ERROR.getCode(), body.getCode());
        // 属性路径是 shortName，describe() 取最后一段，不该出现类名前缀
        assertTrue(body.getMsg().startsWith("shortName: "), "应只保留字段名：" + body.getMsg());
    }

    /** 业务异常的错误码 → HTTP 状态码映射：4xx 开头归 400，其余归 500 */
    @Test
    void customExceptionMapsCodeToHttpStatus() {
        assertEquals(HttpStatus.UNAUTHORIZED,
                handler.handleCustom(new CustomException(ResultCodeEnum.UNAUTHORIZED)).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN,
                handler.handleCustom(new CustomException(ResultCodeEnum.FORBIDDEN)).getStatusCode());
        assertEquals(HttpStatus.CONFLICT,
                handler.handleCustom(new CustomException(ResultCodeEnum.CONFLICT)).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST,
                handler.handleCustom(new CustomException(ResultCodeEnum.PARAM_ERROR)).getStatusCode());
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR,
                handler.handleCustom(new CustomException(ResultCodeEnum.SYSTEM_ERROR)).getStatusCode());
    }
}
