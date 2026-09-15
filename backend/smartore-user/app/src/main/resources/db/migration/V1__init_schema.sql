-- smartore_user 库：账号 / 地址 / 钱包
-- 口径变化（相对单体版）：
--   1. password 存 BCrypt 哈希，不再明文；
--   2. wallet_record 增加 (business_no, type) 唯一约束 —— 钱包 Saga 各步骤
--      以订单号为幂等键，重放不产生二次资金变动（ADR-002）。

CREATE TABLE `user` (
  `id` int NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `username` varchar(255) NULL DEFAULT NULL COMMENT '账号',
  `password` varchar(255) NULL DEFAULT NULL COMMENT '密码（BCrypt 哈希）',
  `name` varchar(255) NULL DEFAULT NULL COMMENT '姓名',
  `avatar` varchar(255) NULL DEFAULT NULL COMMENT '头像',
  `role` varchar(255) NULL DEFAULT NULL COMMENT '角色 USER/ADMIN',
  `phone` varchar(255) NULL DEFAULT NULL COMMENT '电话',
  `email` varchar(255) NULL DEFAULT NULL COMMENT '邮箱',
  `balance` decimal(10, 2) NOT NULL DEFAULT 0.00 COMMENT '账户余额',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_username` (`username`)
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '用户表';

CREATE TABLE `user_address` (
  `id` int NOT NULL AUTO_INCREMENT,
  `user_id` int NOT NULL COMMENT '用户ID',
  `receiver_name` varchar(100) NOT NULL COMMENT '收货人',
  `receiver_phone` varchar(30) NOT NULL COMMENT '收货电话',
  `province` varchar(100) NOT NULL,
  `city` varchar(100) NOT NULL,
  `district` varchar(100) NOT NULL,
  `detail_address` varchar(255) NOT NULL,
  `postal_code` varchar(30) NULL DEFAULT NULL,
  `is_default` tinyint NULL DEFAULT 0,
  `create_time` datetime NULL DEFAULT NULL,
  `update_time` datetime NULL DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_user_address_user_id` (`user_id`),
  CONSTRAINT `fk_address_user` FOREIGN KEY (`user_id`) REFERENCES `user` (`id`)
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '用户收货地址';

CREATE TABLE `wallet_record` (
  `id` int NOT NULL AUTO_INCREMENT,
  `user_id` int NOT NULL COMMENT '用户ID',
  `type` varchar(50) NOT NULL COMMENT '流水类型 RECHARGE/PAY/INCOME/REFUND/REFUND_OUT',
  `amount` decimal(10, 2) NOT NULL COMMENT '变动金额（支出为负）',
  `balance_after` decimal(10, 2) NOT NULL DEFAULT 0.00 COMMENT '变动后余额',
  `business_no` varchar(100) NOT NULL COMMENT '业务编号（订单号/充值单号），幂等键的一半',
  `remark` varchar(255) NULL DEFAULT NULL,
  `create_time` datetime NULL DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_wallet_biz_type` (`business_no`, `type`),
  KEY `idx_wallet_record_user_id` (`user_id`),
  CONSTRAINT `fk_wallet_user` FOREIGN KEY (`user_id`) REFERENCES `user` (`id`)
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '钱包流水（Saga 每步留痕，对账数据源）';
