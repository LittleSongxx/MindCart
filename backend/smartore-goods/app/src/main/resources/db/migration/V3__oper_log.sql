-- 管理端操作审计：谁、什么时候、从哪个入口、改了什么、成了没有。
-- 只记管理端高价值写操作（改价/改库存/上下架/审核），不记读接口与高频用户行为。
CREATE TABLE `oper_log` (
  `id` int NOT NULL AUTO_INCREMENT,
  `user_id` int NULL DEFAULT NULL COMMENT '操作人（网关注入的当前用户）',
  `role` varchar(20) NULL DEFAULT NULL COMMENT '操作时的角色快照：角色会变，留痕记当时身份',
  `module` varchar(50) NOT NULL COMMENT '业务模块',
  `action` varchar(50) NOT NULL COMMENT '动作',
  `request_uri` varchar(255) NULL DEFAULT NULL,
  `http_method` varchar(10) NULL DEFAULT NULL,
  `client_ip` varchar(255) NULL DEFAULT NULL,
  `args` varchar(500) NULL DEFAULT NULL COMMENT '入参摘要（截断；敏感接口不记）',
  `result` varchar(10) NOT NULL COMMENT 'SUCCESS/FAIL',
  `error_msg` varchar(300) NULL DEFAULT NULL,
  `cost_ms` bigint NULL DEFAULT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_oper_log_user` (`user_id`),
  KEY `idx_oper_log_create_time` (`create_time`)
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '管理端操作审计日志';
