package com.smartore.user.controller;

import com.smartore.common.result.Result;
import com.smartore.user.entity.UserAddress;
import com.smartore.user.service.UserAddressService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/userAddress")
public class UserAddressController {

    @Resource
    private UserAddressService userAddressService;

    @PostMapping("/add")
    public Result<Void> add(@RequestBody UserAddress userAddress) {
        userAddressService.add(userAddress);
        return Result.success();
    }

    @PutMapping("/update")
    public Result<Void> update(@RequestBody UserAddress userAddress) {
        userAddressService.updateById(userAddress);
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
