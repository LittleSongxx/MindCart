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

echo "[deploy] 健康门禁"
ok=0
for i in $(seq 1 60); do
  code=$(curl -s -o /dev/null -w '%{http_code}' "$HEALTH_URL" || echo 000)
  [ "$code" = "200" ] && { ok=1; break; }
  sleep 5
done
[ "$ok" = "1" ] || { echo "健康门禁失败（HTTP $code）"; exit 1; }
echo "[deploy] 网关健康 ✅"
