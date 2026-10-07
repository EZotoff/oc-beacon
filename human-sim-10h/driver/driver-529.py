#!/usr/bin/env python3
# driver-529.py — #529 专项 5h 拟人 soak 驱动器（复用 human-sim-10h/driver/driver.py 全部加固基建）
#
# 与 10h 流式 soak（#511）的差异：
#   · 服务器 = 宿主 v1:4199 真实环境（无需发消息——#529 竞态在 UI 层，静态富思考卡会话即原料）
#   · 目标会话 = 「App verification steps」(78 reasoning) / 「CARD-452B」(35) 按权随机
#   · 行为循环 = 展开节律族（回收-E1/连点/阅读翻看/速点收起/多卡堆叠），全部拟人抖动
#   · 证据落 reports/529/ + logs529/ + evidence/529/（不覆盖 #511 台账）
#   · 无线 adb 自愈（无 USB 在位；断连即 adb connect + 重 reverse + LogcatCapture 轮转自重解析 pid）
import os, sys, json, time, random, threading, subprocess

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import driver as d  # noqa: E402  复用基建（import-safe：__main__ 守卫）

# ---------- 覆写运行面 ----------
WIRELESS = "192.168.1.2:34935"
d.SERIAL = WIRELESS
d.PORT = 4199  # 宿主 v1 真实环境（systemd 按需起，rich sessions）
BASE529 = os.path.join(d.BASE, "reports", "529")
LOGS529 = os.path.join(d.BASE, "logs529")
EVID529 = os.path.join(d.BASE, "evidence", "529")
for p in (BASE529, LOGS529, EVID529):
    os.makedirs(p, exist_ok=True)
d.LOGDIR = LOGS529
d.EVID = EVID529
d.ACTIONS = os.path.join(BASE529, "actions.jsonl")
d.PROGRESS = os.path.join(BASE529, "progress.json")

SESSIONS = [("App verification steps", 0.65), ("CARD-452B", 0.35)]
st = {"cycles": 0, "taps_expand": 0, "taps_collapse": 0, "cards_found": 0,
      "navigations": 0, "recordings": 0, "screenshots": 0, "reconnects": 0}

CARD_ROW = ("思考完毕",)
TOOL_ROW = ("$ ",)


def log(msg):
    d.log("[529] " + str(msg))


def action(kind, **kw):
    d.action(kind, **kw)


def ensure_adb():
    """无线 adb 自愈：设备掉线即 connect + 重 reverse。"""
    r = subprocess.run([d.ADB, "devices"], capture_output=True, text=True, timeout=15)
    if WIRELESS not in r.stdout:
        subprocess.run([d.ADB, "connect", WIRELESS], capture_output=True, text=True, timeout=20)
        time.sleep(1.5)
        r = subprocess.run([d.ADB, "devices"], capture_output=True, text=True, timeout=15)
        if WIRELESS not in r.stdout:
            log("adb connect 失败（本轮跳过）")
            return False
        st["reconnects"] += 1
        d.sh("reverse", f"tcp:{d.PORT}", f"tcp:{d.PORT}")
        action("adb_reconnect")
    return True


# ---------- 卡片发现 ----------
def find_card_rows():
    """返回 [(y_center, kind, label)]——思考卡行 + 工具行（$ 命令行）。
    a11y 陈旧树自愈：空结果且在 chat 态时重 dump 一次。"""
    for attempt in range(2):
        root = d.dump_xml()
        if root is None:
            time.sleep(0.8); continue
        rows = []
        for n in d.nodes(root, lambda n: bool((n.get("text") or "").strip())):
            t = (n.get("text") or "").strip()
            c = d.center(n.get("bounds", ""))
            if not c: continue
            y1 = c[1]
            if y1 < 300 or y1 > 2380:  # 顶栏遮挡/输入栏区（定向卡行点击可近底，真机实证思考卡行可低至 2338）
                continue
            if any(t.startswith(p) for p in CARD_ROW):
                rows.append((int(y1), "thinking", t[:36]))
            elif any(t.startswith(p) for p in TOOL_ROW) and len(t) > 4:
                rows.append((int(y1), "tool", t[:36]))
        if rows:
            st["cards_found"] += len(rows)
            return sorted(rows)
        if d.screen_state(root) != "chat":
            return []
        time.sleep(0.9)  # 陈旧树：等一拍重 dump
    return []


def find_card_scrolling(max_steps=6):
    """滚动找卡（拟人扫读）：当前视口无卡即随机方向翻找（偏旧侧——历史区卡密度高）。"""
    for _ in range(max_steps):
        rows = find_card_rows()
        if rows:
            return rows
        if d.rng.random() < 0.7:
            d.swipe(600, 500, 600, 2100, d.rng.randint(180, 320))
        else:
            d.swipe(600, 2100, 600, 500, d.rng.randint(180, 320))
        time.sleep(d.rng.uniform(0.5, 1.1))
    return find_card_rows()


def pick_card(rows):
    """拟人偏好：视口中部优先（阅读重心），带随机。"""
    if not rows: return None
    scored = sorted(rows, key=lambda r: abs(r[0] - 1300) + d.rng.randint(0, 300))
    return scored[0]


def tap_row(y, label, expect):
    x = 240 + d.rng.randint(0, 220)
    d.sh("shell", "input", "tap", str(x), str(y + d.rng.randint(-4, 4)))
    action("tap_card", y=y, label=label, expect=expect)
    return True


# ---------- 节律族（每条都是 E1/E2/E3+ 的原料制造器） ----------
def rhythm_recycle_e1():
    """定罪节奏：滚离视口（回收）→ 带余尾滚回 → 立即点击展开。"""
    rows = find_card_scrolling()
    card = pick_card(rows)
    if not card: return False
    # 滚离 2-4 屏（向旧/向新随机——双向都要回收面）
    older = d.rng.random() < 0.5
    for _ in range(d.rng.randint(2, 4)):
        if older: d.swipe(600, 500, 600, 2100, d.rng.randint(150, 300))
        else:     d.swipe(600, 2100, 600, 500, d.rng.randint(150, 300))
        time.sleep(d.rng.uniform(0.35, 0.9))
    time.sleep(d.rng.uniform(0.4, 1.4))  # 人类反应
    # 滚回（快速 fling，余尾自然存在）
    for _ in range(d.rng.randint(2, 4)):
        if older: d.swipe(600, 2100, 600, 700, d.rng.randint(90, 180))
        else:     d.swipe(600, 700, 600, 2100, d.rng.randint(90, 180))
        time.sleep(d.rng.uniform(0.25, 0.6))
    # 立即在可见范围内找卡速点（不等待完全静止——脏节奏核心）
    rows = find_card_rows()
    card = pick_card(rows)
    if not card: return False
    tap_row(card[0], card[2], "expand")
    st["taps_expand"] += 1
    time.sleep(d.rng.uniform(0.8, 2.5))
    if d.rng.random() < 0.8:
        rows2 = find_card_rows()
        if rows2:
            c2 = pick_card([r for r in rows2 if abs(r[0] - card[0]) < 400] or rows2)
            tap_row(c2[0], c2[2], "collapse"); st["taps_collapse"] += 1
    return True


def rhythm_toggle_cycle():
    """同卡多次展开/收起（E3+ 暖路径 + 少量速点）。"""
    rows = find_card_scrolling(4)
    card = pick_card(rows)
    if not card: return False
    for i in range(d.rng.randint(2, 4)):
        tap_row(card[0] + (0 if i % 2 == 0 else d.rng.randint(-8, 8)), card[2],
                "expand" if i % 2 == 0 else "collapse")
        if i % 2 == 0: st["taps_expand"] += 1
        else: st["taps_collapse"] += 1
        # 奇数轮偶发速点（机器极限间隔 ~0.3s）
        time.sleep(d.rng.uniform(0.28, 0.5) if d.rng.random() < 0.3
                   else d.rng.uniform(1.2, 6.0))
        rows = find_card_rows()
        near = [r for r in rows if abs(r[0] - card[0]) < 500]
        if not near: return True  # 卡滚出视口
        card = pick_card(near)
    return True


def rhythm_read_and_toggle():
    """人类阅读：随机滚动若干次后展开视口内一张卡，读完收起。"""
    for _ in range(d.rng.randint(2, 6)):
        x1, y1, y2 = d.rng.choice([(600, 1900, 700), (600, 700, 1900), (600, 1800, 1000), (600, 1000, 1900)])
        d.swipe(x1 + d.rng.randint(-40, 40), y1, x1 + d.rng.randint(-40, 40),
                y2 + d.rng.randint(-60, 60), d.rng.randint(160, 420))
        time.sleep(d.rng.uniform(0.7, 2.8))
    rows = find_card_scrolling(3)
    card = pick_card(rows)
    if not card: return False
    tap_row(card[0], card[2], "expand"); st["taps_expand"] += 1
    time.sleep(d.rng.uniform(1.5, 9.0))  # 读内容
    rows2 = find_card_rows()
    near = [r for r in rows2 if abs(r[0] - card[0]) < 600] or rows2
    if near:
        c2 = pick_card(near); tap_row(c2[0], c2[2], "collapse"); st["taps_collapse"] += 1
    return True


def rhythm_rapid_double():
    """速点连击：展开→立即收起（机器极限间隔，压重定向取消面）。"""
    rows = find_card_scrolling(4)
    card = pick_card(rows)
    if not card: return False
    for i in range(d.rng.randint(2, 3)):
        tap_row(card[0], card[2], "rapid"); st["taps_expand"] += 1
        time.sleep(0.28)
        tap_row(card[0], card[2], "rapid"); st["taps_collapse"] += 1
        time.sleep(d.rng.uniform(0.5, 1.2))
    return True


def rhythm_multi_card():
    """多卡堆叠：连续展开 2-3 张不同卡（稳态互扰面），随机顺序收起。"""
    rows = find_card_scrolling(5)
    if len(rows) < 2: return False
    picks = d.rng.sample(rows, min(3, len(rows)))
    opened = []
    for r in picks:
        tap_row(r[0], r[2], "expand"); st["taps_expand"] += 1
        opened.append(r)
        time.sleep(d.rng.uniform(0.9, 3.0))
        rows = find_card_rows()
        if not rows: break
    d.rng.shuffle(opened)
    for r in opened:
        rows = find_card_rows()
        near = [x for x in rows if abs(x[0] - r[0]) < 700] or rows
        if near:
            c2 = pick_card(near); tap_row(c2[0], c2[2], "collapse"); st["taps_collapse"] += 1
            time.sleep(d.rng.uniform(0.8, 2.2))
    return True


def rhythm_away():
    """离开/回桌/纯发呆（人类真实性 + 冷热组合面）。"""
    r = d.rng.random()
    if r < 0.25:
        d.app_home_away()
    elif r < 0.4:
        open_target_session(force=True)
    else:
        time.sleep(d.rng.uniform(8, 50))


RHYTHMS = [
    (rhythm_recycle_e1, 0.30),
    (rhythm_toggle_cycle, 0.20),
    (rhythm_read_and_toggle, 0.20),
    (rhythm_rapid_double, 0.10),
    (rhythm_multi_card, 0.10),
    (rhythm_away, 0.10),
]


# ---------- 会话导航 ----------
def open_target_session(force=False):
    root = d.dump_xml()
    if not force and root is not None and d.screen_state(root) == "chat":
        return True  # 已在会话里（沿用当前）
    if not d.goto_list_from_anywhere():
        return False
    title, _ = d.rng.choices(SESSIONS, weights=[w for _, w in SESSIONS])[0]
    for _ in range(8):
        root = d.dump_xml()
        if root is None: time.sleep(1); continue
        rows = d.by_text(root, title)
        if rows:
            d.tap_node(rows[0])
            time.sleep(d.rng.uniform(2.0, 3.2))
            st["navigations"] += 1
            action("open_session", title=title)
            return d.screen_state(d.dump_xml()) == "chat"
        d.swipe(600, 1800, 600, 700, 260)  # 列表向下翻（标题在下方）
        time.sleep(d.rng.uniform(0.7, 1.3))
    log(f"目标会话未找到: {title}")
    return False


# ---------- 取证 ----------
def screenshot(tag):
    d.screenshot("529_" + tag)
    st["screenshots"] += 1


def record_clip():
    """25s 录屏片段（evidence/529/，约 20 分钟一次）。"""
    def _rec():
        try:
            fn = time.strftime("%H%M%S") + "_529clip.mp4"
            sh = subprocess.run(
                [d.ADB, "-s", WIRELESS, "shell", "screenrecord",
                 "--time-limit", "25", "/sdcard/" + fn], timeout=60)
            time.sleep(1)
            subprocess.run([d.ADB, "-s", WIRELESS, "pull", "/sdcard/" + fn,
                            os.path.join(EVID529, fn)], capture_output=True, timeout=60)
            subprocess.run([d.ADB, "-s", WIRELESS, "shell", "rm", "-f", "/sdcard/" + fn],
                           capture_output=True, timeout=30)
            action("screenrecord", file=fn, rc=sh.returncode)
            st["recordings"] += 1
        except Exception as e:
            log("record fail " + str(e))
    threading.Thread(target=_rec, daemon=True).start()


# ---------- 主循环 ----------
def main():
    import argparse
    ap = argparse.ArgumentParser()
    ap.add_argument("--hours", type=float, default=5.0)
    ap.add_argument("--seed", type=int, default=None)
    args = ap.parse_args()
    if args.seed is not None:
        d.rng.seed(args.seed)

    subprocess.run(["systemctl", "--user", "start", "opencode-v1.service"], capture_output=True)
    if not ensure_adb():
        log("设备不可达——退出"); sys.exit(2)
    if not d.entry():
        log("入口失败——退出"); sys.exit(2)
    if not open_target_session(force=True):
        log("目标会话打开失败——退出"); sys.exit(2)

    cap = d.LogcatCapture(); cap.start()
    hl = threading.Thread(target=d.health_loop, daemon=True); hl.start()

    d.state["started"] = time.time()
    d.state["deadline"] = d.state["started"] + args.hours * 3600
    log(f"529 soak 启动 {args.hours}h pid={os.getpid()}")
    action("soak529_start", hours=args.hours, pid=os.getpid())

    last_rec = last_shot = 0.0
    while time.time() < d.state["deadline"] and not d.stop_flag.is_set():
        try:
            if not ensure_adb():
                time.sleep(20); continue
            d.wake_check()
            d.escape_foreign_if_any()
            root = d.dump_xml()
            stt = d.screen_state(root)
            if stt == "dialog":
                d.dismiss_dialogs(root)
            elif stt != "chat":
                if not open_target_session(force=True):
                    time.sleep(10); continue

            fn = d.rng.choices([f for f, _ in RHYTHMS], weights=[w for _, w in RHYTHMS])[0]
            st["cycles"] += 1
            action("rhythm", n=st["cycles"], name=fn.__name__,
                   elapsed_min=round((time.time() - d.state["started"]) / 60, 1))
            fn()

            if time.time() - last_rec > 1200:
                last_rec = time.time(); record_clip()
            if time.time() - last_shot > 600:
                last_shot = time.time(); screenshot("periodic")

            json.dump({"started": d.state["started"], "deadline": d.state["deadline"],
                       "elapsed_h": round((time.time() - d.state["started"]) / 3600, 2),
                       "alive": True, "pid": os.getpid(), **st},
                      open(d.PROGRESS, "w"))
        except KeyboardInterrupt:
            break
        except Exception as e:
            log(f"cycle 异常 {type(e).__name__}: {e}")
            screenshot("cycle_exc")
            try: open_target_session(force=True)
            except Exception: d.entry()
            time.sleep(5)

    d.stop_flag.set()
    json.dump({**st, "alive": False, "ended": time.time()}, open(d.PROGRESS, "w"))
    action("soak529_end", **st)
    log(f"529 soak 结束：{st}")


if __name__ == "__main__":
    main()
