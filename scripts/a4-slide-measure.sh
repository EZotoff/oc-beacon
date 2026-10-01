#!/bin/bash
# A4 正式滑动 p90 测量（#442）：framestats 时间戳过滤法——手势时间窗切帧，
# 消除呼吸光标 120Hz 帧对长窗百分位的稀释；稳态期（开流 ≥40s）分段配对窗。
# 用法: a4_measure.sh <serial> <cycles> <outfile.raw>
set -u
ADB=/home/leonardo/android-sdk/platform-tools/adb
S=${1:?serial}
N=${2:-8}
OUT=${3:?outfile}

: > "$OUT"

for i in $(seq 1 "$N"); do
  T0=$(date +%s%N)
  $ADB -s "$S" shell input swipe 600 700 600 2100 300
  T1=$(date +%s%N)
  sleep 0.10
  $ADB -s "$S" shell dumpsys gfxinfo dev.leonardo.ocbeacon.dev framestats > /tmp/a4_fs.txt 2>/dev/null
  echo "W $i $T0 $T1" >> "$OUT"
  python3 - "$OUT" /tmp/a4_fs.txt << 'PYEOF'
import sys
out, src = sys.argv[1], sys.argv[2]
lines = open(src, errors='ignore').read().splitlines()
try:
    start = next(i for i, l in enumerate(lines) if l.strip() == '---PROFILEDATA---')
except StopIteration:
    sys.exit(0)
with open(out, 'a') as fh:
    for l in lines[start+1:]:
        if l.strip() == '---PROFILEDATA---':
            break
        f = l.split(',')
        if len(f) >= 14 and f[1].lstrip('-').isdigit() and int(f[1]) > 0:
            fh.write('F ' + ' '.join(f[:14]) + '\n')
PYEOF
  sleep 0.9
done
echo "DONE cycles=$N -> $OUT"
