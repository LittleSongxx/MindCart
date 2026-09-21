-- ============================================================================
-- V2：会话归档与订单归因
-- 补足两个缺口：
--   1) 对话正文不落库（session_message 此前建了但无人写入）→ 无法审计/回溯/质检
--   2) 语音订单无归因（业务库里 shop_order 无会话字段）→ 无法统计语音贡献、无法按会话复盘
-- 全部为增量变更，不改动任何既有业务表。
-- ============================================================================

-- ---- 1. 对话流水：补 intent（当轮生效意图）与 latency_ms（编排耗时，供延迟归因）----
ALTER TABLE session_message ADD COLUMN IF NOT EXISTS intent     VARCHAR(32);
ALTER TABLE session_message ADD COLUMN IF NOT EXISTS latency_ms INT;
CREATE INDEX IF NOT EXISTS idx_msg_session_created ON session_message(session_id, id);

-- ---- 2. 会话表：最后活跃时间（ended_at 只记结束，活跃度用于超时清理与运营统计）----
ALTER TABLE session ADD COLUMN IF NOT EXISTS last_active_at TIMESTAMP;

-- ---- 3. 语音订单事件：以 requestId（= 交易侧幂等键）为主线，串起 会话↔订单 ----
-- 事实源仍是 trade（订单本体在 MySQL），本表是语音域的归因镜像，
-- 与 ai 域的 trade_event_mirror 同思路：AI 侧只持有归因所需最小字段。
CREATE TABLE IF NOT EXISTS voice_order_event (
    id           BIGSERIAL     PRIMARY KEY,
    session_id   VARCHAR(64)   NOT NULL,
    user_id      BIGINT        NOT NULL,
    action       VARCHAR(16)   NOT NULL,          -- CREATE / CANCEL
    request_id   VARCHAR(64)   NOT NULL UNIQUE,   -- 派发给前端的幂等键（成交后与 trade 幂等表一致）
    order_no     VARCHAR(64),                     -- 成交后由 order_result 回传
    product_id   BIGINT,
    product_name VARCHAR(255),
    quantity     INT,
    total_amount NUMERIC(10,2),
    status       VARCHAR(16)   NOT NULL,          -- DISPATCHED / SUCCEEDED / FAILED
    fail_reason  VARCHAR(255),
    created_at   TIMESTAMP     NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMP     NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_voice_order_session ON voice_order_event(session_id);
CREATE INDEX IF NOT EXISTS idx_voice_order_user    ON voice_order_event(user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_voice_order_status  ON voice_order_event(status, created_at DESC);
