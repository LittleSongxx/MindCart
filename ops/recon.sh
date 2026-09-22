#!/usr/bin/env bash
# MindCart 四方对账（逐单核对，比总和口径更精确：历史"支付后取消"的订单不破坏基线）
#
# 不变量（全部应为 0）：
#  1 曾支付订单(含已取消) 必有钱包 PAY 流水且金额一致
#  2 已取消订单 必有钱包 REFUND 流水
#  3 曾支付订单 必有已发布 ORDER_PAID 账本事件
#  4 已取消订单 必有已发布 ORDER_CANCELLED 账本事件
#  5 账本已发布事件 必能反查到订单（无孤儿事件）
#  6 ai 镜像事件集合 == 账本已发布事件集合（投递零丢失零伪造）
#  7 PAY_FAILED 订单不得存在 PAY 流水（判失败竞态/资错——出现即触发恢复任务自动反向退款）
#  8 账本不得有 FAILED 事件（Outbox 重试耗尽 = Rabbit 长时间不可用，需人工处置）
#  9 voice 归因镜像（PG voice_order_event）：SUCCEEDED 的 CREATE 必须能反查到 trade 订单；
#    DISPATCHED 超 2 小时未收口 = 前端代执行未回传（WS 断/页面被关），需关注
#
# 用法：ops/recon.sh（环境变量见下方默认值；可设 ALERTMANAGER_URL 触发告警）
set -euo pipefail

MYSQL_HOST=${MINDCART_MYSQL_HOST:-127.0.0.1}
MYSQL_PORT=${MINDCART_MYSQL_PORT:-3306}
MYSQL_USER=${MINDCART_MYSQL_USER:-root}
MYSQL_PASS=${MINDCART_MYSQL_PASSWORD:?set MINDCART_MYSQL_PASSWORD}
PG_HOST=${MINDCART_POSTGRES_HOST:-127.0.0.1}
PG_PORT=${MINDCART_POSTGRES_PORT:-5432}
PG_USER=${MINDCART_POSTGRES_USER:-mindcart}
PG_DB=${MINDCART_POSTGRES_DB:-mindcart_voice}
PG_PASS=${MINDCART_POSTGRES_PASSWORD:-}
ALERTMANAGER_URL=${MINDCART_ALERTMANAGER_URL:-}

# 密码经 MYSQL_PWD 环境变量传递（-p 命令行会进 ps）；连接错误必须可见，
# 否则"连接失败"与"对账不平"都是静默 exit 1，cron/CI 无法区分。
# 宿主机没装 mysql 客户端时回退到 mall-mysql 容器内执行（本地 dev 常态）
if command -v mysql >/dev/null 2>&1; then
  query() {
    MYSQL_PWD="$MYSQL_PASS" mysql -h"$MYSQL_HOST" -P"$MYSQL_PORT" -u"$MYSQL_USER" -N -B -e "$1"
  }
else
  query() {
    docker exec -e MYSQL_PWD="$MYSQL_PASS" mall-mysql mysql -u"$MYSQL_USER" -N -B -e "$1"
  }
fi
query "SELECT 1" >/dev/null 2>&1 || { echo "verdict=MYSQL_UNREACHABLE（检查 MINDCART_MYSQL_* 变量与 mall-mysql 容器）"; exit 2; }

# 一个 MySQL 实例承载全部 schema，可跨 schema JOIN
EVER_PAID="status IN ('PAID','SHIPPED','COMPLETED','CANCELLING','CANCELLED')"

c1=$(query "
  SELECT COUNT(*) FROM mindcart_trade.shop_order o
  LEFT JOIN mindcart_user.wallet_record w
    ON w.business_no = o.order_no AND w.type='PAY' AND ROUND(-w.amount*100) = ROUND(o.total_amount*100)
  WHERE o.$EVER_PAID AND w.id IS NULL")

c2=$(query "
  SELECT COUNT(*) FROM mindcart_trade.shop_order o
  LEFT JOIN mindcart_user.wallet_record w
    ON w.business_no = o.order_no AND w.type='REFUND' AND ROUND(w.amount*100) = ROUND(o.total_amount*100)
  WHERE o.status IN ('CANCELLED') AND w.id IS NULL")

c3=$(query "
  SELECT COUNT(*) FROM mindcart_trade.shop_order o
  LEFT JOIN mindcart_trade.trade_event_ledger l
    ON l.event_id = CONCAT('ORDER_PAID:', o.order_no) AND l.publish_status='PUBLISHED'
  WHERE o.$EVER_PAID AND l.id IS NULL")

c4=$(query "
  SELECT COUNT(*) FROM mindcart_trade.shop_order o
  LEFT JOIN mindcart_trade.trade_event_ledger l
    ON l.event_id = CONCAT('ORDER_CANCELLED:', o.order_no) AND l.publish_status='PUBLISHED'
  WHERE o.status='CANCELLED' AND l.id IS NULL")

c5=$(query "
  SELECT COUNT(*) FROM mindcart_trade.trade_event_ledger l
  LEFT JOIN mindcart_trade.shop_order o ON o.order_no = l.order_no
  WHERE l.publish_status='PUBLISHED' AND o.id IS NULL")

c6=$(query "
  SELECT (SELECT COUNT(*) FROM mindcart_trade.trade_event_ledger l WHERE l.publish_status='PUBLISHED'
          AND NOT EXISTS (SELECT 1 FROM mindcart_ai.trade_event_mirror m WHERE m.event_id = l.event_id))
       + (SELECT COUNT(*) FROM mindcart_ai.trade_event_mirror m
          WHERE NOT EXISTS (SELECT 1 FROM mindcart_trade.trade_event_ledger l
                            WHERE l.event_id = m.event_id AND l.publish_status='PUBLISHED'))")

c7=$(query "
  SELECT COUNT(*) FROM mindcart_trade.shop_order o
  WHERE o.status = 'PAY_FAILED' AND EXISTS (
    SELECT 1 FROM mindcart_user.wallet_record w
    WHERE w.business_no = o.order_no AND w.type = 'PAY')")

c8=$(query "
  SELECT COUNT(*) FROM mindcart_trade.trade_event_ledger WHERE publish_status = 'FAILED'")

echo "paid_without_wallet_pay=$c1"
echo "cancelled_without_refund=$c2"
echo "paid_without_ledger_event=$c3"
echo "cancelled_without_cancel_event=$c4"
echo "ledger_orphan_events=$c5"
echo "ledger_mirror_diff=$c6"
echo "pay_failed_with_pay_flow=$c7"
echo "ledger_failed_events=$c8"

# ---- 不变量 9：voice 归因镜像（PostgreSQL mindcart_voice.voice_order_event）----
# psql 不可用时回退到 mall-postgres 容器内执行（与上面 mysql 回退同理）
if command -v psql >/dev/null 2>&1; then
  pg_query() {
    PGPASSWORD="$PG_PASS" psql -h "$PG_HOST" -p "$PG_PORT" -U "$PG_USER" -d "$PG_DB" -t -A -c "$1"
  }
else
  pg_query() {
    docker exec -e PGPASSWORD="$PG_PASS" mall-postgres psql -U "$PG_USER" -d "$PG_DB" -t -A -c "$1"
  }
fi

c9a=0; c9b=0
if pg_query "SELECT 1" >/dev/null 2>&1; then
  # 9a：SUCCEEDED 的 CREATE 必须在 trade 侧能反查到订单（归因镜像不得有孤儿成交）。
  # 跨引擎（PG→MySQL）无法 JOIN，逐单反查；voice 订单量小，代价可接受
  c9a=$(pg_query "SELECT order_no FROM voice_order_event
                  WHERE action='CREATE' AND status='SUCCEEDED' AND order_no IS NOT NULL" |
    { missing=0
      while IFS= read -r no; do
        [ -z "$no" ] && continue
        found=$(query "SELECT COUNT(*) FROM mindcart_trade.shop_order WHERE order_no='$no'")
        [ "$found" = "0" ] && missing=$((missing+1))
      done
      echo "$missing"; })
  # 9b：DISPATCHED 超 2 小时未收口 = 前端代执行未回传（WS 断/页面被关）
  c9b=$(pg_query "SELECT COUNT(*) FROM voice_order_event
                  WHERE status='DISPATCHED' AND created_at < NOW() - INTERVAL '2 hours'" || echo 0)
else
  # PG 不可达不能静默跳过（voice 归因失明与对账不平一样需要可见），但也不掩盖 MySQL 侧结论
  echo "voice_pg_unreachable=1"
  c9a=1
fi
echo "voice_succeeded_orphan=$c9a"
echo "voice_dispatched_stale=$c9b"

total=$((c1+c2+c3+c4+c5+c6+c7+c8+c9a+c9b))
if [ "$total" = "0" ]; then
  echo "verdict=OK"
else
  echo "verdict=MISMATCH (violations=$total)"
  if [ -n "$ALERTMANAGER_URL" ]; then
    curl -s -X POST "$ALERTMANAGER_URL/api/v2/alerts" -H 'Content-Type: application/json' -d "[{
      \"labels\": {\"alertname\": \"MindCartReconMismatch\", \"severity\": \"critical\"},
      \"annotations\": {\"summary\": \"四方对账不平: paid_no_pay=$c1 cancel_no_refund=$c2 paid_no_event=$c3 cancel_no_event=$c4 orphan=$c5 mirror_diff=$c6 pay_failed_with_pay=$c7 ledger_failed=$c8 voice_orphan=$c9a voice_stale=$c9b\"}
    }]" > /dev/null && echo "ALERT-SENT"
  fi
  exit 1
fi
