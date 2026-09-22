package com.mindcart.user.dto;

import com.mindcart.user.entity.User;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * 管理员新建用户请求（网关 admin-rules 已限 ADMIN）。
 *
 * 不含 balance：余额只能通过钱包记录（充值/支付/退款）变动并留下流水，
 * 直接改用户表的余额列会绕开流水与对账。role 保留——管理员建号本就要指定角色。
 */
@Getter
@Setter
public class UserSaveRequest {

    @NotBlank(message = "账号不能为空")
    @Size(min = 4, max = 50, message = "账号长度需在4~50之间")
    private String username;

    /** 留空则由服务层置为默认密码 123456 */
    @Size(min = 4, max = 64, message = "密码长度需在4~64之间")
    private String password;

    @Size(max = 50, message = "姓名长度不能超过50")
    private String name;

    @Size(max = 255, message = "头像地址长度不能超过255")
    private String avatar;

    @Size(max = 20, message = "手机号长度不能超过20")
    private String phone;

    @Email(message = "邮箱格式不正确")
    @Size(max = 100, message = "邮箱长度不能超过100")
    private String email;

    @Pattern(regexp = "ADMIN|USER", message = "角色只能是 ADMIN 或 USER")
    private String role;

    public User toEntity() {
        User user = new User();
        user.setUsername(username);
        user.setPassword(password);
        user.setName(name);
        user.setAvatar(avatar);
        user.setPhone(phone);
        user.setEmail(email);
        user.setRole(role);
        return user;
    }
}
