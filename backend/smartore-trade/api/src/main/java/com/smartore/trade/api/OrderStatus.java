package com.smartore.trade.api;

import java.util.Set;

/**
 * 订单状态机（单一事实来源，所有状态转移都在这里定义）：
 *
 *   PAYING ──成功──▶ PAID ──发货──▶ SHIPPED ──确认收货──▶ COMPLETED
 *     │               │
 *   失败            取消(先占位)
 *     ▼               ▼
 *  PAY_FAILED      CANCELLING ──补偿完成──▶ CANCELLED
 *
 * 转移一律用条件 UPDATE（where status = 期望前态）落库，影响行数为 0 即并发冲突，
 * 杜绝单体版"先查后改"造成的双退款/双发货。
 */
public enum OrderStatus {

    PAYING("支付编排中"),
    PAID("已支付"),
    SHIPPED("已发货"),
    COMPLETED("已完成"),
    CANCELLING("取消补偿中"),
    CANCELLED("已取消"),
    PAY_FAILED("支付失败");

    private final String label;

    OrderStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    public boolean canTransitionTo(OrderStatus target) {
        return switch (this) {
            case PAYING -> target == PAID || target == PAY_FAILED;
            case PAID -> target == SHIPPED || target == CANCELLING;
            case SHIPPED -> target == COMPLETED;
            case CANCELLING -> target == CANCELLED;
            default -> false;
        };
    }

    /** 对用户可见的"进行中"订单（含取消补偿中的过渡态） */
    public static Set<OrderStatus> ACTIVE_STATUSES = Set.of(PAYING, PAID, SHIPPED, CANCELLING);

    public static OrderStatus of(String value) {
        for (OrderStatus status : values()) {
            if (status.name().equals(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("未知订单状态：" + value);
    }
}
