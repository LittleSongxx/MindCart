#!/usr/bin/env bash
# Smartore 部署网关：SSH 强制命令调用，仅接受四个动词。
# authorized_keys 形如：
#   command="/opt/smartore/ops/ci-gateway.sh",no-port-forwarding,no-X11-forwarding,no-agent-forwarding,no-pty <公钥>
set -euo pipefail
APP_DIR=/opt/smartore
RELEASES=$APP_DIR/releases
BUNDLES=$APP_DIR/bundles
CURRENT=$APP_DIR/current
HEALTH_URL=http://127.0.0.1:9080/actuator/health
read -r verb <<<"${SSH_ORIGINAL_COMMAND:-help}"
case "$verb" in
  upload)
    mkdir -p "$BUNDLES"
    # 限制 200MB：无界 cat 会把服务器磁盘吃满
    head -c 209715200 > "$BUNDLES/incoming.bundle"
    echo "upload: ok ($(stat -c%s "$BUNDLES/incoming.bundle") bytes)"
    ;;
  deploy)
    ts=$(date +%Y%m%d%H%M%S)
    mkdir -p "$RELEASES/$ts"
    git -C "$RELEASES/$ts" clone -q "$BUNDLES/incoming.bundle" repo 2>/dev/null || {
      rm -rf "$RELEASES/$ts"; echo "deploy: clone failed"; exit 1; }
    # 先清空再拷：GNU cp 在目标已存在时会把源目录拷进目标内部（previous/repo/），
    # 从第 3 次部署起 rollback 会拿到最老快照；自拷贝报错被 || true 吞掉的问题一并消除
    if [ -e "$CURRENT" ] && [ -d "$CURRENT" ]; then
      rm -rf "$RELEASES/previous"
      cp -a "$(readlink -f "$CURRENT")" "$RELEASES/previous"
    fi
    "$RELEASES/$ts/repo/ops/deploy.sh" "$RELEASES/$ts/repo" || { echo "deploy: FAILED"; exit 1; }
    ln -sfn "$RELEASES/$ts/repo" "$CURRENT"
    # 只保留最近 5 个时间戳快照（previous 与当前指向的除外）
    ls -1dt "$RELEASES"/[0-9]* 2>/dev/null | tail -n +6 |
      while read -r old; do
        [ "$(readlink -f "$CURRENT")" = "$old/repo" ] && continue
        rm -rf "$old"
      done
    echo "deploy: ok -> $ts"
    ;;
  rollback)
    [ -d "$RELEASES/previous" ] || { echo "rollback: no previous release"; exit 1; }
    "$RELEASES/previous/ops/deploy.sh" "$RELEASES/previous" || { echo "rollback: FAILED"; exit 1; }
    ln -sfn "$RELEASES/previous" "$CURRENT"
    echo "rollback: ok"
    ;;
  status)
    systemctl is-active smartore-apps || true
    curl -s -o /dev/null -w "gateway health: %{http_code}\n" "$HEALTH_URL" || true
    # 语音服务独立于网关链路（叶子服务），网关健康不代表 voice 健康，单独探
    curl -s -o /dev/null -w "voice health: %{http_code}\n" http://127.0.0.1:9105/actuator/health || true
    ;;
  *)
    echo "usage: upload|deploy|status|rollback"
    ;;
esac
