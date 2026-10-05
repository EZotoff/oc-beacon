#!/usr/bin/env bash
# run.sh — 10h 模拟脱离会话启动器（setsid 存活于本会话之外）
# 用法: ./run.sh [hours]
set -eu
BASE="$(cd "$(dirname "$0")/.." && pwd)"
HOURS="${1:-10}"
mkdir -p "$BASE/logs" "$BASE/evidence" "$BASE/reports"
cd "$BASE"
nohup setsid python3 "$BASE/driver/driver.py" --hours "$HOURS" >> "$BASE/reports/driver.log" 2>&1 < /dev/null &
PID=$!
disown
sleep 3
echo "driver pid=$PID hours=$HOURS"
tail -3 "$BASE/reports/driver.log" 2>/dev/null || true
