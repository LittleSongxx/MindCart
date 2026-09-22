package com.mindcart.user.dto;

import com.mindcart.user.entity.User;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * 登录请求。
 * 密码不做长度下限校验：登录只做「是否匹配」判断，对历史弱口令也应当能登录成功；
 * 长度策略属于注册与改密环节的事。上限仍然要有，避免超长入参打到 BCrypt。
 */
@Getter
@Setter
public class LoginRequest {

    @NotBlank(message = "账号不能为空")
    @Size(max = 50, message = "账号长度不能超过50")
    private String username;

    @NotBlank(message = "密码不能为空")
    @Size(max = 64, message = "密码长度不能超过64")
    private String password;

    public User toEntity() {
        User user = new User();
        user.setUsername(username);
        user.setPassword(password);
        return user;
    }
}
