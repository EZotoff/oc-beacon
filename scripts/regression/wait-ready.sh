#!/usr/bin/env bash
# wait-ready.sh — 等三面健康检查全绿；DSH 侧顺带把 launch token 落到 runtime/dsh-token
set -euo pipefail
cd "$(dirname "$0")"
for i in $(seq 1 60); do
  V1=$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:14199/global/health || echo 000)
  V2=$(curl -s -o /dev/null -w '%{http_code}' -u opencode:regression-v2-pw http://127.0.0.1:14096/api/health || echo 000)
  DSH=$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:14200/ || echo 000)
  echo "[$i] V1=$V1 V2=$V2 DSH=$DSH"
  [ "$V1" = 200 ] && [ "$V2" = 200 ] && [ "$DSH" != 000 ] && break
  sleep 5
done
# DSH launch token：从容器日志行 dsh web: .../?token= 提取
docker logs ocbeacon-reg-dsh 2>&1 | grep -oE 'token=[A-Za-z0-9_-]+' | tail -1 | cut -d= -f2 > runtime/dsh-token || true
echo "DSH token: $(cat runtime/dsh-token 2>/dev/null | head -c 6)...(len $(wc -c < runtime/dsh-token 2>/dev/null || echo 0))"
