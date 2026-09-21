#!/usr/bin/env bash
# 停止六服务：SIGTERM 后等待进程真正退出（graceful shutdown 最长 20s），超时 -9 兜底
for s in gateway voice ai trade goods user; do
  pidfile="/opt/smartore/run/$s.pid"
  if [ -f "$pidfile" ] && kill -0 "$(cat "$pidfile")" 2>/dev/null; then
    pid=$(cat "$pidfile")
    kill "$pid" 2>/dev/null || true
    for i in $(seq 1 25); do kill -0 "$pid" 2>/dev/null || break; sleep 1; done
    kill -0 "$pid" 2>/dev/null && kill -9 "$pid" 2>/dev/null || true
  fi
  rm -f "$pidfile"
done
