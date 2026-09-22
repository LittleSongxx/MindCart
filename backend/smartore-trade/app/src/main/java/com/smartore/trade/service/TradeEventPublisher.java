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

    /** 重试次数上限：超过即转 FAILED 等人工介入（不再无限滞留 PENDING） */
    private static final int MAX_ATTEMPTS = 10;
    /** 单轮最多投递条数：限制一次中继对 broker 的瞬时压力 */
    private static final int BATCH_SIZE = 50;
    /**
     * 退避表（秒）：第 n 次失败后隔多久再试，最长一档对超出长度的次数复用。
     * 原先固定每 3 秒重投全部 PENDING，broker 故障时会持续硬拍打——而 broker 恢复
     * 通常是以分钟计的，3 秒一轮除了制造日志噪声并不加快恢复。
     */
    private static final int[] RETRY_BACKOFF_SECONDS = {5, 15, 30, 120, 300, 900, 1800};

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

    /** 中继：每 3 秒捞一次"退避时间已到"的未发布事件（含提交后首投失败的那些） */
    @Scheduled(fixedDelay = 3000)
    public void relayPending() {
        // 重试耗尽的事件转 FAILED（可观察、可用 /shopOrder/replayOutbox 复位重投）
        int failed = ledgerMapper.markFailedExhausted(MAX_ATTEMPTS);
        if (failed > 0) {
            log.error("Outbox 事件重试耗尽转 FAILED，共 {} 条——Rabbit 可能长时间不可用，"
                    + "处置后调 /shopOrder/replayOutbox 复位重投", failed);
        }
        List<TradeEventLedger> pending = ledgerMapper.selectPending(MAX_ATTEMPTS, BATCH_SIZE);
        for (TradeEventLedger event : pending) {
            boolean ok = publishOne(event);
            if (ok) {
                ledgerMapper.markPublished(event.getId());
            } else {
                int attempts = event.getPublishAttempts() == null ? 0 : event.getPublishAttempts();
                int delay = backoffSeconds(attempts + 1);
                ledgerMapper.increaseAttempts(event.getId(), delay);
                log.warn("事件投递失败，第 {} 次重试将在 {}s 后（eventId={}）",
                        attempts + 1, delay, event.getEventId());
            }
        }
    }

    /** 第 attempts 次失败后的等待秒数（attempts 从 1 开始；超出退避表长度复用最后一档） */
    private int backoffSeconds(int attempts) {
        if (attempts <= 0) {
            return RETRY_BACKOFF_SECONDS[0];
        }
        int index = Math.min(attempts, RETRY_BACKOFF_SECONDS.length) - 1;
        return RETRY_BACKOFF_SECONDS[index];
    }

    /**
     * 人工复位重投：把 FAILED 事件置回 PENDING 并清零次数与退避。
     * 返回复位条数，由调用方（管理端接口）回给运维确认。
     */
    public int replayExhausted() {
        int replayed = ledgerMapper.replayExhausted();
        if (replayed > 0) {
            log.warn("人工复位 Outbox 事件 {} 条，中继将重新投递", replayed);
        }
        return replayed;
    }

    /** 账本水位：PENDING/FAILED/PUBLISHED 各多少条 */
    public java.util.Map<String, Integer> stats() {
        java.util.Map<String, Integer> result = new java.util.LinkedHashMap<>();
        for (java.util.Map<String, Object> row : ledgerMapper.countByStatus()) {
            result.put(String.valueOf(row.get("status")), ((Number) row.get("total")).intValue());
        }
        // 没有该状态的行也要显示为 0，否则看板上会出现"缺项"而不是"清零"
        for (String status : List.of("PENDING", "PUBLISHED", "FAILED")) {
            result.putIfAbsent(status, 0);
        }
        return result;
    }

    private void publishPendingQuietly() {
        try {
            relayPending();
        } catch (Exception e) {
            log.warn("提交后首投异常，等待中继重投：{}", e.getMessage());
        }
    }

    /**
     * 投递并等待 broker confirm（publisher-confirm-type: correlated）。
     * 只把 convertAndSend 返回当成功是"假成功"：broker nack（如 quorum 丢多数派）或消息不可路由时
     * 账本会被误标 PUBLISHED 且永不重投——这里同步等 ack/return，异常照常走重试。
     */
    private boolean publishOne(TradeEventLedger event) {
        try {
            org.springframework.amqp.rabbit.connection.CorrelationData correlation =
                    new org.springframework.amqp.rabbit.connection.CorrelationData(event.getEventId());
            rabbitTemplate.convertAndSend(EXCHANGE, ROUTING_EVENT, event.getPayload(), correlation);
            org.springframework.amqp.rabbit.connection.CorrelationData.Confirm confirm =
                    correlation.getFuture().get(5, java.util.concurrent.TimeUnit.SECONDS);
            if (confirm != null && !confirm.isAck()) {
                log.error("broker nack，事件待重投（eventId={}）：{}", event.getEventId(), confirm.getReason());
                return false;
            }
            var returned = correlation.getReturned();
            if (returned != null) {
                log.error("消息不可路由（mandatory return），事件待重投（eventId={}）：{}",
                        event.getEventId(), returned.getReplyText());
                return false;
            }
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
