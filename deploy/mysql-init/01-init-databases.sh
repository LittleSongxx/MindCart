#!/bin/bash
# MySQL 容器首次初始化：创建 4 个服务库与业务账号（按服务分库，对账与故障隔离的边界）。
# MINDCART_MYSQL_PASSWORD 由 compose 传入容器环境（旧版变量名没传进来，
# 账号被建成空密码，与后端"默认空密码"恰好互相抵消——现统一为单一变量名）。
set -e
mysql -uroot -p"${MYSQL_ROOT_PASSWORD}" <<EOF
CREATE DATABASE IF NOT EXISTS mindcart_user  DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS mindcart_goods DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS mindcart_trade DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS mindcart_ai    DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER IF NOT EXISTS 'mindcart'@'%' IDENTIFIED BY '${MINDCART_MYSQL_PASSWORD}';
ALTER USER 'mindcart'@'%' IDENTIFIED BY '${MINDCART_MYSQL_PASSWORD}';
GRANT ALL PRIVILEGES ON mindcart_user.* TO 'mindcart'@'%';
GRANT ALL PRIVILEGES ON mindcart_goods.* TO 'mindcart'@'%';
GRANT ALL PRIVILEGES ON mindcart_trade.* TO 'mindcart'@'%';
GRANT ALL PRIVILEGES ON mindcart_ai.* TO 'mindcart'@'%';
FLUSH PRIVILEGES;
EOF
echo "mindcart databases initialized"
