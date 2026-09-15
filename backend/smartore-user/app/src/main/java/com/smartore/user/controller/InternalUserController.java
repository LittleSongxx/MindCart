package com.smartore.user.controller;

import com.smartore.common.result.Result;
import com.smartore.user.api.UserVO;
import com.smartore.user.api.WalletOpRequest;
import com.smartore.user.entity.User;
import com.smartore.user.service.UserService;
import com.smartore.user.service.WalletSagaService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;

/**
 * 集群内内部接口：只经 Feign 调用，网关不路由 /internal/**。
 */
@RestController
@RequestMapping("/internal")
public class InternalUserController {

    @Resource
    private UserService userService;
    @Resource
    private WalletSagaService walletSagaService;

    @GetMapping("/user/{id}")
    public Result<UserVO> getById(@PathVariable Integer id) {
        return Result.success(toVO(userService.selectById(id)));
    }

    @PostMapping("/user/batch")
    public Result<java.util.List<UserVO>> getUsers(@RequestBody java.util.List<Integer> ids) {
        if (ids == null || ids.isEmpty()) {
            return Result.success(java.util.List.of());
        }
        return Result.success(ids.stream()
                .map(id -> toVO(userService.selectById(id)))
                .filter(java.util.Objects::nonNull)
                .toList());
    }

    @GetMapping("/user/by-username")
    public Result<UserVO> getByUsername(@RequestParam String username) {
        return Result.success(toVO(userService.selectByUsername(username)));
    }

    @GetMapping("/user/first-admin")
    public Result<UserVO> getFirstAdmin() {
        User admin = userService.selectAll(roleOnly("ADMIN")).stream().findFirst().orElse(null);
        return Result.success(admin == null ? null : toVO(admin));
    }

    @GetMapping("/wallet/status")
    public Result<com.smartore.user.api.WalletStatusVO> walletStatus(@RequestParam String orderNo) {
        return Result.success(walletSagaService.statusOf(orderNo));
    }

    @PostMapping("/wallet/pay")
    public Result<Boolean> payForOrder(@RequestBody WalletOpRequest request) {
        walletSagaService.payForOrder(request);
        return Result.success(true);
    }

    @PostMapping("/wallet/platform-income")
    public Result<Boolean> platformIncome(@RequestBody WalletOpRequest request) {
        walletSagaService.platformIncome(request);
        return Result.success(true);
    }

    @PostMapping("/wallet/refund")
    public Result<Boolean> refundToUser(@RequestBody WalletOpRequest request) {
        walletSagaService.refundToUser(request);
        return Result.success(true);
    }

    @PostMapping("/wallet/platform-refund-out")
    public Result<Boolean> platformRefundOut(@RequestBody WalletOpRequest request) {
        walletSagaService.platformRefundOut(request);
        return Result.success(true);
    }

    private User roleOnly(String role) {
        User condition = new User();
        condition.setRole(role);
        return condition;
    }

    private UserVO toVO(User user) {
        if (user == null) {
            return null;
        }
        UserVO vo = new UserVO();
        vo.setId(user.getId());
        vo.setUsername(user.getUsername());
        vo.setName(user.getName());
        vo.setAvatar(user.getAvatar());
        vo.setRole(user.getRole());
        vo.setPhone(user.getPhone());
        vo.setEmail(user.getEmail());
        vo.setBalance(user.getBalance());
        return vo;
    }
}
