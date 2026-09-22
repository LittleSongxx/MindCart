package com.smartore.user.controller;

import com.smartore.common.result.Result;
import com.smartore.user.entity.User;
import com.smartore.user.entity.WalletRechargeRequest;
import com.smartore.user.entity.WalletRecord;
import com.smartore.user.service.WalletRecordService;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/wallet")
public class WalletRecordController {

    @Resource
    private WalletRecordService walletRecordService;

    @PostMapping("/recharge")
    public Result<User> recharge(@Valid @RequestBody WalletRechargeRequest request) {
        return Result.success(walletRecordService.recharge(request));
    }

    /** 我的钱包流水（路径参数保留前端兼容，服务端强制用当前登录用户） */
    @GetMapping("/records/{userId}")
    public Result<List<WalletRecord>> records(@PathVariable Integer userId) {
        return Result.success(walletRecordService.selectOwnRecords());
    }
}
