-- smartore_trade 库：购物车 / 订单 / 订单明细 / 下单幂等 / 事件账本
-- 状态机：PAYING→PAID/ PAY_FAILED；PAID→SHIPPED/CANCELLING；CANCELLING→CANCELLED

CREATE TABLE `shopping_cart`  (
  `id` int NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `user_id` int NOT NULL COMMENT '用户ID',
  `product_id` int NOT NULL COMMENT '商品ID',
  `quantity` int NULL DEFAULT 1 COMMENT '购买数量',
  `selected` tinyint NULL DEFAULT 1 COMMENT '是否选中：0否，1是',
  `create_time` datetime NULL DEFAULT NULL COMMENT '创建时间',
  `update_time` datetime NULL DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `idx_cart_user_product`(`user_id` ASC, `product_id` ASC) USING BTREE,
  INDEX `idx_cart_product_id`(`product_id` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 6 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '购物车表' ROW_FORMAT = DYNAMIC;

CREATE TABLE `shop_order`  (
  `id` int NOT NULL AUTO_INCREMENT COMMENT '订单ID',
  `order_no` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '订单编号',
  `user_id` int NOT NULL COMMENT '用户ID',
  `total_amount` decimal(10, 2) NOT NULL COMMENT '订单总金额',
  `total_quantity` int NOT NULL COMMENT '商品总件数',
  `status` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '订单状态',
  `receiver_name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '收货人',
  `receiver_phone` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '联系电话',
  `receiver_address` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '收货地址',
  `pay_time` datetime NULL DEFAULT NULL COMMENT '支付时间',
  `ship_time` datetime NULL DEFAULT NULL COMMENT '发货时间',
  `finish_time` datetime NULL DEFAULT NULL COMMENT '完成时间',
  `cancel_time` datetime NULL DEFAULT NULL COMMENT '取消时间',
  `create_time` datetime NULL DEFAULT NULL COMMENT '创建时间',
  `update_time` datetime NULL DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `idx_shop_order_no`(`order_no` ASC) USING BTREE,
  INDEX `idx_shop_order_user_id`(`user_id` ASC) USING BTREE,
  INDEX `idx_shop_order_status`(`status` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 5 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '商城订单表' ROW_FORMAT = DYNAMIC;

CREATE TABLE `shop_order_item`  (
  `id` int NOT NULL AUTO_INCREMENT COMMENT '明细ID',
  `order_id` int NOT NULL COMMENT '订单ID',
  `product_id` int NOT NULL COMMENT '商品ID',
  `product_no` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '商品编号',
  `product_name` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '商品名称',
  `cover_image` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '商品封面',
  `price` decimal(10, 2) NOT NULL COMMENT '成交单价',
  `quantity` int NOT NULL COMMENT '购买数量',
  `subtotal_amount` decimal(10, 2) NOT NULL COMMENT '明细小计',
  `create_time` datetime NULL DEFAULT NULL COMMENT '创建时间',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_order_item_order_id`(`order_id` ASC) USING BTREE,
  INDEX `idx_order_item_product_id`(`product_id` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 3 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '商城订单明细表' ROW_FORMAT = DYNAMIC;


CREATE TABLE `order_request_idempotency` (
  `id` int NOT NULL AUTO_INCREMENT,
  `request_id` varchar(64) NOT NULL COMMENT '前端生成的幂等键',
  `request_hash` varchar(64) NOT NULL COMMENT '请求内容指纹',
  `order_no` varchar(100) NULL DEFAULT NULL COMMENT '关联订单号',
  `order_id` int NULL DEFAULT NULL COMMENT '成功后绑定的订单ID',
  `status` varchar(20) NOT NULL COMMENT 'PROCESSING/SUCCESS/FAILED',
  `create_time` datetime NULL DEFAULT NULL,
  `update_time` datetime NULL DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_idem_request` (`request_id`),
  KEY `idx_idem_order_no` (`order_no`)
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '下单幂等（防重复提交/网络重试）';

CREATE TABLE `trade_event_ledger` (
  `id` int NOT NULL AUTO_INCREMENT,
  `event_id` varchar(128) NOT NULL COMMENT 'eventType:orderNo，幂等键',
  `event_type` varchar(50) NOT NULL,
  `order_no` varchar(100) NOT NULL,
  `user_id` int NULL DEFAULT NULL,
  `payload` text NOT NULL COMMENT '事件 JSON（TradeEventPayload）',
  `publish_status` varchar(20) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/PUBLISHED',
  `publish_attempts` int NOT NULL DEFAULT 0,
  `create_time` datetime NULL DEFAULT NULL,
  `publish_time` datetime NULL DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ledger_event` (`event_id`),
  KEY `idx_ledger_status` (`publish_status`)
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '交易事件账本（Outbox，对账数据源）';
