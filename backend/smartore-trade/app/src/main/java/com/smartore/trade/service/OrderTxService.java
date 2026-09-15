package com.smartore.trade.service;

import com.smartore.trade.api.TradeEventPayload;
import com.smartore.trade.entity.OrderRequestIdempotency;
import com.smartore.trade.entity.ShopOrder;
import com.smartore.trade.entity.ShopOrderItem;
import com.smartore.trade.entity.ShoppingCart;
import com.smartore.trade.mapper.OrderRequestIdempotencyMapper;
import com.smartore.trade.mapper.ShopOrderItemMapper;
import com.smartore.trade.mapper.ShopOrderMapper;
import com.smartore.trade.mapper.ShoppingCartMapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 订单本地事务单元。从 Saga 编排器里独立成 Bean：
 * Spring 声明式事务基于代理，同类内自调用会绕过代理导致事务失效，
 * 编排（OrderSagaService，无事务）与本地事务单元（本类，经代理调用）必须分离。
 */
@Service
public class OrderTxService {

    @Resource
    private ShopOrderMapper shopOrderMapper;
    @Resource
    private ShopOrderItemMapper shopOrderItemMapper;
    @Resource
    private ShoppingCartMapper shoppingCartMapper;
    @Resource
    private OrderRequestIdempotencyMapper idempotencyMapper;
    @Resource
    private TradeEventPublisher eventPublisher;

    /** 本地事务①：订单(PAYING)+明细+清车+幂等占位（含 orderNo 回填） */
    @Transactional
    public void persistOrder(ShopOrder order, List<ShopOrderItem> items, List<ShoppingCart> cartList,
                             String requestId, String requestHash) {
        String now = cn.hutool.core.date.DateUtil.now();
        order.setCreateTime(now);
        order.setUpdateTime(now);
        shopOrderMapper.insert(order);
        for (ShopOrderItem item : items) {
            item.setOrderId(order.getId());
            item.setCreateTime(now);
            shopOrderItemMapper.insert(item);
        }
        shoppingCartMapper.deleteSelectedByIds(order.getUserId(),
                cartList.stream().map(ShoppingCart::getId).toList());

        OrderRequestIdempotency idempotency = new OrderRequestIdempotency();
        idempotency.setRequestId(requestId);
        idempotency.setRequestHash(requestHash);
        idempotency.setOrderNo(order.getOrderNo());
        idempotency.setStatus("PROCESSING");
        if (idempotencyMapper.insertIgnore(idempotency) == 0) {
            // 并发同 requestId：本事务回滚，重放方命中已存在记录的分支
            throw new com.smartore.common.exception.CustomException(
                    com.smartore.common.result.ResultCodeEnum.CONFLICT);
        }
    }

    /** 成功收口本地事务②：markPaid + Outbox 追加 + 幂等绑定（三者原子） */
    @Transactional
    public void finishPay(ShopOrder order, List<ShopOrderItem> items) {
        int rows = shopOrderMapper.markPaid(order.getId());
        if (rows == 0) {
            throw new IllegalStateException("订单状态已非 PAYING，无法标记支付成功：" + order.getOrderNo());
        }
        eventPublisher.append(TradeEventPayload.TYPE_ORDER_PAID, order, items);
        OrderRequestIdempotency idempotency = idempotencyMapper.selectByOrderNo(order.getOrderNo());
        if (idempotency != null) {
            idempotencyMapper.bindOrder(idempotency.getRequestId(), order.getId(), "SUCCESS");
        }
    }

    /** 取消收口：finishCancel + Outbox 追加（原子）；已收口则幂等返回 */
    @Transactional
    public void finishCancel(ShopOrder order, List<ShopOrderItem> items) {
        int rows = shopOrderMapper.finishCancel(order.getId());
        if (rows == 0) {
            return;
        }
        eventPublisher.append(TradeEventPayload.TYPE_ORDER_CANCELLED, order, items);
    }

    /** 失败收口：markPayFailed + 幂等记录置 FAILED */
    @Transactional
    public void finishPayFailed(ShopOrder order) {
        shopOrderMapper.markPayFailed(order.getId());
        OrderRequestIdempotency idempotency = idempotencyMapper.selectByOrderNo(order.getOrderNo());
        if (idempotency != null) {
            idempotencyMapper.updateStatus(idempotency.getRequestId(), "FAILED");
        }
    }
}
