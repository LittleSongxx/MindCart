package com.smartore.trade.internal;

import cn.hutool.core.util.StrUtil;
import com.smartore.common.result.Result;
import com.smartore.trade.api.OrderBriefVO;
import com.smartore.trade.api.OrderGlobalStatsVO;
import com.smartore.trade.api.OrderStatsVO;
import com.smartore.trade.entity.ShopOrder;
import com.smartore.trade.entity.ShopOrderItem;
import com.smartore.trade.mapper.ShopOrderItemMapper;
import com.smartore.trade.mapper.ShopOrderMapper;
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
