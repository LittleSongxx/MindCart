-- 演示种子（自洽口径）：
-- user2: 充20000 → 付订单A(7999) → 充4999 → 付订单B(5399) → B取消退款5399 ⇒ 余额 17000
-- admin: 开业基线21880 + A收入7999 - B退款出5399 ⇒ 余额 24480
-- 密码 BCrypt：admin/admin、aaa/123
INSERT INTO `user` (`id`, `username`, `password`, `name`, `avatar`, `role`, `phone`, `email`, `balance`) VALUES
(1, 'admin', '$2b$10$wz2qbeaamIyGORyDXqIiXODvsjbIlDdiBFuU0ZQ.GKlixOydw8AK2', '商城管理员', 'http://localhost:9080/files/download/avatar.jpeg', 'ADMIN', '18899990011', 'admin@aishop.com', 24480.00),
(2, 'aaa', '$2b$10$fM.iCyv.dULJ.yzkntDOsezEnt3bKcgqsin9UJJJQ2H6.NyJy/8ea', '张三', 'http://localhost:9080/files/download/1775913605328-8.png', 'USER', '18800009999', 'buyer@aishop.com', 17000.00);

INSERT INTO `user_address` (`id`, `user_id`, `receiver_name`, `receiver_phone`, `province`, `city`, `district`, `detail_address`, `postal_code`, `is_default`, `create_time`, `update_time`) VALUES
(1, 2, '张晓然', '18800009999', '浙江省', '杭州市', '西湖区', '文三路 168 号未来科技大厦 8 层', '310000', 1, '2026-06-26 09:30:00', '2026-06-26 09:30:00'),
(2, 2, '张晓然', '18800009999', '上海市', '上海市', '浦东新区', '张江高科园区商城公寓 3 幢 1202 室', '200120', 0, '2026-06-26 09:35:00', '2026-06-26 09:35:00');

-- 钱包流水（business_no 与订单号一一对应，对账可核对）
INSERT INTO `wallet_record` (`user_id`, `type`, `amount`, `balance_after`, `business_no`, `remark`, `create_time`) VALUES
(1, 'INCOME', 21880.00, 21880.00, 'OPEN-20260601', '开业基线入账', '2026-06-01 10:00:00'),
(2, 'RECHARGE', 20000.00, 20000.00, 'RC20260626093000', '用户钱包充值', '2026-06-26 09:30:00'),
(2, 'PAY', -7999.00, 12001.00, 'OD20260626A0001', '订单支付', '2026-06-26 16:30:00'),
(1, 'INCOME', 7999.00, 29879.00, 'OD20260626A0001', '订单收入', '2026-06-26 16:30:00'),
(2, 'RECHARGE', 4999.00, 17000.00, 'RC20260701110000', '用户钱包充值', '2026-07-01 11:00:00'),
(2, 'PAY', -5399.00, 11601.00, 'OD20260701B0002', '订单支付', '2026-07-01 15:00:00'),
(1, 'INCOME', 5399.00, 35278.00, 'OD20260701B0002', '订单收入', '2026-07-01 15:00:00'),
(2, 'REFUND', 5399.00, 17000.00, 'OD20260701B0002', '订单取消退款', '2026-07-02 09:00:00'),
(1, 'REFUND_OUT', -5399.00, 29879.00, 'OD20260701B0002', '订单取消退款支出', '2026-07-02 09:00:00');
