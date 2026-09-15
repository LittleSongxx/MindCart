#!/bin/bash
# MySQL 容器首次初始化：创建 4 个服务库与业务账号（按服务分库，对账与故障隔离的边界）
set -e
mysql -uroot -p"${MYSQL_ROOT_PASSWORD}" <<EOF
CREATE DATABASE IF NOT EXISTS smartore_user  DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS smartore_goods DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS smartore_trade DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS smartore_ai    DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER IF NOT EXISTS 'smartore'@'%' IDENTIFIED BY '${SMARTORE_MYSQL_APP_PASSWORD}';
GRANT ALL PRIVILEGES ON smartore_user.* TO 'smartore'@'%';
GRANT ALL PRIVILEGES ON smartore_goods.* TO 'smartore'@'%';
GRANT ALL PRIVILEGES ON smartore_trade.* TO 'smartore'@'%';
GRANT ALL PRIVILEGES ON smartore_ai.* TO 'smartore'@'%';
FLUSH PRIVILEGES;
EOF
echo "smartore databases initialized"
