#!/usr/bin/env bash
# 顺序拉起五服务（网关最后起，等业务服务注册）；pid 文件由脚本维护
set -euo pipefail
BASE=$(cd "$(dirname "$0")/../../.." && pwd)/backend
RUN=/opt/smartore/run
mkdir -p "$RUN" /opt/smartore/logs
start() {
  local name=$1 jar=$2
  [ -f "$RUN/$name.pid" ] && kill "$(cat "$RUN/$name.pid")" 2>/dev/null || true
  local jar_path
  jar_path=$(ls "$BASE/$jar"/target/*-SNAPSHOT.jar 2>/dev/null | head -1)
  [ -n "$jar_path" ] || { echo "$name jar 缺失"; exit 1; }
  nohup java ${SMARTORE_JVM_OPTS:--Xms256m -Xmx512m} -jar "$jar_path" > "/opt/smartore/logs/$name.log" 2>&1 &
  echo $! > "$RUN/$name.pid"
  echo "$name pid=$(cat "$RUN/$name.pid")"
}
start user  smartore-user/app
start goods smartore-goods/app
start trade smartore-trade/app
start ai    smartore-ai
sleep 20
start gateway smartore-gateway
