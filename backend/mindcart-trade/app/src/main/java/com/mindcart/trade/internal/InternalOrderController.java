package com.mindcart.trade.internal;

import cn.hutool.core.util.StrUtil;
import com.mindcart.common.result.Result;
import com.mindcart.trade.api.OrderBriefVO;
import com.mindcart.trade.api.OrderGlobalStatsVO;
import com.mindcart.trade.api.OrderStatsVO;
import com.mindcart.trade.entity.ShopOrder;
import com.mindcart.trade.entity.ShopOrderItem;
import com.mindcart.trade.mapper.ShopOrderItemMapper;
import com.mindcart.trade.mapper.ShopOrderMapper;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

/**
 * 集群内内部接口（仅 Feign）。统计口径：已付=PAID/SHIPPED/COMPLETED，退款=CANCELLED。
 */
@RestController
@RequestMapping("/internal/order")
public class InternalOrderController {

    @Resource
    private ShopOrderMapper shopOrderMapper;
    @Resource
    private ShopOrderItemMapper shopOrderItemMapper;

    @GetMapping("/stats/{userId}")
    public Result<OrderStatsVO> statsOfUser(@PathVariable Integer userId) {
        OrderStatsVO stats = new OrderStatsVO();
        ShopOrder condition = new ShopOrder();
        condition.setUserId(userId);
        List<ShopOrder> orders = shopOrderMapper.selectAll(condition);
        List<ShopOrder> paid = orders.stream()
                .filter(o -> List.of("PAID", "SHIPPED", "COMPLETED").contains(o.getStatus()))
                .toList();
        stats.setOrderCount(paid.size());
        stats.setPaidAmount(paid.stream().map(ShopOrder::getTotalAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add));
        stats.setRefundedAmount(orders.stream()
                .filter(o -> "CANCELLED".equals(o.getStatus()))
                .map(ShopOrder::getTotalAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add));
        orders.stream()
                .filter(o -> List.of("PAID", "SHIPPED", "COMPLETED").contains(o.getStatus()))
                .findFirst()
                .ifPresentOrElse(latest -> {
                    stats.setLatestOrderStatus(latest.getStatus());
                    stats.setLatestOrderNo(latest.getOrderNo());
                }, () -> {
                    stats.setLatestOrderStatus("无");
                    stats.setLatestOrderNo("");
                });
        return Result.success(stats);
    }

    @GetMapping("/recent/{userId}")
    public Result<List<OrderBriefVO>> recentOfUser(@PathVariable Integer userId,
                                                   @RequestParam(defaultValue = "5") Integer limit) {
        ShopOrder condition = new ShopOrder();
        condition.setUserId(userId);
        return Result.success(shopOrderMapper.selectAll(condition).stream()
                .filter(o -> List.of("PAID", "SHIPPED", "COMPLETED").contains(o.getStatus()))
                .limit(Math.max(1, limit))
                .map(this::toBrief)
                .toList());
    }

    /**
     * 最近已购商品 ID（去重、新单在前）：供 mindcart-voice 的历史订单画像回流
     * （voice 的动态画像此前只从语音会话里学，看不到真实购买）。只读，不暴露金额明细。
     */
    @GetMapping("/recent-product-ids/{userId}")
    public Result<List<Integer>> recentProductIds(@PathVariable Integer userId,
                                                  @RequestParam(defaultValue = "5") Integer limit) {
        ShopOrder condition = new ShopOrder();
        condition.setUserId(userId);
        List<Integer> ids = new java.util.ArrayList<>();
        shopOrderMapper.selectAll(condition).stream()
                .filter(o -> List.of("PAID", "SHIPPED", "COMPLETED").contains(o.getStatus()))
                .limit(Math.max(1, limit))
                .forEach(o -> {
                    for (ShopOrderItem item : shopOrderItemMapper.selectByOrderId(o.getId())) {
                        if (item.getProductId() != null && !ids.contains(item.getProductId())) {
                            ids.add(item.getProductId());
                        }
                    }
                });
        return Result.success(ids);
    }

    @GetMapping("/owns-product")
    public Result<Boolean> ownsProduct(@RequestParam Integer userId, @RequestParam Integer productId) {
        ShopOrder condition = new ShopOrder();
        condition.setUserId(userId);
        boolean owned = shopOrderMapper.selectAll(condition).stream()
                .filter(o -> "COMPLETED".equals(o.getStatus()))
                .anyMatch(o -> shopOrderItemMapper.selectByOrderId(o.getId()).stream()
                        .anyMatch(item -> productId.equals(item.getProductId())));
        return Result.success(owned);
    }

    /**
     * 评价资格校验（精确版）：订单行属于该用户、订单已完成、且行内确实是指定商品。
     * 相比 owns-product 收紧了"订单行归属"——防止拿别人的 orderItemId 冒名/占位评价。
     */
    @GetMapping("/owns-order-item")
    public Result<Boolean> ownsOrderItem(@RequestParam Integer userId,
                                         @RequestParam Integer orderItemId,
                                         @RequestParam Integer productId) {
        ShopOrderItem item = shopOrderItemMapper.selectById(orderItemId);
        if (item == null || !productId.equals(item.getProductId())) {
            return Result.success(false);
        }
        ShopOrder order = shopOrderMapper.selectById(item.getOrderId());
        return Result.success(order != null
                && userId.equals(order.getUserId())
                && "COMPLETED".equals(order.getStatus()));
    }

    @GetMapping("/stats-all")
    public Result<OrderGlobalStatsVO> globalStats() {
        List<ShopOrder> all = shopOrderMapper.selectAll(new ShopOrder());
        List<ShopOrder> valid = all.stream()
                .filter(o -> !"CANCELLED".equals(o.getStatus()) && !"PAY_FAILED".equals(o.getStatus())).toList();
        OrderGlobalStatsVO stats = new OrderGlobalStatsVO();
        stats.setOrderCount(valid.size());
        stats.setSalesAmount(valid.stream().map(ShopOrder::getTotalAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add));
        return Result.success(stats);
    }

    @GetMapping("/detail")
    public Result<OrderBriefVO> resolveOrder(@RequestParam(required = false) Integer id,
                                             @RequestParam(required = false) String orderNo) {
        ShopOrder order = null;
        if (id != null) {
            order = shopOrderMapper.selectById(id);
        } else if (orderNo != null && !orderNo.isBlank()) {
            order = shopOrderMapper.selectByOrderNo(orderNo);
        }
        return Result.success(order == null ? null : toBrief(order));
    }

    @GetMapping("/by-no/{orderNo}")
    public Result<OrderBriefVO> byOrderNo(@PathVariable String orderNo) {
        if (StrUtil.isBlank(orderNo)) {
            return Result.success(null);
        }
        ShopOrder order = shopOrderMapper.selectByOrderNo(orderNo);
        return Result.success(order == null ? null : toBrief(order));
    }

    private OrderBriefVO toBrief(ShopOrder order) {
        OrderBriefVO vo = new OrderBriefVO();
        vo.setId(order.getId());
        vo.setOrderNo(order.getOrderNo());
        vo.setUserId(order.getUserId());
        vo.setTotalAmount(order.getTotalAmount());
        vo.setTotalQuantity(order.getTotalQuantity());
        vo.setStatus(order.getStatus());
        vo.setReceiverName(order.getReceiverName());
        vo.setReceiverPhone(order.getReceiverPhone());
        vo.setReceiverAddress(order.getReceiverAddress());
        vo.setPayTime(order.getPayTime());
        vo.setShipTime(order.getShipTime());
        vo.setFinishTime(order.getFinishTime());
        vo.setCancelTime(order.getCancelTime());
        vo.setCreateTime(order.getCreateTime());
        return vo;
    }
}
