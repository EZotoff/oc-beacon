#!/usr/bin/env bash
# review.sh — 10h 模拟 cron 巡检（宿主侧 grep 判读；设备侧 grep 禁令内建）
# 用法: ./review.sh [--final]
set -u
BASE="$(cd "$(dirname "$0")/.." && pwd)"
LOG="$BASE/logs"; REP="$BASE/reports"
MARKER="$REP/.review_marker"; LEDGER="$REP/review_log.md"
FINAL="${1:-}"

cd "$BASE"
ts() { date "+%Y-%m-%d %H:%M:%S"; }

# 1) 驱动器与进度
PROG=$(python3 -c "import json;d=json.load(open('$REP/progress.json'));print(d.get('alive'),d.get('pid'),d.get('elapsed_h'),d.get('turns'),d.get('crashes'))" 2>/dev/null || echo "MISSING")
read -r ALIVE PID ELAPSED TURNS CRASHES <<< "$PROG"
DEAD=0
if [ "$ALIVE" != "True" ]; then DEAD=1; fi
if [ -n "${PID:-}" ] && [ "$PID" != "None" ] && ! [ -d "/proc/$PID" ]; then DEAD=1; fi
# 自动判档：已过 deadline 或驱动已终局 → 终盘模式（不复活）
if python3 - "$REP" <<'PYEOF' 2>/dev/null
import json, sys, os, time
d = json.load(open(os.path.join(sys.argv[1], "progress.json")))
sys.exit(0 if time.time() > d.get("deadline", 0) else 1)
PYEOF
then FINAL="--final"; fi

# 2) 拉新日志文件（mtime 晚于上轮标记）
TOUCH_ARGS=()
if [ -f "$MARKER" ]; then TOUCH_ARGS=(-newer "$MARKER"); fi
FILES=$(find "$LOG" -name 'cap_*.log' "${TOUCH_ARGS[@]}" 2>/dev/null | sort)
NFILES=$(echo "$FILES" | grep -c . || true)

# 3) 签名扫描（#484 判读集；两种负向 d 格式都必须扫）
scan() { grep -aE "$1" $FILES 2>/dev/null | head -"${2:-5}"; }
cnt() { grep -acE "$1" $FILES 2>/dev/null | awk '{s+=$NF} END {print s+0}'; }  # -c 多文件带文件名前缀，awk 取末段求和
NEG_MD=$(cnt 'MDResize card=[^ ]* h=[0-9]+ d=-')
NEG_RS=$(cnt 'RESIZE t=[0-9]+ key=t_[^ ]+ h [0-9]+->[0-9]+ \(d=-')
RESETK=$(cnt 'RESETKEY')
GUARD=$(cnt 'GUARD reanchor')
NONPRE=$(cnt 'nonPrefix')
RESERVE=$(cnt 'RESERVE flush reserved=')
ITEMP=$(cnt 'ItemP')
SWAP=$(cnt '\[swap\]')
HFDRIFT=$(cnt 'HFLICK PLAN.*add1 rem1')

# 3.5) #520 冻结渲染耦合判别（#484 签名集对正向爆发形态失明的补盲）
# 判据 = 三条件耦合（缺一不判，防 #509 冷重组 d=h 健康形态误报）：
#   ① 流式活跃窗（[flush] deltas= 批标记聚窗，20s 间隙分窗）内 MDPilot append 静默
#     （零 append 行——#513 定罪形态：live 消费链断，pilot 整轮不触发）；
#   ② 窗内（末批 +3s 完结宽限）出现大正向 MDResize（d≥500——#513 完结砸出 6618 /
#     10-04 存档 d=h 出生满高 508-3798 实测定标）；
#   ③ 窗内渐进步（0<d<500）不足 3 步——健康流式是 +66/+74/+132 密集渐进族。
# 注意：若 STREAM_DELTA_BUS 升全默认（append 静默=设计），① 需换基（③ 渐进判据仍有效）。
FROZEN_OUT=$(FILES="$FILES" python3 - <<'PYEOF' 2>/dev/null || true
import os, re

ev = []
ts_re = re.compile(r"^(\d{2})-(\d{2}) (\d{2}):(\d{2}):(\d{2})\.(\d{3})\s+\S/(\S+?)\s*\(\d+\):\s(.*)$")
rs_re = re.compile(r"card=(\S+) h=(\d+) d=(-?\d+)")

def sec_of(m):
    mon, day, h, mi, s, ms = (int(m.group(i)) for i in range(1, 7))
    return ((mon * 31 + day) * 86400 + h * 3600 + mi * 60 + s) * 1000 + ms

for path in sorted(os.environ.get("FILES", "").split()):
    try:
        with open(path, errors="replace") as f:
            for line in f:
                m = ts_re.match(line)
                if not m:
                    continue
                tag, msg, t = m.group(7), m.group(8), sec_of(m)
                if "[flush] deltas=" in msg:
                    ev.append((t, "flush", None))
                elif tag == "MDPilot" and msg.startswith("append "):
                    ev.append((t, "append", None))
                elif tag == "MDResize":
                    rm = rs_re.match(msg)
                    if rm:
                        ev.append((t, "resize", (rm.group(1), int(rm.group(2)), int(rm.group(3)))))
    except OSError:
        pass

ev.sort(key=lambda e: e[0])
windows, cur = [], []
for e in (x for x in ev if x[1] == "flush"):
    if cur and e[0] - cur[-1][0] > 20_000:
        windows.append(cur)
        cur = []
    cur.append(e)
if cur:
    windows.append(cur)

BURST, PROG_MIN, GRACE = 500, 3, 3_000
frozen = []
for w in windows:
    t0, t1 = w[0][0], w[-1][0] + GRACE
    appends = sum(1 for e in ev if e[1] == "append" and t0 <= e[0] <= t1)
    bursts = [e for e in ev if e[1] == "resize" and t0 <= e[0] <= t1 and e[2][2] >= BURST]
    prog = sum(1 for e in ev if e[1] == "resize" and t0 <= e[0] <= t1 and 0 < e[2][2] < BURST)
    if appends == 0 and bursts and prog < PROG_MIN:
        b = bursts[0]
        frozen.append("    MDResize card=%s h=%d d=%d（窗内零 append/渐进 %d 步）" % (b[2][0], b[2][1], b[2][2], prog))
print(len(frozen))
for line in frozen[:3]:
    print(line)
PYEOF
)
FROZEN=$(echo "$FROZEN_OUT" | head -1); FROZEN=${FROZEN:-0}

# 4) mock 与服务器侧
MOCKSTAT=$(docker inspect human-sim-mockllm --format '{{.State.Status}}' 2>/dev/null || echo gone)
SRV=$(curl -s -m 3 -o /dev/null -w '%{http_code}' http://127.0.0.1:4299/global/health 2>/dev/null || echo 000)
# grep -c 无匹配时已打印 0（退出码 1 触发 || echo 0 会双行——存量瑕疵顺手修）
MOCKCALLS=$(docker logs --since 2h human-sim-mockllm 2>/dev/null | grep -c 'call=')

# 5) 台账追加
{
  echo ""
  echo "## $(ts) ${FINAL:+【终盘】}巡检"
  echo "- 驱动: alive=$ALIVE pid=${PID:-?} elapsed=${ELAPSED:-?}h turns=${TURNS:-?} crashes=${CRASHES:-?} 新日志文件=$NFILES"
  echo "- 签名计数: negMDResize=$NEG_MD negRESIZE=$NEG_RS RESETKEY=$RESETK GUARDreanchor=$GUARD nonPrefix=$NONPRE RESERVEflush=$RESERVE ItemP=$ITEMP swap=$SWAP HFLICKdrift=$HFDRIFT frozenRender=$FROZEN"
  echo "- 基建: mock=$MOCKSTAT srv=$SRV mockCalls(2h)=$MOCKCALLS"
  if [ "$NEG_MD" != "0" ] || [ "$NEG_RS" != "0" ] || [ "$RESETK" != "0" ] || [ "$FROZEN" != "0" ]; then
    echo "- ⚠️ 命中异常签名，样本："
    scan 'MDResize card=[^ ]* h=[0-9]+ d=-' 3
    scan 'RESIZE t=[0-9]+ key=t_[^ ]+ h [0-9]+->[0-9]+ \(d=-' 3
    scan 'RESETKEY' 3
    if [ "$FROZEN" != "0" ]; then
      echo "    #520 冻结渲染耦合判别（流中零 append × 爆发 d）命中 $FROZEN 窗："
      echo "$FROZEN_OUT" | tail -n +2
    fi
  fi
} >> "$LEDGER"

# 6) 死亡复活（终盘不复活）
if [ "$DEAD" = "1" ] && [ "$FINAL" != "--final" ]; then
  REM=$(python3 -c "import json;d=json.load(open('$REP/progress.json'));import time;print(max(0.1,round((d['deadline']-time.time())/3600,2)))" 2>/dev/null || echo "")
  if [ -n "$REM" ]; then
    echo "- ☠️ 驱动死亡，复活剩余 ${REM}h" >> "$LEDGER"
    nohup setsid python3 "$BASE/driver/driver.py" --hours "$REM" >> "$REP/driver.log" 2>&1 < /dev/null &
    disown
  fi
fi
if [ "$MOCKSTAT" != "running" ]; then
  docker start human-sim-mockllm >/dev/null 2>&1 && echo "- mock 容器已重启" >> "$LEDGER"
fi
if [ "$SRV" != "200" ]; then
  docker restart v1-e2e >/dev/null 2>&1 && echo "- v1-e2e 已重启（srv=$SRV）" >> "$LEDGER"
fi

touch "$MARKER"

# 7) 终盘收割（--final 或自动判档）：汇总报告
if [ "$FINAL" = "--final" ]; then
  python3 - "$BASE" <<'PYEOF' >> "$LEDGER" 2>/dev/null || true
import json, os, subprocess, sys, collections, re
BASE = sys.argv[1]; REP = os.path.join(BASE, "reports")
kinds = collections.Counter()
try:
    for line in open(os.path.join(REP, "actions.jsonl")):
        try: kinds[json.loads(line).get("kind", "?")] += 1
        except Exception: pass
except FileNotFoundError: pass
mock = subprocess.run(["docker", "logs", "human-sim-mockllm"], capture_output=True, text=True).stdout
calls = re.findall(r"corpus=(\d+) profile=([\w-]+)", mock)
corpus = collections.Counter(c for c, _ in calls); prof = collections.Counter(p for _, p in calls)
ev = sorted(os.listdir(os.path.join(BASE, "evidence"))) if os.path.isdir(os.path.join(BASE, "evidence")) else []
prog = json.load(open(os.path.join(REP, "progress.json")))
print("- 终盘统计: kinds=" + json.dumps(dict(kinds.most_common(14))))
print("- mock calls=" + str(len(calls)) + " abort=" + str(prof.get("abort-mid", 0)) +
      " 语料分布=" + json.dumps(dict(sorted(corpus.items()))))
print("- evidence(" + str(len(ev)) + ")=" + ",".join(ev[:12]))
print("- progress=" + json.dumps({k: prog.get(k) for k in ("turns", "crashes", "interrupts", "exploratory", "dialogs", "elapsed_h")}))
PYEOF
  echo "- 【终盘完成】结论以本节签名计数对照 #484+#520 基线（10h 零负向 d / 零 RESETKEY / 零 frozenRender）判读；异常则提示登记 backlog" >> "$LEDGER"
fi

tail -14 "$LEDGER"
