package com.smartore.user.dto;

import com.smartore.user.entity.User;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * 用户资料更新请求，字段与管理端 User.vue 的表单一致（含可编辑的账号与角色）。
 *
 * 两点刻意保留的边界：
 * 1. 不含 balance —— 余额只走钱包流水，不允许从用户资料接口旁路修改；
 * 2. 不含 token —— 令牌由登录签发，客户端无法注入。
 *
 * 服务层原有的「普通用户只能改自己的资料字段、角色/账号被丢弃」白名单逻辑保持不变：
 * DTO 只声明「允许传什么」，能不能改由服务层身份判定，两层职责不重叠。
 */
@Getter
@Setter
public class UserUpdateRequest {

    @NotNull(message = "用户ID不能为空")
    private Integer id;

    @Size(min = 4, max = 50, message = "账号长度需在4~50之间")
    private String username;

    /** 留空表示不改密码；填了则由服务层 BCrypt 加密后写入 */
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
        user.setId(id);
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
