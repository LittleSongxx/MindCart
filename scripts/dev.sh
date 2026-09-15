#!/usr/bin/env bash
# Smartore 本地开发/演示全生命周期脚本
# 用法：./scripts/dev.sh bootstrap|infra-up|infra-down|build|up|down|status|check|logs <svc>|test
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT="$(pwd)"
RUN_DIR="$ROOT/run"
ENV_FILE="$RUN_DIR/runtime.env"
mkdir -p "$RUN_DIR" "$ROOT/logs"

say() { printf '\033[1;36m[dev]\033[0m %s\n' "$*"; }
die() { printf '\033[1;31m[dev]\033[0m %s\n' "$*" >&2; exit 1; }

rand_hex() { openssl rand -hex "$1"; }

require_env() {
  [ -f "$ENV_FILE" ] || die "runtime.env 不存在，先执行: ./scripts/dev.sh bootstrap"
  set -a; . "$ENV_FILE"; set +a
}

gen_env() {
  if [ -f "$ENV_FILE" ]; then say "runtime.env 已存在，保留（不覆盖已有密钥）"; return; fi
  cat > "$ENV_FILE" <<EOF
# Smartore 运行时密钥（本文件被 git 忽略，禁止提交）
SMARTORE_MYSQL_ROOT_PASSWORD=$(rand_hex 12)
SMARTORE_MYSQL_APP_PASSWORD=$(rand_hex 12)
SMARTORE_MYSQL_USER=smartore
SMARTORE_MYSQL_HOST=127.0.0.1
SMARTORE_MYSQL_PORT=33306
SMARTORE_REDIS_HOST=127.0.0.1
SMARTORE_REDIS_PORT=26379
SMARTORE_REDIS_PASSWORD=$(rand_hex 12)
SMARTORE_RABBIT_HOST=127.0.0.1
SMARTORE_RABBIT_PORT=5674
SMARTORE_RABBIT_USER=smartore
SMARTORE_RABBIT_PASSWORD=$(rand_hex 12)
SMARTORE_RABBIT_VHOST=/
SMARTORE_NACOS_ADDR=127.0.0.1:28848
SMARTORE_NACOS_USERNAME=nacos
SMARTORE_NACOS_PASSWORD=nacos
SMARTORE_NACOS_GROUP=SMARTORE_GROUP
SMARTORE_INTERNAL_TOKEN=$(rand_hex 24)
SMARTORE_JWT_SECRET=$(rand_hex 32)
SMARTORE_MODEL_CRYPTO_KEY=$(openssl rand -base64 32 | tr -d '\n')
SMARTORE_FILE_DIR=$ROOT/run/files
SMARTORE_FILE_BASE_URL=http://localhost:9080
# 模型密钥留空则使用库内配置；从 Smartlect run/model.env 复制可复用已有 Key
SMARTORE_CHAT_API_KEY=
SMARTORE_CHAT_BASE_URL=https://dashscope.aliyuncs.com/compatible-mode/v1
SMARTORE_CHAT_MODEL=qwen-plus
SMARTORE_EMBED_API_KEY=
SMARTORE_EMBED_BASE_URL=https://dashscope.aliyuncs.com/compatible-mode/v1
SMARTORE_EMBED_MODEL=text-embedding-v4
EOF
  chmod 600 "$ENV_FILE"
  say "已生成 $ENV_FILE（600 权限）。如需复用模型 Key：把 Smartlect run/model.env 的值填入 SMARTORE_CHAT_API_KEY / SMARTORE_EMBED_API_KEY"
}

infra_up() {
  require_env
  say "启动 mall 中间件（mysql/redis/rabbitmq/nacos）…"
  docker compose --env-file "$ENV_FILE" -f deploy/compose.yaml up -d
  say "等待健康检查…"
  for i in $(seq 1 60); do
    healthy=$(docker inspect --format '{{.State.Health.Status}}' mall-mysql mall-redis mall-rabbitmq mall-nacos 2>/dev/null | sort -u | tr '\n' ' ')
    [ "$healthy" = "healthy " ] && { say "中间件全部健康：$healthy"; return; }
    sleep 3
  done
  docker compose --env-file "$ENV_FILE" -f deploy/compose.yaml ps
  die "等待中间件健康超时，看上方状态排查"
}

infra_down() {
  say "停止 mall 中间件（数据卷保留）"
  docker compose --env-file "$ENV_FILE" -f deploy/compose.yaml down 2>/dev/null || docker compose -f deploy/compose.yaml down
}

build_backend() {
  say "构建后端（跳过测试）"
  (cd backend && mvn -q -T 1C package -DskipTests)
}

build_web() {
  say "构建前端"
  (cd web && npm install --silent && npm run build)
}

svc_jar() {
  # 不用 ls+管道（通配未命中非零退出会触发 pipefail 杀脚本）
  local p
  for p in "backend/$1"/target/*-SNAPSHOT.jar "backend/$1"/app/target/*-SNAPSHOT.jar; do
    if [ -f "$p" ]; then echo "$p"; return 0; fi
  done
  return 1
}

start_svc() {
  local name="$1" jar pidfile="run/$1.pid"
  local jar_path
  case "$name" in
    gateway) jar_path=$(svc_jar smartore-gateway);;
    user)    jar_path=$(svc_jar smartore-user) ;;
    goods)   jar_path=$(svc_jar smartore-goods) ;;
    trade)   jar_path=$(svc_jar smartore-trade) ;;
    ai)      jar_path=$(svc_jar smartore-ai) ;;
    *) die "未知服务 $name";;
  esac
  [ -n "$jar_path" ] || die "$name 的 jar 不存在，先 build"
  [ -f "$pidfile" ] && kill "$(cat "$pidfile")" 2>/dev/null || true
  sleep 1
  nohup java -Xms128m -Xmx384m -jar "$jar_path" > "logs/$name.log" 2>&1 &
  echo $! > "$pidfile"
  say "$name 已启动 pid=$(cat "$pidfile") → logs/$name.log"
}

up_all() {
  require_env
  build_backend
  for s in gateway user goods trade ai; do start_svc "$s"; done
  say "全部服务启动中（约 30-60s），用 status 查看健康"
}

down_all() {
  for s in gateway user goods trade ai; do
    [ -f "run/$s.pid" ] && { kill "$(cat run/$s.pid)" 2>/dev/null && say "$s 已停止" || true; rm -f "run/$s.pid"; }
  done
}

status_all() {
  for s in gateway:9080 user:9101 goods:9102 trade:9103 ai:9104; do
    name="${s%%:*}"; port="${s##*:}"
    health=$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:$port/actuator/health" || echo 000)
    pid="(-)"; [ -f "run/$name.pid" ] && pid="($(cat run/$name.pid))"
    printf '%-8s %-6s health=%s %s\n' "$name" "$port" "$health" "$pid"
  done
}

check_smoke() {
  require_env
  say "冒烟：登录 admin → 网关路由 → 商品列表"
  token=$(curl -s -X POST http://127.0.0.1:9080/user/login -H 'Content-Type: application/json' \
    -d '{"username":"admin","password":"admin"}' | python3 -c 'import json,sys; d=json.load(sys.stdin); print(d["data"]["token"] if d.get("data") else "")')
  [ -n "$token" ] || die "登录失败（user 服务/网关是否已 up？）"
  say "登录 OK（token ${#token} 字符）"
  code=$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:9080/product/selectAll" -H "token: $token")
  [ "$code" = "200" ] || die "商品列表路由失败 HTTP $code"
  say "商品列表 OK"
  # RBAC 验证：USER 角色访问管理接口应 403
  user_token=$(curl -s -X POST http://127.0.0.1:9080/user/login -H 'Content-Type: application/json' \
    -d '{"username":"aaa","password":"123"}' | python3 -c 'import json,sys; d=json.load(sys.stdin); print(d["data"]["token"] if d.get("data") else "")')
  code=$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:9080/aiModelConfig/selectPage" -H "token: $user_token")
  [ "$code" = "403" ] || die "RBAC 验证失败（期望 403 实得 $code）"
  say "RBAC OK（USER 访问管理接口被 403）"
  say "冒烟全部通过 ✅"
}

case "${1:-}" in
  bootstrap)  gen_env ;;
  infra-up)   infra_up ;;
  infra-down) infra_down ;;
  build)      build_backend && build_web ;;
  up)         up_all ;;
  down)       down_all ;;
  status)     status_all ;;
  check)      check_smoke ;;
  logs)       [ -n "${2:-}" ] && tail -f "logs/$2.log" || die "用法: dev.sh logs <gateway|user|goods|trade|ai>" ;;
  test)       (cd backend && mvn -q test) ;;
  *) sed -n '2,4p' "$0"; exit 1 ;;
esac
