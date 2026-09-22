package com.mindcart.trade.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OrderStatusTest {

    @Test
    void legalTransitions() {
        assertTrue(OrderStatus.PAYING.canTransitionTo(OrderStatus.PAID));
        assertTrue(OrderStatus.PAYING.canTransitionTo(OrderStatus.PAY_FAILED));
        assertTrue(OrderStatus.PAID.canTransitionTo(OrderStatus.SHIPPED));
        assertTrue(OrderStatus.PAID.canTransitionTo(OrderStatus.CANCELLING));
        assertTrue(OrderStatus.SHIPPED.canTransitionTo(OrderStatus.COMPLETED));
        assertTrue(OrderStatus.CANCELLING.canTransitionTo(OrderStatus.CANCELLED));
    }

    @Test
    void illegalTransitions() {
        // 已支付不能直接跳已完成（必须先发货）；终态不可再转移
        assertFalse(OrderStatus.PAID.canTransitionTo(OrderStatus.COMPLETED));
        assertFalse(OrderStatus.CANCELLED.canTransitionTo(OrderStatus.PAID));
        assertFalse(OrderStatus.COMPLETED.canTransitionTo(OrderStatus.PAID));
        assertFalse(OrderStatus.PAY_FAILED.canTransitionTo(OrderStatus.PAID));
        // 不能从已发货回退到支付中
        assertFalse(OrderStatus.SHIPPED.canTransitionTo(OrderStatus.PAYING));
    }

    @Test
    void parseRejectsUnknown() {
        assertThrows(IllegalArgumentException.class, () -> OrderStatus.of("NOT_A_STATUS"));
        assertEquals(OrderStatus.PAID, OrderStatus.of("PAID"));
    }
}
