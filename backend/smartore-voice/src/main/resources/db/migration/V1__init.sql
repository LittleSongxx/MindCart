-- ============================================================================
-- smartore-voice 数据库 Schema（PostgreSQL 16 + pgvector）
-- 来源：裁剪自 jc-voice-shopping V1，只保留语音服务自有的四类数据：
--   1. voice_product  —— Smartore 商品目录的同步副本 + 向量（事实以 goods 服务为准）
--   2. session / session_message / session_state —— 语音会话
--   3. user_profile_dynamic —— 行为累加的动态画像
-- 不建：商家/用户/订单/FAQ（用户与订单事实在 Smartore MySQL，经 /internal/** 读取）
-- ============================================================================

CREATE EXTENSION IF NOT EXISTS pg_trgm;         -- LIKE 加速（向量检索故障时的降级链路用）
CREATE EXTENSION IF NOT EXISTS vector;          -- pgvector：向量检索


-- ============================================================================
-- 一、商品同步目录（Smartore goods 服务的只读副本 + embedding，由 CatalogSyncService 维护）
-- ============================================================================

CREATE TABLE voice_product (
                         id              BIGINT        PRIMARY KEY,   -- 与 Smartore product.id 一致（同步写入，不自增）
                         name            VARCHAR(255)  NOT NULL,
                         category_l1     VARCHAR(32)   NOT NULL,
                         category_l2     VARCHAR(64)   NOT NULL,
                         brand           VARCHAR(64),
                         price           NUMERIC(10,2) NOT NULL,       -- 同步时刻快照；回答前经 /internal/product/batch 复核
                         original_price  NUMERIC(10,2),

    -- 灵活属性（品类各自扩展）
                         attributes      JSONB         NOT NULL DEFAULT '{}',

    -- 文本，用于向量化
                         description     TEXT,
                         selling_points  TEXT,

    -- 运营
                         status          VARCHAR(16)   NOT NULL DEFAULT 'ON_SALE',
                         is_new_arrival  BOOLEAN       NOT NULL DEFAULT FALSE,

    -- 向量（pgvector）：基于"名称 + 品牌 + 品类 + 卖点 + 描述"拼接文本生成，1024 维
                         embedding       VECTOR(1024),
    -- 向量化原文的 SHA-256：同步时只在文本变化才重算 embedding（DashScope 按量计费）
                         embed_hash      VARCHAR(64),

                         synced_at       TIMESTAMP     NOT NULL DEFAULT NOW(),
                         created_at      TIMESTAMP     NOT NULL DEFAULT NOW(),
                         updated_at      TIMESTAMP     NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_voice_product_category  ON voice_product(category_l2, status);
CREATE INDEX idx_voice_product_attr_gin  ON voice_product USING GIN(attributes);
CREATE INDEX idx_voice_product_name_trgm ON voice_product USING GIN(name gin_trgm_ops);
-- 向量近邻索引（HNSW 无需训练、召回率稳；生产 10w+ 再调 m/ef_construction）
CREATE INDEX idx_voice_product_embedding_hnsw
    ON voice_product USING HNSW (embedding vector_cosine_ops)
    WITH (m = 16, ef_construction = 64);


-- ============================================================================
-- 二、动态画像（行为累加；静态画像已从本地移除，基本资料经 /internal/user/{id} 读取）
-- ============================================================================

CREATE TABLE user_profile_dynamic (
                                      user_id             BIGINT       PRIMARY KEY,
                                      category_affinity   JSONB        NOT NULL DEFAULT '{}',
                                      brand_affinity      JSONB        NOT NULL DEFAULT '{}',
                                      recent_viewed       BIGINT[]     NOT NULL DEFAULT '{}',
                                      recent_purchased    BIGINT[]     NOT NULL DEFAULT '{}',
                                      price_sensitivity   NUMERIC(3,2),
                                      avg_order_amount    NUMERIC(10,2),
                                      updated_at          TIMESTAMP    NOT NULL DEFAULT NOW()
);


-- ============================================================================
-- 三、语音会话
-- ============================================================================

CREATE TABLE session (
                         id                  VARCHAR(64)   PRIMARY KEY,             -- UUID
                         user_id             BIGINT        NOT NULL,                -- Smartore user.id
                         merchant_id         BIGINT,                                 -- 预留（多商户扩展），当前恒 NULL
                         started_at          TIMESTAMP     NOT NULL DEFAULT NOW(),
                         ended_at            TIMESTAMP,
                         channel             VARCHAR(16)   NOT NULL,                 -- HOME_ENTRY / PRODUCT_PAGE / SEARCH_FALLBACK
                         bound_product_id    BIGINT,
                         locale              VARCHAR(16)   DEFAULT 'zh_cn',
                         outcome             VARCHAR(16),                            -- ORDERED / ABANDONED / FOLLOWUP
                         total_tokens        INT           NOT NULL DEFAULT 0
);
CREATE INDEX idx_session_user ON session(user_id, started_at DESC);

CREATE TABLE session_message (
                                 id                  BIGSERIAL     PRIMARY KEY,
                                 session_id          VARCHAR(64)   NOT NULL REFERENCES session(id),
                                 turn                INT           NOT NULL,
                                 role                VARCHAR(16)   NOT NULL,                 -- USER / ASSISTANT / SYSTEM
                                 agent_name          VARCHAR(32),
                                 content_text        TEXT,
                                 content_audio_url   VARCHAR(255),
                                 tokens              INT           NOT NULL DEFAULT 0,
                                 created_at          TIMESTAMP     NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_msg_session ON session_message(session_id, turn);

CREATE TABLE session_state (
                               session_id              VARCHAR(64)  PRIMARY KEY REFERENCES session(id),
                               phase                   VARCHAR(32)  NOT NULL,              -- INTENT / CLARIFY / RECOMMEND / ORDER_CONFIRM / ENDED
                               current_intent          VARCHAR(32),
                               slots                   JSONB        NOT NULL DEFAULT '{}',
                               pending_ask             VARCHAR(255),
                               last_recommendations    BIGINT[],
                               updated_at              TIMESTAMP    NOT NULL DEFAULT NOW()
);
