package com.mindcart.user.controller;

import com.github.pagehelper.PageInfo;
import com.mindcart.common.context.UserContext;
import com.mindcart.common.exception.CustomException;
import com.mindcart.common.result.Result;
import com.mindcart.common.result.ResultCodeEnum;
import com.mindcart.user.dto.LoginRequest;
import com.mindcart.user.dto.RegisterRequest;
import com.mindcart.user.dto.UpdatePasswordRequest;
import com.mindcart.user.dto.UserSaveRequest;
import com.mindcart.user.dto.UserUpdateRequest;
import com.mindcart.user.entity.User;
import com.mindcart.user.service.UserService;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 账号接口。登录/注册在网关白名单内；管理类操作由网关 RBAC 限制 ADMIN。
 */
@RestController
@RequestMapping("/user")
public class UserController {

    @Resource
    private UserService userService;

    @PostMapping("/add")
    public Result<Void> add(@Valid @RequestBody UserSaveRequest request) {
        userService.add(request.toEntity());
        return Result.success();
    }

    @PutMapping("/update")
    public Result<Void> update(@Valid @RequestBody UserUpdateRequest request) {
        userService.updateById(request.toEntity());
        return Result.success();
    }

    @DeleteMapping("/delete/{id}")
    public Result<Void> delete(@PathVariable Integer id) {
        userService.deleteById(id);
        return Result.success();
    }

    @DeleteMapping("/delete/batch")
    public Result<Void> deleteBatch(@RequestBody List<Integer> ids) {
        userService.deleteBatch(ids);
        return Result.success();
    }

    @GetMapping("/selectById/{id}")
    public Result<User> selectById(@PathVariable Integer id) {
        // 非管理员只能查自己（余额/联系方式属敏感信息）
        if (!UserContext.isAdmin() && !id.equals(UserContext.requireUserId())) {
            throw new CustomException(ResultCodeEnum.FORBIDDEN);
        }
        return Result.success(userService.selectById(id));
    }

    @GetMapping("/selectAll")
    public Result<List<User>> selectAll(User user) {
        return Result.success(userService.selectAll(user));
    }

    @GetMapping("/selectPage")
    public Result<PageInfo<User>> selectPage(User user,
                                             @RequestParam(defaultValue = "1")
                                             @Min(value = 1, message = "页码最小为1") Integer pageNum,
                                             @RequestParam(defaultValue = "10")
                                             @Min(value = 1, message = "每页条数最小为1")
                                             @Max(value = 200, message = "每页条数最大为200") Integer pageSize) {
        return Result.success(userService.selectPage(user, pageNum, pageSize));
    }

    @PostMapping("/login")
    public Result<User> login(@Valid @RequestBody LoginRequest request) {
        return Result.success(userService.login(request.toEntity()));
    }

    @PostMapping("/register")
    public Result<Void> register(@Valid @RequestBody RegisterRequest request) {
        userService.register(request.toEntity());
        return Result.success();
    }

    /** 改密码：以当前登录用户为准（网关已认证），不再信任请求体身份 */
    @PutMapping("/updatePassword")
    public Result<Void> updatePassword(@Valid @RequestBody UpdatePasswordRequest request) {
        userService.updatePassword(request.getPassword(), request.getNewPassword());
        return Result.success();
    }
}
