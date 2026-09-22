package com.mindcart.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * 修改密码请求，替代原先的 {@code Map<String,String>}。
 *
 * 字段名沿用前端已发送的 JSON：{@code password} 是原密码（Password.vue 把整个 user 对象
 * 当请求体发过来，原密码写在 password 字段里），{@code newPassword} 是新密码。
 * 改成 DTO 后这两个字段从「字符串键取值」变成「编译期可见的字段」，少写一个键就是 null 而不是静默失败。
 */
@Getter
@Setter
public class UpdatePasswordRequest {

    @NotBlank(message = "原密码不能为空")
    @Size(max = 64, message = "原密码长度不能超过64")
    private String password;

    @NotBlank(message = "新密码不能为空")
    @Size(min = 4, max = 64, message = "新密码长度需在4~64之间")
    private String newPassword;
}
