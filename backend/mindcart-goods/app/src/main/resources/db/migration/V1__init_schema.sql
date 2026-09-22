-- mindcart_goods 库：商品 / 分类 / 品牌 / 参数 / 详情 / 收藏 / 评价 / 售后规则 / 库存权威表
-- 库存不再以 product.stock_quantity 为准：stock 表是权威，商品列仅展示（同事务同步）。

CREATE TABLE `product_category`  (
  `id` int NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '分类名称',
  `icon` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '分类图标',
  `sort` int NULL DEFAULT 1 COMMENT '排序',
  `is_enabled` tinyint NULL DEFAULT 1 COMMENT '是否启用',
  `remark` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '分类说明',
  `create_time` datetime NULL DEFAULT NULL COMMENT '创建时间',
  `update_time` datetime NULL DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 14 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '商品分类表' ROW_FORMAT = DYNAMIC;

CREATE TABLE `product_brand`  (
  `id` int NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '品牌名称',
  `logo` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '品牌Logo',
  `official_site` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '官网地址',
  `sort` int NULL DEFAULT 1 COMMENT '排序',
  `is_enabled` tinyint NULL DEFAULT 1 COMMENT '是否启用',
  `description` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '品牌介绍',
  `create_time` datetime NULL DEFAULT NULL COMMENT '创建时间',
  `update_time` datetime NULL DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 13 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '商品品牌表' ROW_FORMAT = DYNAMIC;

CREATE TABLE `product`  (
  `id` int NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `product_no` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '商品编号',
  `name` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '商品名称',
  `category_id` int NOT NULL COMMENT '分类ID',
  `brand_id` int NOT NULL COMMENT '品牌ID',
  `cover_image` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '商品封面',
  `album_images` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL COMMENT '商品相册',
  `price` decimal(10, 2) NOT NULL COMMENT '售价',
  `original_price` decimal(10, 2) NULL DEFAULT NULL COMMENT '原价',
  `stock_quantity` int NULL DEFAULT 0 COMMENT '库存数量',
  `selling_point` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '商品卖点',
  `tags` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '商品标签',
  `is_recommend` tinyint NULL DEFAULT 0 COMMENT '是否首页推荐',
  `status` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT 'ON_SALE' COMMENT '商品状态',
  `sort` int NULL DEFAULT 1 COMMENT '排序',
  `create_time` datetime NULL DEFAULT NULL COMMENT '创建时间',
  `update_time` datetime NULL DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `idx_product_no`(`product_no` ASC) USING BTREE,
  INDEX `idx_product_category_id`(`category_id` ASC) USING BTREE,
  INDEX `idx_product_brand_id`(`brand_id` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 21 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '商品表' ROW_FORMAT = DYNAMIC;

CREATE TABLE `product_detail`  (
  `id` int NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `product_id` int NOT NULL COMMENT '商品ID',
  `detail_content` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '商品详情',
  `package_info` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL COMMENT '包装清单',
  `after_sale_info` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL COMMENT '售后说明',
  `ai_summary` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL COMMENT 'AI摘要',
  `create_time` datetime NULL DEFAULT NULL COMMENT '创建时间',
  `update_time` datetime NULL DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `idx_product_detail_product_id`(`product_id` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 21 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '商品详情表' ROW_FORMAT = DYNAMIC;

CREATE TABLE `product_param`  (
  `id` int NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `product_id` int NOT NULL COMMENT '商品ID',
  `param_group` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '参数分组',
  `param_name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '参数名称',
  `param_value` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '参数值',
  `sort` int NULL DEFAULT 1 COMMENT '排序',
  `is_core` tinyint NULL DEFAULT 0 COMMENT '是否核心参数',
  `create_time` datetime NULL DEFAULT NULL COMMENT '创建时间',
  `update_time` datetime NULL DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_product_param_product_id`(`product_id` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 81 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '商品参数表' ROW_FORMAT = DYNAMIC;

CREATE TABLE `product_favorite`  (
  `id` int NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `user_id` int NOT NULL COMMENT '用户ID',
  `product_id` int NOT NULL COMMENT '商品ID',
  `create_time` datetime NULL DEFAULT NULL COMMENT '创建时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `idx_favorite_user_product`(`user_id` ASC, `product_id` ASC) USING BTREE,
  INDEX `idx_favorite_product_id`(`product_id` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 6 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '商品收藏表' ROW_FORMAT = DYNAMIC;

CREATE TABLE `product_review`  (
  `id` int NOT NULL AUTO_INCREMENT COMMENT '评价ID',
  `user_id` int NOT NULL COMMENT '用户ID',
  `order_id` int NOT NULL COMMENT '订单ID',
  `order_item_id` int NOT NULL COMMENT '订单明细ID',
  `product_id` int NOT NULL COMMENT '商品ID',
  `rating` int NOT NULL COMMENT '评分',
  `content` varchar(1000) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '评价内容',
  `images` varchar(1000) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '评价图片',
  `audit_status` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT 'APPROVED' COMMENT '审核状态',
  `create_time` datetime NULL DEFAULT NULL COMMENT '创建时间',
  `update_time` datetime NULL DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `idx_review_order_item`(`order_item_id` ASC) USING BTREE,
  INDEX `idx_review_user_id`(`user_id` ASC) USING BTREE,
  INDEX `idx_review_product_id`(`product_id` ASC) USING BTREE,
  INDEX `idx_review_order_id`(`order_id` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 3 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '商品评价表' ROW_FORMAT = DYNAMIC;

CREATE TABLE `after_sale_rule`  (
  `id` int NOT NULL AUTO_INCREMENT COMMENT '规则ID',
  `rule_name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '规则名称',
  `rule_type` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '规则类型',
  `category_id` int NULL DEFAULT NULL COMMENT '适用分类ID',
  `apply_scene` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '适用场景',
  `condition_text` varchar(1000) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '适用条件',
  `process_text` varchar(1000) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '处理流程',
  `time_limit` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '处理时效',
  `contact_channel` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '联系渠道',
  `is_enabled` tinyint NULL DEFAULT 1 COMMENT '是否启用',
  `sort` int NULL DEFAULT 1 COMMENT '排序',
  `create_time` datetime NULL DEFAULT NULL COMMENT '创建时间',
  `update_time` datetime NULL DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_after_sale_rule_type`(`rule_type` ASC) USING BTREE,
  INDEX `idx_after_sale_rule_category`(`category_id` ASC) USING BTREE,
  INDEX `idx_after_sale_rule_enabled`(`is_enabled` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 6 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '售后规则表' ROW_FORMAT = DYNAMIC;


CREATE TABLE `stock` (
  `id` int NOT NULL AUTO_INCREMENT,
  `product_id` int NOT NULL COMMENT '商品ID',
  `available` int NOT NULL DEFAULT 0 COMMENT '可用库存（权威值）',
  `update_time` datetime NULL DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_stock_product` (`product_id`),
  CONSTRAINT `fk_stock_product` FOREIGN KEY (`product_id`) REFERENCES `product` (`id`)
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '库存权威表';

CREATE TABLE `stock_change_record` (
  `id` int NOT NULL AUTO_INCREMENT,
  `biz_no` varchar(100) NOT NULL COMMENT '业务编号（订单号），幂等键',
  `product_id` int NOT NULL,
  `change_type` varchar(20) NOT NULL COMMENT 'DEDUCT/RESTORE',
  `quantity` int NOT NULL,
  `create_time` datetime NULL DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_stock_change` (`biz_no`, `product_id`, `change_type`),
  KEY `idx_stock_change_biz` (`biz_no`)
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '库存变动流水（Saga 留痕，对账数据源）';
