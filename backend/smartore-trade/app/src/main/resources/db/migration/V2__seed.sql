-- 演示种子（与钱包流水/事件账本/镜像三方自洽）
INSERT INTO `shop_order` (`order_no`, `user_id`, `total_amount`, `total_quantity`, `status`, `receiver_name`, `receiver_phone`, `receiver_address`, `pay_time`, `finish_time`, `cancel_time`, `create_time`, `update_time`) VALUES
('OD20260626A0001', 2, 7999.00, 1, 'COMPLETED', '张晓然', '18800009999', '浙江省杭州市西湖区文三路 168 号', '2026-06-26 16:30:00', '2026-06-28 10:00:00', NULL, '2026-06-26 16:30:00', '2026-06-28 10:00:00'),
('OD20260701B0002', 2, 5399.00, 1, 'CANCELLED', '张晓然', '18800009999', '浙江省杭州市西湖区文三路 168 号', '2026-07-01 15:00:00', NULL, '2026-07-02 09:00:00', '2026-07-01 15:00:00', '2026-07-02 09:00:00');

INSERT INTO `shop_order_item` (`order_id`, `product_id`, `product_no`, `product_name`, `cover_image`, `price`, `quantity`, `subtotal_amount`, `create_time`) VALUES
(1, 1, 'SP202606260001', 'Apple MacBook Air 13 英寸 M3 轻薄本', NULL, 7999.00, 1, 7999.00, '2026-06-26 16:30:00'),
(2, 2, 'SP202606260002', '索尼 WH-1000XM5 头戴式降噪耳机', NULL, 5399.00, 1, 5399.00, '2026-07-01 15:00:00');

INSERT INTO `trade_event_ledger` (`event_id`, `event_type`, `order_no`, `user_id`, `payload`, `publish_status`, `publish_attempts`, `create_time`, `publish_time`) VALUES
('ORDER_PAID:OD20260626A0001', 'ORDER_PAID', 'OD20260626A0001', 2, '{"eventId":"ORDER_PAID:OD20260626A0001","eventType":"ORDER_PAID","orderNo":"OD20260626A0001","userId":2,"amount":7999.00}', 'PUBLISHED', 0, '2026-06-26 16:30:00', '2026-06-26 16:30:01'),
('ORDER_PAID:OD20260701B0002', 'ORDER_PAID', 'OD20260701B0002', 2, '{"eventId":"ORDER_PAID:OD20260701B0002","eventType":"ORDER_PAID","orderNo":"OD20260701B0002","userId":2,"amount":5399.00}', 'PUBLISHED', 0, '2026-07-01 15:00:00', '2026-07-01 15:00:01'),
('ORDER_CANCELLED:OD20260701B0002', 'ORDER_CANCELLED', 'OD20260701B0002', 2, '{"eventId":"ORDER_CANCELLED:OD20260701B0002","eventType":"ORDER_CANCELLED","orderNo":"OD20260701B0002","userId":2,"amount":5399.00}', 'PUBLISHED', 0, '2026-07-02 09:00:00', '2026-07-02 09:00:01');
