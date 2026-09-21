package com.smartore.trade.api;

import com.smartore.common.result.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * 交易域对外契约（AI 域消费：用户画像、成长报告、订单问答）。
 */
@FeignClient(name = "smartore-trade", contextId = "orderClient")
public interface OrderFeignClient {

    /** 用户订单汇总：累计单数、累计消费、最近订单状态（导购用户画像工具） */
    @GetMapping("/internal/order/stats/{userId}")
    Result<OrderStatsVO> statsOfUser(@PathVariable("userId") Integer userId);

    /** 用户最近订单（成长报告素材） */
    @GetMapping("/internal/order/recent/{userId}")
    Result<List<OrderBriefVO>> recentOfUser(@PathVariable("userId") Integer userId,
                                            @RequestParam(value = "limit", defaultValue = "5") Integer limit);

    /** 最近已购商品 ID（去重、新单在前）：smartore-voice 的历史订单画像回流用 */
    @GetMapping("/internal/order/recent-product-ids/{userId}")
    Result<List<Integer>> recentProductIds(@PathVariable("userId") Integer userId,
                                           @RequestParam(value = "limit", defaultValue = "5") Integer limit);

    /** 按订单号查订单（订单问答） */
    @GetMapping("/internal/order/by-no/{orderNo}")
    Result<OrderBriefVO> byOrderNo(@PathVariable("orderNo") String orderNo);

    /** 用户是否已完成购买过指定商品（评价资格校验） */
    @GetMapping("/internal/order/owns-product")
    Result<Boolean> ownsProduct(@RequestParam("userId") Integer userId,
                                @RequestParam("productId") Integer productId);

    /** 评价资格校验（精确版）：订单行属于该用户且行内是指定商品（防冒名/占位评价） */
    @GetMapping("/internal/order/owns-order-item")
    Result<Boolean> ownsOrderItem(@RequestParam("userId") Integer userId,
                                  @RequestParam("orderItemId") Integer orderItemId,
                                  @RequestParam("productId") Integer productId);

    /** 全店订单统计（非取消订单数与销售额，成长报告用） */
    @GetMapping("/internal/order/stats-all")
    Result<OrderGlobalStatsVO> globalStats();

    /** 按订单ID或订单号解析订单（含收货人/时间线，订单问答与工具调试用） */
    @GetMapping("/internal/order/detail")
    Result<OrderBriefVO> resolveOrder(@RequestParam(value = "id", required = false) Integer id,
                                      @RequestParam(value = "orderNo", required = false) String orderNo);
}
