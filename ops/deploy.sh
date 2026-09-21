#!/usr/bin/env bash
# 服务器侧部署：源码构建 → systemd 重启 → 健康门禁（失败即 exit 1，CI 判红）
set -euo pipefail
REPO_DIR=${1:?repo dir}
ENV_FILE=${SMARTORE_ENV_FILE:-/opt/smartore/run/runtime.env}
HEALTH_URL=${SMARTORE_HEALTH_URL:-http://127.0.0.1:9080/actuator/health}

echo "[deploy] 构建后端与前端"
(cd "$REPO_DIR/backend" && mvn -q -T 1C package -DskipTests)
(cd "$REPO_DIR/web" && npm ci --silent && npm run build)

echo "[deploy] 注入 runtime.env 并重启 smartore-apps"
install -d /opt/smartore/run
[ -f "$ENV_FILE" ] || { echo "缺少 $ENV_FILE"; exit 1; }
systemctl restart smartore-apps

echo "[deploy] 健康门禁（全部 6 个服务，不只网关——否则后端起不来也能过 CI）"
ok=1
for url in http://127.0.0.1:9080/actuator/health http://127.0.0.1:9101/actuator/health \
            http://127.0.0.1:9102/actuator/health http://127.0.0.1:9103/actuator/health \
            http://127.0.0.1:9104/actuator/health http://127.0.0.1:9105/actuator/health; do
  healthy=0
  for i in $(seq 1 60); do
    code=$(curl -s -o /dev/null -w '%{http_code}' "$url" || echo 000)
    [ "$code" = "200" ] && { healthy=1; break; }
    sleep 5
  done
  if [ "$healthy" != "1" ]; then
    echo "健康门禁失败：$url（HTTP $code）"
    ok=0
  fi
done
[ "$ok" = "1" ] || exit 1
echo "[deploy] 六服务全部健康 ✅"
