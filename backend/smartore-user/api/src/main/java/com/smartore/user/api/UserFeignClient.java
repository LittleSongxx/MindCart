package com.smartore.user.api;

import com.smartore.common.result.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * 用户域对外契约。/internal/** 路径不经网关暴露，仅限集群内 Feign 调用。
 */
@FeignClient(name = "smartore-user", contextId = "userClient")
public interface UserFeignClient {

    @GetMapping("/internal/user/{id}")
    Result<UserVO> getById(@PathVariable("id") Integer id);

    /** 平台账户 = 第一个管理员（收款/退款出款的对手方） */
    @GetMapping("/internal/user/first-admin")
    Result<UserVO> getFirstAdmin();

    /** 批量取用户（展示名回填） */
    @PostMapping("/internal/user/batch")
    Result<java.util.List<UserVO>> getUsers(@RequestBody java.util.List<Integer> ids);

    /** 按用户名解析用户（AI 用户画像工具） */
    @GetMapping("/internal/user/by-username")
    Result<UserVO> getByUsername(@RequestParam("username") String username);

    /** 订单支付：买家扣款（幂等，余额不足抛 BALANCE_NOT_ENOUGH） */
    @PostMapping("/internal/wallet/pay")
    Result<Boolean> payForOrder(@RequestBody WalletOpRequest request);

    /** 订单支付：平台收款（幂等） */
    @PostMapping("/internal/wallet/platform-income")
    Result<Boolean> platformIncome(@RequestBody WalletOpRequest request);

    /** 订单取消：退款给买家（幂等） */
    @PostMapping("/internal/wallet/refund")
    Result<Boolean> refundToUser(@RequestBody WalletOpRequest request);

    /** 订单取消：平台出款（幂等） */
    @PostMapping("/internal/wallet/platform-refund-out")
    Result<Boolean> platformRefundOut(@RequestBody WalletOpRequest request);

    /** 查询该订单钱包各步骤是否已生效（恢复任务/对账判定用） */
    @GetMapping("/internal/wallet/status")
    Result<WalletStatusVO> walletStatus(@RequestParam("orderNo") String orderNo);
}
