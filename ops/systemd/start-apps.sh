#!/usr/bin/env bash
# 顺序拉起六服务（网关最后起，等业务服务注册）；pid 文件由脚本维护
set -euo pipefail
BASE=$(cd "$(dirname "$0")/../.." && pwd)/backend
RUN=/opt/mindcart/run
mkdir -p "$RUN" /opt/mindcart/logs
start() {
  local name=$1 jar=$2
  if [ -f "$RUN/$name.pid" ] && kill -0 "$(cat "$RUN/$name.pid")" 2>/dev/null; then
    local old=$i
    old=$(cat "$RUN/$name.pid"); kill "$old" 2>/dev/null || true
    for i in $(seq 1 25); do kill -0 "$old" 2>/dev/null || break; sleep 1; done
    kill -0 "$old" 2>/dev/null && kill -9 "$old" 2>/dev/null || true
    sleep 1
  fi
  # 通配未命中时 ls 管道会触发 pipefail 静默杀脚本，改为 for-glob（与 dev.sh 同款修法）
  local jar_path p
  for p in "$BASE/$jar"/target/*-SNAPSHOT.jar; do
    if [ -f "$p" ]; then jar_path=$p; break; fi
  done
  [ -n "${jar_path:-}" ] || { echo "$name jar 缺失"; exit 1; }
  nohup java ${MINDCART_JVM_OPTS:--Xms256m -Xmx512m} -jar "$jar_path" > "/opt/mindcart/logs/$name.log" 2>&1 &
  echo $! > "$RUN/$name.pid"
  echo "$name pid=$(cat "$RUN/$name.pid")"
}
start user  mindcart-user/app
start goods mindcart-goods/app
start trade mindcart-trade/app
start ai    mindcart-ai
start voice mindcart-voice
sleep 20
start gateway mindcart-gateway
