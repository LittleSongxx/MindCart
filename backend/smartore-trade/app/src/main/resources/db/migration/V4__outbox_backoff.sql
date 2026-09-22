-- Outbox 中继的退避重试：原先固定每 3 秒把 PENDING 全部重投一遍，
-- broker 长时间不可用时等于持续硬拍打（每轮都是几十次必然失败的投递），
-- 而退避后失败事件的投递间隔按次数递增，把压力让给真正可能成功的时刻。
ALTER TABLE `trade_event_ledger`
  ADD COLUMN `next_retry_at` datetime NULL DEFAULT NULL
    COMMENT '下次允许重投的时间；NULL 表示立即可投（首次入账/人工复位）',
  ADD KEY `idx_ledger_retry` (`publish_status`, `next_retry_at`);

-- 存量 PENDING 事件视为可立即重投
UPDATE `trade_event_ledger` SET `next_retry_at` = NULL WHERE `publish_status` = 'PENDING';
