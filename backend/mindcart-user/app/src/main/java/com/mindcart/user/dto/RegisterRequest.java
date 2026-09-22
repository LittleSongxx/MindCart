package com.mindcart.user.dto;

import com.mindcart.user.entity.User;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * 注册请求。
 * 4 位下限与服务层既有口径一致（历史演示数据用 123）；
 * confirmPassword 由前端校验，这里不接收（收进 DTO 只会让人以为服务端会比对两次输入）。
 */
@Getter
@Setter
public class RegisterRequest {

    @NotBlank(message = "账号不能为空")
    @Size(min = 4, max = 50, message = "账号长度需在4~50之间")
    private String username;

    @NotBlank(message = "密码不能为空")
    @Size(min = 4, max = 64, message = "密码长度需在4~64之间")
    private String password;

    public User toEntity() {
        User user = new User();
        user.setUsername(username);
        user.setPassword(password);
        return user;
    }
}
