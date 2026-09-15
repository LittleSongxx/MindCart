-- 演示种子数据（沿用单体版口径；密码替换为 BCrypt 哈希：admin/admin、aaa/123）
INSERT INTO `user` (`id`, `username`, `password`, `name`, `avatar`, `role`, `phone`, `email`, `balance`) VALUES
(1, 'admin', '$2b$10$wz2qbeaamIyGORyDXqIiXODvsjbIlDdiBFuU0ZQ.GKlixOydw8AK2', '商城管理员', 'http://localhost:9080/files/download/avatar.jpeg', 'ADMIN', '18899990011', 'admin@aishop.com', 35278.00),
(2, 'aaa', '$2b$10$fM.iCyv.dULJ.yzkntDOsezEnt3bKcgqsin9UJJJQ2H6.NyJy/8ea', '张三', 'http://localhost:9080/files/download/1775913605328-8.png', 'USER', '18800009999', 'buyer@aishop.com', 11602.00);

INSERT INTO `user_address` (`id`, `user_id`, `receiver_name`, `receiver_phone`, `province`, `city`, `district`, `detail_address`, `postal_code`, `is_default`, `create_time`, `update_time`) VALUES
(1, 2, '张晓然', '18800009999', '浙江省', '杭州市', '西湖区', '文三路 168 号未来科技大厦 8 层', '310000', 1, '2026-06-26 09:30:00', '2026-06-26 09:30:00'),
(2, 2, '张晓然', '18800009999', '上海市', '上海市', '浦东新区', '张江高科园区商城公寓 3 幢 1202 室', '200120', 0, '2026-06-26 09:35:00', '2026-06-26 09:35:00');

INSERT INTO `wallet_record` (`id`, `user_id`, `type`, `amount`, `balance_after`, `business_no`, `remark`, `create_time`) VALUES
(1, 2, 'RECHARGE', 20000.00, 20000.00, 'RC20260626093000100', '商城用户钱包充值', '2026-06-26 09:30:00'),
(2, 1, 'INCOME', 26880.00, 26880.00, 'IN20260626101000100', '商城订单收入汇总', '2026-06-26 10:10:00'),
(3, 2, 'RECHARGE', 5000.00, 25000.00, 'RC20260627110000200', '商城用户钱包充值', '2026-06-27 11:00:00'),
(4, 2, 'PAY', -7999.00, 17001.00, 'OD20260626163000100', '订单支付', '2026-06-26 16:30:00'),
(5, 2, 'PAY', -5399.00, 11602.00, 'OD20260701150000200', '订单支付', '2026-07-01 15:00:00'),
(6, 1, 'INCOME', 13398.00, 40278.00, 'IN20260701150000200', '商城订单收入', '2026-07-01 15:00:00');
