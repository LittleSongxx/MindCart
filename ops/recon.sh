#!/usr/bin/env bash
# Smartore 四方对账（逐单核对，比总和口径更精确：历史"支付后取消"的订单不破坏基线）
#
# 不变量（全部应为 0）：
#  1 曾支付订单(含已取消) 必有钱包 PAY 流水且金额一致
#  2 已取消订单 必有钱包 REFUND 流水
#  3 曾支付订单 必有已发布 ORDER_PAID 账本事件
#  4 已取消订单 必有已发布 ORDER_CANCELLED 账本事件
#  5 账本已发布事件 必能反查到订单（无孤儿事件）
#  6 ai 镜像事件集合 == 账本已发布事件集合（投递零丢失零伪造）
#
# 用法：ops/recon.sh（环境变量见下方默认值；可设 ALERTMANAGER_URL 触发告警）
set -euo pipefail

MYSQL_HOST=${SMARTORE_MYSQL_HOST:-127.0.0.1}
MYSQL_PORT=${SMARTORE_MYSQL_PORT:-3306}
MYSQL_USER=${SMARTORE_MYSQL_USER:-root}
MYSQL_PASS=${SMARTORE_MYSQL_PASSWORD:?set SMARTORE_MYSQL_PASSWORD}
ALERTMANAGER_URL=${SMARTORE_ALERTMANAGER_URL:-}

query() {
  mysql -h"$MYSQL_HOST" -P"$MYSQL_PORT" -u"$MYSQL_USER" -p"$MYSQL_PASS" -N -B -e "$1" 2>/dev/null
}

# 一个 MySQL 实例承载全部 schema，可跨 schema JOIN
EVER_PAID="status IN ('PAID','SHIPPED','COMPLETED','CANCELLING','CANCELLED')"

c1=$(query "
  SELECT COUNT(*) FROM smartore_trade.shop_order o
  LEFT JOIN smartore_user.wallet_record w
    ON w.business_no = o.order_no AND w.type='PAY' AND ROUND(-w.amount*100) = ROUND(o.total_amount*100)
  WHERE o.$EVER_PAID AND w.id IS NULL")

c2=$(query "
  SELECT COUNT(*) FROM smartore_trade.shop_order o
  LEFT JOIN smartore_user.wallet_record w
    ON w.business_no = o.order_no AND w.type='REFUND' AND ROUND(w.amount*100) = ROUND(o.total_amount*100)
  WHERE o.status IN ('CANCELLED') AND w.id IS NULL")

c3=$(query "
  SELECT COUNT(*) FROM smartore_trade.shop_order o
  LEFT JOIN smartore_trade.trade_event_ledger l
    ON l.event_id = CONCAT('ORDER_PAID:', o.order_no) AND l.publish_status='PUBLISHED'
  WHERE o.$EVER_PAID AND l.id IS NULL")

c4=$(query "
  SELECT COUNT(*) FROM smartore_trade.shop_order o
  LEFT JOIN smartore_trade.trade_event_ledger l
    ON l.event_id = CONCAT('ORDER_CANCELLED:', o.order_no) AND l.publish_status='PUBLISHED'
  WHERE o.status='CANCELLED' AND l.id IS NULL")

c5=$(query "
  SELECT COUNT(*) FROM smartore_trade.trade_event_ledger l
  LEFT JOIN smartore_trade.shop_order o ON o.order_no = l.order_no
  WHERE l.publish_status='PUBLISHED' AND o.id IS NULL")

c6=$(query "
  SELECT (SELECT COUNT(*) FROM smartore_trade.trade_event_ledger l WHERE l.publish_status='PUBLISHED'
          AND NOT EXISTS (SELECT 1 FROM smartore_ai.trade_event_mirror m WHERE m.event_id = l.event_id))
       + (SELECT COUNT(*) FROM smartore_ai.trade_event_mirror m
          WHERE NOT EXISTS (SELECT 1 FROM smartore_trade.trade_event_ledger l
                            WHERE l.event_id = m.event_id AND l.publish_status='PUBLISHED'))")

echo "paid_without_wallet_pay=$c1"
echo "cancelled_without_refund=$c2"
echo "paid_without_ledger_event=$c3"
echo "cancelled_without_cancel_event=$c4"
echo "ledger_orphan_events=$c5"
echo "ledger_mirror_diff=$c6"

total=$((c1+c2+c3+c4+c5+c6))
if [ "$total" = "0" ]; then
  echo "verdict=OK"
else
  echo "verdict=MISMATCH (violations=$total)"
  if [ -n "$ALERTMANAGER_URL" ]; then
    curl -s -X POST "$ALERTMANAGER_URL/api/v2/alerts" -H 'Content-Type: application/json' -d "[{
      \"labels\": {\"alertname\": \"SmartoreReconMismatch\", \"severity\": \"critical\"},
      \"annotations\": {\"summary\": \"四方对账不平: paid_no_pay=$c1 cancel_no_refund=$c2 paid_no_event=$c3 cancel_no_event=$c4 orphan=$c5 mirror_diff=$c6\"}
    }]" > /dev/null && echo "ALERT-SENT"
  fi
  exit 1
fi
