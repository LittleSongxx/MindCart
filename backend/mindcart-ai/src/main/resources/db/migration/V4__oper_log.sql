-- 管理端操作审计（ai 库）：模型 Key、提示词、知识库这些"改了会影响所有回答"的配置。
-- 结构与 goods.oper_log 一致（各服务各自留痕，不跨库写同一张表）。
CREATE TABLE `oper_log` (
  `id` int NOT NULL AUTO_INCREMENT,
  `user_id` int NULL DEFAULT NULL COMMENT '操作人（网关注入的当前用户）',
  `role` varchar(20) NULL DEFAULT NULL COMMENT '操作时的角色快照',
  `module` varchar(50) NOT NULL COMMENT '业务模块',
  `action` varchar(50) NOT NULL COMMENT '动作',
  `request_uri` varchar(255) NULL DEFAULT NULL,
  `http_method` varchar(10) NULL DEFAULT NULL,
  `client_ip` varchar(255) NULL DEFAULT NULL,
  `args` varchar(500) NULL DEFAULT NULL COMMENT '入参摘要（截断；模型 Key 所在接口不记）',
  `result` varchar(10) NOT NULL COMMENT 'SUCCESS/FAIL',
  `error_msg` varchar(300) NULL DEFAULT NULL,
  `cost_ms` bigint NULL DEFAULT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_oper_log_user` (`user_id`),
  KEY `idx_oper_log_create_time` (`create_time`)
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '管理端操作审计日志';
