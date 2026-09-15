-- P0/P1 工程化迁移：
-- 1) agent_run 记录 token 用量（成本归因：一次导购 = N 轮 × tokens）
-- 2) QA 多轮会话：会话表 + 问答归属会话（上下文组装用）

ALTER TABLE `agent_run`
    ADD COLUMN `prompt_tokens` int NULL DEFAULT NULL COMMENT '本次运行累计输入 token',
    ADD COLUMN `completion_tokens` int NULL DEFAULT NULL COMMENT '本次运行累计输出 token';

CREATE TABLE `qa_conversation` (
  `id` int NOT NULL AUTO_INCREMENT,
  `user_id` int NOT NULL COMMENT '所属用户',
  `title` varchar(200) NULL DEFAULT NULL COMMENT '会话标题（取首问截断）',
  `create_time` datetime NULL DEFAULT NULL,
  `update_time` datetime NULL DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_qa_conversation_user` (`user_id`)
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '问答会话（多轮上下文载体）';

ALTER TABLE `shopping_qa`
    ADD COLUMN `conversation_id` int NULL DEFAULT NULL COMMENT '所属会话',
    ADD COLUMN `round_no` int NULL DEFAULT NULL COMMENT '会话内轮次',
    ADD INDEX `idx_qa_conversation_round` (`conversation_id`, `round_no`);
