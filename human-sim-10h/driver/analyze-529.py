#!/usr/bin/env python3
# analyze-529.py — #529 soak 离线判读器（宿主侧解析 logs529/cap_*.log）
#
# 判读面（多维）：
#   1. 逐集 topY 稳定性：PLACED 按 E<k> 连续段分组——展开集 |topY(f=1 末帧) − topY(f≈0 首帧)|
#      ≤4px=干净；>4px=DIRTY（记录幅度/boxH/时间）。后随 exit-CANCELLED 的段剔除
#      （用户滚动取消=位置合法归用户），单列计数。
#   2. 反射数学：expand-anchor(+H) 与同段 PLACED fiso 增量对账（±4px；norm 折叠段跳过）；
#      close-anchor-request vs close-post fiso 对账（±2px）。
#   3. 静止门统计：anchor-quiesce stable/ms 分布（stable<2=超时捕获，列样本）。
#   4. 异常面：exit-THREW / [DEBUG-426] dispatch threw / steady 大额漂移 / FATAL
#      （抓取本身 --pid=app，文件内 FATAL 即 app 侧）。
#   5. 通用健康签名（#484 判读集同源）：negMDResize/negRESIZE/RESETKEY/GUARD/nonPrefix/RESERVE。
#   6. 交叠嫌疑：段内 host 非单调（两卡同窗动画）单列，不进自动判档。
import glob, os, re, sys, collections, statistics

BASE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
LOGS = os.path.join(BASE, "logs529")
OUT = os.path.join(BASE, "reports", "529", "review.md")

RE_PLACED = re.compile(
    r"^(\d\d-\d\d \d\d:\d\d:\d\d\.\d+).*E(\d+) PLACED .*topY=(-?\d+) boxH=(\d+) rep=(-?\d+) "
    r"abs=(-?\d+) f=([\d.]+) fii=(-?\d+) fiso=(-?\d+) host=(-?\d+)")
# 组索引（match.groups() 0 基）: 0=ts 1=E 2=topY 3=boxH 4=rep 5=abs 6=f 7=fii 8=fiso 9=host
I_TS, I_E, I_TOPY, I_BOXH, I_REP, I_ABS, I_F, I_FII, I_FISO, I_HOST = range(10)
RE_ANCHOR = re.compile(r"^(\d\d-\d\d \d\d:\d\d:\d\d\.\d+).*expand-anchor fii=(-?\d+) fiso=(-?\d+) \+H=(\d+).*?(norm->(-?\d+):(-?\d+))?")
RE_QUIESCE = re.compile(r"anchor-quiesce stable=(\d+) ms=(\d+)")
RE_CLOSE_REQ = re.compile(r"close-anchor-request fii=(-?\d+) fiso=(-?\d+)")
RE_CLOSE_POST = re.compile(r"close-post fii=(-?\d+) fiso=(-?\d+)")
RE_EXIT = re.compile(r"^(\d\d-\d\d \d\d:\d\d:\d\d\.\d+).*episode exit-(CANCELLED|THREW)")
RE_DONE = re.compile(r"episode done .*completed=(\w+)")
RE_DISPATCH_THROW = re.compile(r"\[DEBUG-426\] dispatch threw")


def parse(files):
    placed, anchors, quiesce, closes, exits, dones, dthrows = [], [], [], [], [], [], 0
    raw_lines = 0
    for f in files:
        try:
            for line in open(f, errors="replace"):
                raw_lines += 1
                m = RE_PLACED.match(line)
                if m:
                    placed.append(m.groups()); continue
                m = RE_ANCHOR.match(line)
                if m:
                    anchors.append(m.groups()); continue
                m = RE_QUIESCE.search(line)
                if m:
                    quiesce.append((int(m.group(1)), int(m.group(2)))); continue
                m = RE_CLOSE_REQ.search(line)
                if m:
                    closes.append(("req", m.group(1), m.group(2))); continue
                m = RE_CLOSE_POST.search(line)
                if m:
                    closes.append(("post", m.group(1), m.group(2))); continue
                m = RE_EXIT.search(line)
                if m:
                    exits.append((m.group(1), m.group(2))); continue
                m = RE_DONE.search(line)
                if m:
                    dones.append(m.group(1)); continue
                if RE_DISPATCH_THROW.search(line):
                    dthrows += 1
        except FileNotFoundError:
            pass
    return dict(placed=placed, anchors=anchors, quiesce=quiesce, closes=closes,
                exits=exits, dones=dones, dthrows=dthrows, raw=raw_lines)


def bursts(placed):
    """按 E<k> 连续段分组；同 E 号内再做「落后复升」切割——
    smoke 实证：同 item 两张卡各自 E1 相邻爆发（fiso/host 连续但 topY 跳到
    另一卡基线），f 序列 1.000→0.001→1.000 即新集起点（单集内 f 单调，
    warmup 只升不降）。"""
    out, cur, cur_k = [], [], None
    for g in placed:
        k = int(g[I_E])
        if cur and k != cur_k:
            out.extend(_split_on_rise(cur)); cur = []
        cur_k = k
        cur.append(g)
    if cur: out.extend(_split_on_rise(cur))
    return out


def _split_on_rise(run):
    """在「f 从高分跌到低分、且段内其后复升」的位置切割（收起终止段不切：跌后无升）。"""
    cuts, prev_hi = [], False
    for i, g in enumerate(run):
        f = float(g[I_F])
        if f <= 0.01 and prev_hi and any(float(x[I_F]) >= 0.999 for x in run[i:]):
            cuts.append(i)
        prev_hi = f >= 0.999
    if not cuts:
        return [run]
    parts, start = [], 0
    for c in cuts:
        parts.append(run[start:c]); start = c
    parts.append(run[start:])
    return [p for p in parts if p]


def nearest_anchor_before(anchors, ts):
    best = None
    for a in anchors:
        if a[0] < ts and (best is None or a[0] > best[0]):
            best = a
    return best


def main():
    files = sorted(glob.glob(os.path.join(LOGS, "cap_*.log")))
    if not files:
        print("NO LOGS"); sys.exit(2)
    d = parse(files)
    report = ["# #529 soak 判读", "",
              f"- 抓取文件 {len(files)} 个 / 原始行 {d['raw']}",
              f"- PLACED {len(d['placed'])} 行 / expand-anchor {len(d['anchors'])} / quiesce {len(d['quiesce'])} / exit {len(d['exits'])}"]

    # 交叠/脏段判读
    dirty, clean_expand, clean_close, suspect = [], 0, 0, []
    fiso_mismatch = []
    cancel_ts = [t for t, k in d["exits"] if k == "CANCELLED"]
    for b in bursts(d["placed"]):
        e = f"E{b[0][I_E]}"
        first_ts, last_ts = b[0][I_TS], b[-1][I_TS]
        # 滚动取消的段：位置合法归用户（guard 保证取消先于任何反射帧），剔除不判档
        if any(first_ts <= t <= _shift(last_ts, 2.0) for t in cancel_ts):
            continue
        i_f1 = next((i for i, g in enumerate(b) if float(g[I_F]) >= 0.999), None)
        i_f0 = next((i for i, g in enumerate(b) if float(g[I_F]) <= 0.01), None)
        if i_f1 is None:
            continue  # settle 中被取消（无反射帧）
        hosts = [int(g[I_HOST]) for g in b]
        mono_ok = all(hosts[i] <= hosts[i + 1] or hosts[i] >= hosts[i + 1] - 2 for i in range(len(hosts) - 1))
        if not mono_ok:
            suspect.append((e, first_ts, hosts)); continue
        if i_f0 is not None and i_f0 > i_f1:
            # 收起段（f 1→0：首帧在高分）——卡行全程钉住（镜像语义），首尾 topY 差即判据
            dty = abs(int(b[-1][I_TOPY]) - int(b[0][I_TOPY]))
            if dty > 4:
                dirty.append((e + "-close", first_ts, dty, int(b[0][I_BOXH]), int(b[0][I_FII])))
            else:
                clean_close += 1
            continue
        f0 = [g for g in b if float(g[I_F]) <= 0.01]
        f1 = [g for g in b if float(g[I_F]) >= 0.999]
        if not f0:
            clean_close += 1  # f1-only：幕布期被取消的收起段
            continue
        # 展开段（f 0.001→1.000：首帧在低分）——展开前后卡行钉住（反射配对语义）
        dty = abs(int(f1[-1][I_TOPY]) - int(f0[0][I_TOPY]))
        boxH = int(f1[-1][I_BOXH])
        if dty > 4:
            dirty.append((e, first_ts, dty, boxH, int(f1[-1][I_FII])))
            continue
        clean_expand += 1
        # 反射数学对账（fii 未变且无 norm 折叠的展开集）
        a = nearest_anchor_before(d["anchors"], f1[0][I_TS])
        if a and _ts_diff(a[0], f1[0][I_TS]) <= 5.0 and a[4] is None:
            H = int(a[3])
            if int(b[0][I_FII]) == int(b[-1][I_FII]):
                fiso_delta = int(b[-1][I_FISO]) - int(b[0][I_FISO])
                if abs(fiso_delta - H) > 4:
                    fiso_mismatch.append((e, first_ts, fiso_delta, H))

    # close 对账：req → 紧随 post
    close_pairs, close_bad = 0, []
    pend = None
    for c in d["closes"]:
        if c[0] == "req":
            pend = (int(c[1]), int(c[2]))
        elif c[0] == "post" and pend:
            close_pairs += 1
            if abs(int(c[1]) - pend[0]) > 2 or abs(int(c[2]) - pend[1]) > 2:
                close_bad.append((pend, (int(c[1]), int(c[2]))))
            pend = None

    # 门统计
    st_hist = collections.Counter(q[0] for q in d["quiesce"])
    ms = [q[1] for q in d["quiesce"]]
    timeouts = [q for q in d["quiesce"] if q[0] < 2]

    # 健康签名（#484 集；negRESIZE 需收起配对归因——卡收起的 −H 塌缩是配对设计行为，
    # 10h 流式 soak 零收起故基线为 0；未配对的负向 resize 才是坍缩族嫌疑）
    sig = {}
    neg_resize_lines = []
    pats = {
        "negMDResize": r"MDResize card=[^ ]* h=[0-9]+ d=-",
        "negRESIZE": r"RESIZE t=[0-9]+ key=t_[^ ]+ h [0-9]+->[0-9]+ \(d=-",
        "RESETKEY": r"RESETKEY",
        "GUARDreanchor": r"GUARD reanchor",
        "nonPrefix": r"nonPrefix",
        "RESERVEflush": r"RESERVE flush reserved=",
        "FATAL": r"FATAL EXCEPTION",
        "ANR": r"ANR in dev\.leonardo",
    }
    close_ts = []
    for f in files:
        try:
            for line in open(f, errors="replace"):
                m = re.match(r"^(\d\d-\d\d \d\d:\d\d:\d\d\.\d+)", line)
                ts = m.group(1) if m else None
                if re.search(pats["negRESIZE"], line):
                    neg_resize_lines.append((ts, line.strip()))
                    sig["negRESIZE"] = sig.get("negRESIZE", 0) + 1
                if ts and re.search(r"close-anchor-request|close-post|close\+2f|episode done f=0\.000", line):
                    close_ts.append(ts)
        except FileNotFoundError:
            pass
    for name, p in pats.items():
        if name == "negRESIZE":
            continue
        n = 0
        cp = re.compile(p)
        for f in files:
            try:
                for line in open(f, errors="replace"):
                    if cp.search(line): n += 1
            except FileNotFoundError:
                pass
        sig[name] = n
    # negRESIZE 配对：±3s 内有收起标记 = 配对塌缩（预期）
    neg_unpaired = []
    for ts, line in neg_resize_lines:
        if ts is None:
            neg_unpaired.append(line); continue
        if not any(t and abs(_ts_diff(t, ts)) <= 3.0 for t in close_ts):
            neg_unpaired.append(line)

    cancelled_n = sum(1 for _, k in d["exits"] if k == "CANCELLED")
    threw_n = sum(1 for _, k in d["exits"] if k == "THREW")

    verdict_pass = (not dirty and not close_bad and not fiso_mismatch
                    and threw_n == 0 and d["dthrows"] == 0
                    and sig["FATAL"] == 0 and sig["ANR"] == 0
                    and not neg_unpaired)

    report += [
        "",
        "## 判读",
        f"- 展开集干净 {clean_expand} / 收起集干净 {clean_close} / **脏 {len(dirty)}** / 交叠嫌疑 {len(suspect)}（单列不判档）",
        f"- 收起 req↔post 对账 {close_pairs} 对，不匹配 {len(close_bad)}",
        f"- fiso↔H 对账不匹配 {len(fiso_mismatch)}",
        f"- 静止门: stable 直方 {dict(sorted(st_hist.items()))} / ms p50={statistics.median(ms) if ms else '-'} max={max(ms) if ms else '-'} / 超时捕获(stable<2) {len(timeouts)}",
        f"- exit: CANCELLED {cancelled_n}（滚动取消,预期） / **THREW {threw_n}（bug）** / dispatch-threw {d['dthrows']}",
        f"- 健康签名: " + " ".join(f"{k}={v}" for k, v in sig.items()) +
        f"（negRESIZE 未配对 {len(neg_unpaired)}——配对=收起 −H 塌缩,预期）",
        "",
        f"## 判档: {'✅ PASS' if verdict_pass else '❌ FAIL'}",
    ]
    if dirty:
        report += ["", "### DIRTY 样本（topY 跳变集）"]
        for e, ts, dlt, boxH, fii in dirty[:40]:
            report.append(f"- {ts} {e} ΔtopY={dlt} boxH={boxH} fii={fii}")
    if fiso_mismatch:
        report += ["", "### fiso↔H 不匹配样本"]
        for e, ts, fd, H in fiso_mismatch[:20]:
            report.append(f"- {ts} {e} fisoΔ={fd} H={H}")
    if close_bad:
        report += ["", "### close 对账不匹配"]
        for pend, got in close_bad[:20]:
            report.append(f"- req={pend} post={got}")
    if suspect:
        report += ["", "### 交叠嫌疑段（人工复核）"]
        for e, ts, hosts in suspect[:10]:
            report.append(f"- {ts} {e} hosts={hosts[:8]}")
    if timeouts:
        report += ["", "### 门超时捕获样本（stable<2，超时按现状捕获）"]
        report.append(f"- 共 {len(timeouts)} 次（详情 grep 'anchor-quiesce stable=' logs529/）")
    if neg_unpaired:
        report += ["", "### negRESIZE 未配对样本（坍缩族嫌疑）"]
        for line in neg_unpaired[:12]:
            report.append(f"- {line}")

    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    open(OUT, "w").write("\n".join(report) + "\n")
    print("\n".join(report[:14]))
    print("...")
    print(f"完整报告 → {OUT}")
    sys.exit(0 if verdict_pass else 1)


def _ts_diff(a, b):
    """'10-08 03:42:36.108' 两者差秒（同日内）。"""
    def sec(t):
        hh, mm, rest = t.split()[1].split(":")
        return int(hh) * 3600 + int(mm) * 60 + float(rest)
    return abs(sec(a) - sec(b))


def _shift(t, s):
    """时间戳字符串 +s 秒（跨分/时进位交给尾数溢出的字典序近似——同窗 2s 内足够）。"""
    hh, mm, rest = t.split()[1].split(":")
    total = int(hh) * 3600 + int(mm) * 60 + float(rest) + s
    nh, rem = int(total // 3600), total % 3600
    nm, ns = int(rem // 60), rem % 60
    return t.split()[0] + " %02d:%02d:%06.3f" % (nh, nm, ns)


if __name__ == "__main__":
    main()
