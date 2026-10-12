#!/usr/bin/env bash
# #290：run-as 活库取证拉取（WAL 三件套 + integrity 循环校验）
# 用法：./scripts/pull-app-db.sh <serial> <out-prefix> [max-tries]
# 依据 docs/journal/2026-09-01-disconnect-awareness.md 取证勘误：
#   1) 单拉主 db 报 database disk image is malformed（缺 WAL）；
#   2) 活写中拉取仍可能撕裂——循环拉取直至 integrity ok（上限 max-tries，默认 6）。
set -euo pipefail

SERIAL="${1:?usage: pull-app-db.sh <serial> <out-prefix> [max-tries]}"
PREFIX="${2:?missing out-prefix}"
MAX_TRIES="${3:-6}"
PKG="dev.leonardo.ocbeacon.dev"
# 2026-10-09（B1 批 #288 E2E 定罪）：PATH 裸 adb 在无 adb 环境静默失败 → 0 字节库
# 而 sqlite 对空文件 integrity_check 返回 ok（假阳）。固定 SDK 路径兜底 + 空库门槛。
ADB_BIN="$(command -v adb || true)"
[ -n "$ADB_BIN" ] || ADB_BIN="$HOME/android-sdk/platform-tools/adb"
[ -x "$ADB_BIN" ] || { echo "ERROR: adb not found (PATH nor ~/android-sdk)" >&2; exit 1; }

for i in $(seq 1 "$MAX_TRIES"); do
  rm -f "$PREFIX.db" "$PREFIX.db-wal" "$PREFIX.db-shm"
  "$ADB_BIN" -s "$SERIAL" exec-out "run-as $PKG cat databases/ocbeacon.db" > "$PREFIX.db" 2>/dev/null || true
  "$ADB_BIN" -s "$SERIAL" exec-out "run-as $PKG cat databases/ocbeacon.db-wal" > "$PREFIX.db-wal" 2>/dev/null || true
  "$ADB_BIN" -s "$SERIAL" exec-out "run-as $PKG cat databases/ocbeacon.db-shm" > "$PREFIX.db-shm" 2>/dev/null || true
  if [ ! -s "$PREFIX.db" ]; then
    echo "pull#$i db=0bytes (pull failed) — retrying" >&2
    sleep 2
    continue
  fi
  verdict=$(sqlite3 "$PREFIX.db" 'PRAGMA integrity_check;' 2>/dev/null | head -1 || true)
  echo "pull#$i integrity=[$verdict]" >&2
  if [ "$verdict" = "ok" ]; then
    echo "$PREFIX.db"
    exit 0
  fi
  sleep 2
done
echo "ERROR: no consistent snapshot after $MAX_TRIES pulls" >&2
exit 1
