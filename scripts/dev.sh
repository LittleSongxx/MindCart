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

# 必需键清单：已存在的 runtime.env 缺哪个就补哪个（值全新生成，不覆盖已有值）
REQUIRED_ENV_KEYS=(
  SMARTORE_MYSQL_ROOT_PASSWORD SMARTORE_MYSQL_PASSWORD
  SMARTORE_REDIS_PASSWORD SMARTORE_RABBIT_PASSWORD
  SMARTORE_INTERNAL_TOKEN SMARTORE_GATEWAY_TOKEN SMARTORE_JWT_SECRET
  SMARTORE_MODEL_CRYPTO_KEY SMARTORE_GRAFANA_PASSWORD
  SMARTORE_POSTGRES_PASSWORD
)

append_missing_env_keys() {
  local missing=0 key
  for key in "${REQUIRED_ENV_KEYS[@]}"; do
    if ! grep -q "^${key}=" "$ENV_FILE"; then
      case "$key" in
        SMARTORE_MODEL_CRYPTO_KEY) val=$(openssl rand -base64 32 | tr -d '\n') ;;
        *) val=$(rand_hex 24) ;;
      esac
      printf '%s=%s\n' "$key" "$val" >> "$ENV_FILE"
      say "runtime.env 缺少 $key，已生成并追加"
      missing=1
    fi
  done
  [ "$missing" = 1 ] && chmod 600 "$ENV_FILE"
  return 0
}

gen_env() {
  if [ -f "$ENV_FILE" ]; then
    say "runtime.env 已存在，保留（不覆盖已有密钥），仅补齐缺失的必需键"
    append_missing_env_keys
    return
  fi
  # umask 077：文件创建即 600，避免"先 644 后 chmod"的全可读窗口
  ( umask 077 && cat > "$ENV_FILE" <<EOF
# Smartore 运行时密钥（本文件被 git 忽略，禁止提交）
SMARTORE_MYSQL_ROOT_PASSWORD=$(rand_hex 12)
# 业务账号唯一密码变量：compose 传给 mysql-init 建号、后端连接、recon 对账共用
# （旧版曾拆成 APP/MYSQL 两个名字互相脱节，实际账号被建成空密码——两个 bug 恰好抵消）
SMARTORE_MYSQL_PASSWORD=$(rand_hex 12)
SMARTORE_MYSQL_USER=smartore
SMARTORE_MYSQL_HOST=127.0.0.1
SMARTORE_MYSQL_PORT=33306
SMARTORE_REDIS_HOST=127.0.0.1
SMARTORE_REDIS_PORT=26379
SMARTORE_REDIS_PASSWORD=$(rand_hex 12)
SMARTORE_RABBIT_HOST=127.0.0.1
SMARTORE_RABBIT_PORT=5774
SMARTORE_RABBIT_USER=smartore
SMARTORE_RABBIT_PASSWORD=$(rand_hex 12)
SMARTORE_RABBIT_VHOST=/
SMARTORE_NACOS_ADDR=127.0.0.1:28848
SMARTORE_NACOS_USERNAME=nacos
SMARTORE_NACOS_PASSWORD=nacos
SMARTORE_NACOS_GROUP=SMARTORE_GROUP
# 双凭证（见 common/UserContextFilter）：集群内 Feign 调用凭证 + 网关转发凭证，
# /internal/** 只认前者，用户流量无论因何种路由缺陷到达内部接口都会被拒
SMARTORE_INTERNAL_TOKEN=$(rand_hex 24)
SMARTORE_GATEWAY_TOKEN=$(rand_hex 24)
SMARTORE_JWT_SECRET=$(rand_hex 32)
SMARTORE_MODEL_CRYPTO_KEY=$(openssl rand -base64 32 | tr -d '\n')
SMARTORE_GRAFANA_PASSWORD=$(rand_hex 12)
SMARTORE_FILE_DIR=$ROOT/run/files
SMARTORE_FILE_BASE_URL=http://localhost:9080
# 模型密钥留空则使用库内配置；从 Smartlect run/model.env 复制可复用已有 Key
SMARTORE_CHAT_API_KEY=
SMARTORE_CHAT_BASE_URL=https://dashscope.aliyuncs.com/compatible-mode/v1
SMARTORE_CHAT_MODEL=qwen-plus
SMARTORE_EMBED_API_KEY=
SMARTORE_EMBED_BASE_URL=https://dashscope.aliyuncs.com/compatible-mode/v1
SMARTORE_EMBED_MODEL=text-embedding-v4
# smartore-voice 的 PostgreSQL/pgvector（compose 里的 mall-postgres）
SMARTORE_POSTGRES_HOST=127.0.0.1
SMARTORE_POSTGRES_PORT=35432
SMARTORE_POSTGRES_DB=smartore_voice
SMARTORE_POSTGRES_USER=smartore
SMARTORE_POSTGRES_PASSWORD=$(rand_hex 12)
EOF
  )
  chmod 600 "$ENV_FILE"
  say "已生成 $ENV_FILE（600 权限）。如需复用模型 Key：把 Smartlect run/model.env 的值填入 SMARTORE_CHAT_API_KEY / SMARTORE_EMBED_API_KEY"
}

infra_up() {
  require_env
  say "启动 mall 中间件（mysql/redis/rabbitmq/nacos/postgres）…"
  docker compose --env-file "$ENV_FILE" -f deploy/compose.yaml up -d
  say "等待健康检查…"
  local i healthy count
  for i in $(seq 1 60); do
    # grep 无匹配时退出码 1 会触发 pipefail 杀脚本（起步阶段全是 starting），加 || true
    healthy=$(docker inspect --format '{{.State.Health.Status}}' mall-mysql mall-redis mall-rabbitmq mall-nacos mall-postgres 2>&1 | sort | tr '\n' ' ')
    count=$( (grep -o healthy <<<"$healthy" || true) | wc -l )
    [ "$count" = 5 ] && { say "中间件全部健康"; heal_mysql_app_user; return; }
    sleep 3
  done
  docker compose --env-file "$ENV_FILE" -f deploy/compose.yaml ps
  die "等待中间件健康超时（当前状态：$healthy）"
}

# 存量数据卷自愈：旧版 init 脚本变量未传入容器，smartore 账号被建成空密码。
# ALTER USER 幂等，每次 infra-up 都跑一遍，保证账号密码与 runtime.env 一致。
heal_mysql_app_user() {
  docker exec mall-mysql mysql -uroot -p"${SMARTORE_MYSQL_ROOT_PASSWORD}" \
    -e "ALTER USER 'smartore'@'%' IDENTIFIED BY '${SMARTORE_MYSQL_PASSWORD}'; FLUSH PRIVILEGES;" \
    >/dev/null 2>&1 || die "MySQL 业务账号密码自愈失败（检查 SMARTORE_MYSQL_* 变量与容器状态）"
  say "MySQL 业务账号密码已对齐 runtime.env"
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
    voice)   jar_path=$(svc_jar smartore-voice) ;;
    *) die "未知服务 $name";;
  esac
  [ -n "$jar_path" ] || die "$name 的 jar 不存在，先 build"
  if [ -f "$pidfile" ] && kill -0 "$(cat "$pidfile")" 2>/dev/null; then
    local old_pid i
    old_pid=$(cat "$pidfile")
    kill "$old_pid" 2>/dev/null || true
    # Spring graceful shutdown 最长 20s：必须等进程真正退出再起，否则端口占用启动失败
    for i in $(seq 1 25); do
      kill -0 "$old_pid" 2>/dev/null || break
      sleep 1
    done
    kill -0 "$old_pid" 2>/dev/null && { kill -9 "$old_pid" 2>/dev/null || true; sleep 1; }
  fi
  rm -f "$pidfile"
  # voice 在本地开发默认开调试端点（/voice/debug/** 仍受网关 ADMIN 规则保护；生产 systemd 不带此 flag）
  local extra_args=""
  [ "$name" = "voice" ] && extra_args="--voice-shopping.debug.enabled=true"
  nohup java -Xms128m -Xmx384m -jar "$jar_path" $extra_args > "logs/$name.log" 2>&1 &
  echo $! > "$pidfile"
  say "$name 已启动 pid=$(cat "$pidfile") → logs/$name.log"
}

up_all() {
  require_env
  build_backend
  for s in gateway user goods trade ai voice; do start_svc "$s"; done
  say "全部服务启动中（约 30-60s），用 status 查看健康"
}

down_all() {
  for s in gateway user goods trade ai voice; do
    [ -f "run/$s.pid" ] && { kill "$(cat run/$s.pid)" 2>/dev/null && say "$s 已停止" || true; rm -f "run/$s.pid"; }
  done
}

status_all() {
  for s in gateway:9080 user:9101 goods:9102 trade:9103 ai:9104 voice:9105; do
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

  # smartore-voice：健康 → 经网关 WS 握手（query token）→ 目录同步状态
  say "冒烟：voice 语音服务"
  code=$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:9105/actuator/health")
  [ "$code" = "200" ] || die "voice 健康检查失败 HTTP $code（voice 未启动不影响其他服务，可 dev.sh 单独拉起）"
  say "voice 健康 OK"
  TOKEN="$token" python3 - <<'PYEOF'
import os, sys, asyncio
import websockets

async def main():
    token = os.environ["TOKEN"]
    try:
        async with websockets.connect(f"ws://127.0.0.1:9080/voice/ws?token={token}") as ws:
            pass  # 握手成功即通过（鉴权由网关完成）
    except Exception as e:
        print(f"voice WS 握手失败: {e}"); sys.exit(1)
    try:
        async with websockets.connect("ws://127.0.0.1:9080/voice/ws"):
            pass
        print("voice WS 无 token 竟握手成功（应 401）"); sys.exit(1)
    except Exception:
        pass
    print("voice WS 握手/鉴权 OK")

asyncio.run(main())
PYEOF
  [ $? -eq 0 ] || die "voice WS 链路验证失败"
  stats=$(curl -s "http://127.0.0.1:9080/voice/admin/catalog-stats" -H "token: $token")
  echo "$stats" | grep -q '"vectorized"' || die "voice 目录统计接口失败：$stats"
  say "voice 目录状态：$stats"
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
