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
    cat > "$BUNDLES/incoming.bundle"
    echo "upload: ok ($(stat -c%s "$BUNDLES/incoming.bundle") bytes)"
    ;;
  deploy)
    ts=$(date +%Y%m%d%H%M%S)
    mkdir -p "$RELEASES/$ts"
    git -C "$RELEASES/$ts" clone -q "$BUNDLES/incoming.bundle" repo 2>/dev/null || {
      rm -rf "$RELEASES/$ts"; echo "deploy: clone failed"; exit 1; }
    if [ -e "$CURRENT" ] && [ -d "$CURRENT" ]; then
      cp -a "$(readlink -f "$CURRENT")" "$RELEASES/previous" 2>/dev/null || true
    fi
    "$RELEASES/$ts/repo/ops/deploy.sh" "$RELEASES/$ts/repo" || { echo "deploy: FAILED"; exit 1; }
    ln -sfn "$RELEASES/$ts/repo" "$CURRENT"
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
    ;;
  *)
    echo "usage: upload|deploy|status|rollback"
    ;;
esac
