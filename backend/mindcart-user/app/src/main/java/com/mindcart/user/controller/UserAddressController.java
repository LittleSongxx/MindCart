package com.mindcart.user.controller;

import com.mindcart.common.result.Result;
import com.mindcart.common.validation.Create;
import com.mindcart.common.validation.Update;
import com.mindcart.user.dto.AddressSaveRequest;
import com.mindcart.user.entity.UserAddress;
import com.mindcart.user.service.UserAddressService;
import jakarta.annotation.Resource;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/userAddress")
public class UserAddressController {

    @Resource
    private UserAddressService userAddressService;

    @PostMapping("/add")
    public Result<Void> add(@Validated(Create.class) @RequestBody AddressSaveRequest request) {
        userAddressService.add(request.toEntity());
        return Result.success();
    }

    /**
     * 更新：现有前端提交的是完整表单，因此格式校验按 {Create, Update} 分组照常执行；
     * 只提交部分字段也允许（必填校验只在 Create 分组），归属校验仍在服务层。
     */
    @PutMapping("/update")
    public Result<Void> update(@Validated(Update.class) @RequestBody AddressSaveRequest request) {
        userAddressService.updateById(request.toEntity());
        return Result.success();
    }

    @DeleteMapping("/delete/{id}")
    public Result<Void> delete(@PathVariable Integer id) {
        userAddressService.deleteById(id);
        return Result.success();
    }

    /** 我的地址列表（userId 取当前登录用户） */
    @GetMapping("/selectByUserId/{userId}")
    public Result<List<UserAddress>> selectMine(@PathVariable Integer userId) {
        return Result.success(userAddressService.selectMine());
    }

    @PutMapping("/setDefault/{id}/{userId}")
    public Result<Void> setDefault(@PathVariable Integer id, @PathVariable Integer userId) {
        userAddressService.setDefault(id);
        return Result.success();
    }
}
