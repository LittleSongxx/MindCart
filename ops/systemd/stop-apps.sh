#!/usr/bin/env bash
for s in gateway ai trade goods user; do
  [ -f "/opt/smartore/run/$s.pid" ] && kill "$(cat /opt/smartore/run/$s.pid)" 2>/dev/null || true
  rm -f "/opt/smartore/run/$s.pid"
done
