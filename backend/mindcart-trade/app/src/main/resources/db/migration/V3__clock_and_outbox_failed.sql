-- 时钟统一：停滞判定（selectStale*）用 DB now() 与 update_time 比较，
-- 写入侧也必须由 DB 时钟产生，消除"应用时钟写入、DB 时钟比较"的偏斜误判
-- （偏斜 > STALE_SECONDS 时新订单会被立即当作停滞单处理）。
ALTER TABLE `shop_order`
  MODIFY `create_time` datetime NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  MODIFY `update_time` datetime NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间';

-- Outbox 事件重试耗尽（publish_attempts>=10）后自动标 FAILED。
-- 人工处置 Rabbit 故障后复位重投：
--   UPDATE trade_event_ledger SET publish_status='PENDING', publish_attempts=0 WHERE publish_status='FAILED';
