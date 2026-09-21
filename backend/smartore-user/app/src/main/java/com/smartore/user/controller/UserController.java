package com.smartore.user.controller;

import com.github.pagehelper.PageInfo;
import com.smartore.common.result.Result;
import com.smartore.user.entity.User;
import com.smartore.user.service.UserService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 账号接口。登录/注册在网关白名单内；管理类操作由网关 RBAC 限制 ADMIN。
 */
@RestController
@RequestMapping("/user")
public class UserController {

    @Resource
    private UserService userService;

    @PostMapping("/add")
    public Result<Void> add(@RequestBody User user) {
        userService.add(user);
        return Result.success();
    }

    @PutMapping("/update")
    public Result<Void> update(@RequestBody User user) {
        userService.updateById(user);
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
        if (!com.smartore.common.context.UserContext.isAdmin()
                && !id.equals(com.smartore.common.context.UserContext.requireUserId())) {
            throw new com.smartore.common.exception.CustomException(
                    com.smartore.common.result.ResultCodeEnum.FORBIDDEN);
        }
        return Result.success(userService.selectById(id));
    }

    @GetMapping("/selectAll")
    public Result<List<User>> selectAll(User user) {
        return Result.success(userService.selectAll(user));
    }

    @GetMapping("/selectPage")
    public Result<PageInfo<User>> selectPage(User user,
                                             @RequestParam(defaultValue = "1") Integer pageNum,
                                             @RequestParam(defaultValue = "10") Integer pageSize) {
        return Result.success(userService.selectPage(user, pageNum, pageSize));
    }

    @PostMapping("/login")
    public Result<User> login(@RequestBody User user) {
        return Result.success(userService.login(user));
    }

    @PostMapping("/register")
    public Result<Void> register(@RequestBody User account) {
        User user = new User();
        user.setUsername(account.getUsername());
        user.setPassword(account.getPassword());
        userService.register(user);
        return Result.success();
    }

    /** 改密码：以当前登录用户为准（网关已认证），不再信任请求体身份 */
    @PutMapping("/updatePassword")
    public Result<Void> updatePassword(@RequestBody Map<String, String> body) {
        userService.updatePassword(body.get("password"), body.get("newPassword"));
        return Result.success();
    }
}
