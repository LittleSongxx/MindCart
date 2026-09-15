package com.smartore.trade.service;

import cn.hutool.json.JSONUtil;
import com.smartore.trade.api.TradeEventPayload;
import com.smartore.trade.entity.ShopOrder;
import com.smartore.trade.entity.ShopOrderItem;
import com.smartore.trade.entity.TradeEventLedger;
import com.smartore.trade.mapper.TradeEventLedgerMapper;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

/**
 * 交易事件账本（Transactional Outbox）：
 * 1. append 与订单状态变更在同一个本地事务里写库（event_id 唯一，幂等）；
 * 2. 事务提交后立即尝试投递一次（低延迟路径）；
 * 3. 中继定时扫描 PENDING/失败事件重投（可靠性路径），对账脚本核对账本 vs 各侧事实。
 */
@Service
public class TradeEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(TradeEventPublisher.class);

    public static final String EXCHANGE = "smartore.trade";
    public static final String ROUTING_EVENT = "trade.event";

    @Resource
    private TradeEventLedgerMapper ledgerMapper;
    @Resource
    private RabbitTemplate rabbitTemplate;

    /** 必须在订单状态变更的本地事务内调用，与业务写入原子提交 */
    public void append(String eventType, ShopOrder order, List<ShopOrderItem> items) {
        TradeEventPayload payload = buildPayload(eventType, order, items);
        TradeEventLedger ledger = new TradeEventLedger();
        ledger.setEventId(payload.getEventId());
        ledger.setEventType(eventType);
        ledger.setOrderNo(order.getOrderNo());
        ledger.setUserId(order.getUserId());
        ledger.setPayload(JSONUtil.toJsonStr(payload));
        ledger.setPublishAttempts(0);
        int rows = ledgerMapper.insertIgnore(ledger);
        if (rows == 0) {
            log.info("事件已存在，跳过追加：{}", payload.getEventId());
            return;
        }
        // 提交后立即投递一次；失败也不回滚——交给中继重投
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publishPendingQuietly();
                }
            });
        }
    }

    /** 中继：每 3 秒重投未发布事件（含提交后首投失败的那些） */
    @Scheduled(fixedDelay = 3000)
    public void relayPending() {
        List<TradeEventLedger> pending = ledgerMapper.selectPending();
        for (TradeEventLedger event : pending) {
            boolean ok = publishOne(event);
            if (ok) {
                ledgerMapper.markPublished(event.getId());
            } else {
                ledgerMapper.increaseAttempts(event.getId());
            }
        }
    }

    private void publishPendingQuietly() {
        try {
            relayPending();
        } catch (Exception e) {
            log.warn("提交后首投异常，等待中继重投：{}", e.getMessage());
        }
    }

    private boolean publishOne(TradeEventLedger event) {
        try {
            rabbitTemplate.convertAndSend(EXCHANGE, ROUTING_EVENT, event.getPayload());
            return true;
        } catch (Exception e) {
            log.warn("事件投递失败（eventId={}）：{}", event.getEventId(), e.getMessage());
            return false;
        }
    }

    private TradeEventPayload buildPayload(String eventType, ShopOrder order, List<ShopOrderItem> items) {
        TradeEventPayload payload = new TradeEventPayload();
        payload.setEventId(eventType + ":" + order.getOrderNo());
        payload.setEventType(eventType);
        payload.setOrderNo(order.getOrderNo());
        payload.setUserId(order.getUserId());
        payload.setAmount(order.getTotalAmount());
        payload.setOccurredAt(cn.hutool.core.date.DateUtil.now());
        if (items != null) {
            payload.setItems(items.stream().map(item -> {
                TradeEventPayload.ItemBrief brief = new TradeEventPayload.ItemBrief();
                brief.setProductId(item.getProductId());
                brief.setProductName(item.getProductName());
                brief.setQuantity(item.getQuantity());
                brief.setPrice(item.getPrice());
                return brief;
            }).toList());
        }
        return payload;
    }
}
