#!/usr/bin/env bash
# run-529.sh — #529 专项 soak 脱离启动器（setsid 存活于会话之外）
# 用法: ./run-529.sh <hours>   例: ./run-529.sh 0.15   # ~9 分钟 smoke
set -eu
BASE="$(cd "$(dirname "$0")/.." && pwd)"
HOURS="${1:-5}"
mkdir -p "$BASE/logs529" "$BASE/evidence/529" "$BASE/reports/529"
cd "$BASE"
nohup setsid python3 "$BASE/driver/driver-529.py" --hours "$HOURS" \
  >> "$BASE/reports/529/driver.log" 2>&1 < /dev/null &
PID=$!
disown
sleep 3
echo "driver-529 pid=$PID hours=$HOURS"
tail -3 "$BASE/reports/529/driver.log" 2>/dev/null || true
