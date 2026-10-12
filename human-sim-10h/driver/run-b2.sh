#!/usr/bin/env bash
# run-b2.sh — B2 狩猎批启动器（driver-b2.py = 基线 driver + 狩猎三件套，
# 2026-10-09 #535；用法: ./run-b2.sh [hours] [seed]）
set -eu
BASE="$(cd "$(dirname "$0")/.." && pwd)"
HOURS="${1:-10}"
SEED="${2:-}"
mkdir -p "$BASE/logs" "$BASE/evidence" "$BASE/reports"
cd "$BASE"
ARGS=(--hours "$HOURS")
[ -n "$SEED" ] && ARGS+=(--seed "$SEED")
nohup setsid python3 "$BASE/driver/driver-b2.py" "${ARGS[@]}" >> "$BASE/reports/driver-b2.log" 2>&1 < /dev/null &
PID=$!
disown
sleep 3
echo "driver-b2 pid=$PID hours=$HOURS seed=${SEED:-random}"
tail -3 "$BASE/reports/driver-b2.log" 2>/dev/null || true
