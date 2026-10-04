#!/usr/bin/env python3
# driver.py — 10 小时人类行为模拟驱动器（真机 e69a99d8 / dev 包 / v1-e2e:4299 mock 链路）
#
# 人类特征建模（用户要求）：目标相似、路径基本一致、但每次操作有随机性——
#   · 间隔随机（点击/思考/离开 时长全部抖动）
#   · 随机点击可交互元素（安全护栏过滤破坏性目标）
#   · 流程随机（发消息/读历史/切会话/回桌面/回应用 中随机走）
#
# 纪律内建（AGENTS.md / 记忆）：
#   · 禁 `input text`（keyevent 打字，仅 ASCII）
#   · IME 弹出后按钮位移 → 一律 dump 后动态定位再点
#   · 列表页禁 BACK（会退服务器页），只前进导航
#   · 禁改系统设置；只做应用内交互
#   · 探针判定 grep 一律宿主侧文件（pull 语义：本地 logcat 流文件）
#   · 证据（截图/崩溃日志）落 evidence/，台账落 reports/
import argparse, json, os, random, re, subprocess, sys, threading, time
import xml.etree.ElementTree as ET

BASE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))  # human-sim-10h/
ADB = os.path.expanduser("~/android-sdk/platform-tools/adb")
SERIAL = "e69a99d8"
PKG = "dev.leonardo.ocbeacon.dev"
ACT = PKG + "/dev.leonardo.ocbeacon.MainActivity"
PORT = 4299
LOGDIR = os.path.join(BASE, "logs")
EVID = os.path.join(BASE, "evidence")
REP = os.path.join(BASE, "reports")
ACTIONS = os.path.join(BASE, "reports", "actions.jsonl")
PROGRESS = os.path.join(BASE, "reports", "progress.json")

# 破坏性目标黑名单（text/desc 命中即不点）——语言切换/主题/删除/断开/清除全挡
BLOCKLIST = re.compile(
    r"删除|移除|断开|清除|重置|注销|退出登录|卸载|清空|编辑服务器|删除服务器|语言|language|"
    r"翻译|主题|theme|dark|夜间|切换服务器|sign.?out|delete|remove|disconnect|reset|clear|"
    r"导出|导入|备份|还原全部|注销账户", re.I)

PROMPTS = [
    "give me the big table", "show me the long code", "more", "again", "ok",
    "hello", "give me the nested list", "explain", "continue please",
    "one more table", "code please", "short answer please", "hi again",
    "the chinese table now", "headings please", "checklist please",
]

rng = random.Random()
state = {"turns": 0, "crashes": 0, "dialogs": 0, "interrupts": 0, "explor": 0, "started": 0, "deadline": 0}
stop_flag = threading.Event()

def log(msg):
    line = time.strftime("%H:%M:%S") + " " + msg
    print(line, flush=True)
    with open(os.path.join(REP, "driver.log"), "a") as f:
        f.write(time.strftime("%Y-%m-%d ") + line + "\n")

def action(kind, **kw):
    rec = {"ts": time.time(), "kind": kind, **kw}
    with open(ACTIONS, "a") as f:
        f.write(json.dumps(rec, ensure_ascii=False) + "\n")

def sh(*args, timeout=20):
    return subprocess.run([ADB, "-s", SERIAL] + list(args), capture_output=True, text=True, timeout=timeout)

def shok(*args, timeout=20):
    r = sh(*args, timeout=timeout)
    return r.returncode == 0 and "exception" not in (r.stdout + r.stderr).lower()

# ---------- UI ----------
def dump_xml(retries=3):
    for i in range(retries + 1):
        sh("shell", "rm", "-f", "/sdcard/wd.xml")
        sh("shell", "uiautomator", "dump", "/sdcard/wd.xml", timeout=25)
        xml = sh("shell", "cat", "/sdcard/wd.xml").stdout
        if "<hierarchy" in xml:
            try:
                return ET.fromstring(xml)
            except ET.ParseError:
                pass
        time.sleep(1.5)
    return None

def nodes(root, pred=None):
    out = []
    if root is None: return out
    def walk(n):
        if pred is None or pred(n): out.append(n)
        for c in n: walk(c)
    walk(root)
    return out

def center(bounds):
    m = re.findall(r"\d+", bounds)
    if len(m) != 4: return None
    return (int(m[0]) + int(m[2])) // 2, (int(m[1]) + int(m[3])) // 2

def jitter(v, r=6):
    return v + rng.randint(-r, r)

def tap(x, y, r=6):
    sh("shell", "input", "tap", str(jitter(x, r)), str(jitter(y, r)))

def tap_node(n):
    c = center(n.get("bounds", ""))
    if c: tap(*c)

def swipe(x1, y1, x2, y2, ms):
    sh("shell", "input", "swipe", str(jitter(x1, 8)), str(jitter(y1, 8)),
       str(jitter(x2, 8)), str(jitter(y2, 8)), str(ms + rng.randint(-40, 120)))

def type_ascii(text):
    for ch in text:
        if ch == " ": k = "KEYCODE_SPACE"
        elif ch == ",": k = "KEYCODE_COMMA"
        elif ch == ".": k = "KEYCODE_PERIOD"
        elif ch == "/": k = "KEYCODE_SLASH"
        elif ch == "-": k = "KEYCODE_MINUS"
        elif ch.isalnum(): k = "KEYCODE_" + ch.upper()
        else: continue
        sh("shell", "input", "keyevent", k)
        if rng.random() < 0.12: time.sleep(rng.uniform(0.05, 0.22))  # 人类打字节奏抖动

def by_desc(root, desc): return nodes(root, lambda n: (n.get("content-desc") or "") == desc)
def by_text(root, text): return nodes(root, lambda n: (n.get("text") or "") == text)

def screen_state(root):
    if root is None: return "unknown"
    if by_desc(root, "发送") or by_desc(root, "停止"): return "chat"
    if by_desc(root, "切换会话"): return "list"
    if (by_text(root, "取消") or by_text(root, "确定") or by_text(root, "允许")) and not by_desc(root, "切换会话"):
        return "dialog"
    if by_text(root, "设置") and nodes(root, lambda n: n.get("text") == "设置" and n.get("bounds", "0,0").startswith("[")):
        pass
    return "other"

def dismiss_dialogs(root=None):
    root = root or dump_xml()
    if root is None: return False
    canc = by_text(root, "取消") or by_text(root, "不允许") or nodes(root, lambda n: (n.get("text") or "") == "取消")
    if canc:
        tap_node(canc[0]); state["dialogs"] += 1
        action("dialog_cancel"); time.sleep(1.2); return True
    return False

# ---------- 导航 ----------
def entry():
    sh("reverse", f"tcp:{PORT}", f"tcp:{PORT}")
    sh("shell", "am", "force-stop", PKG)
    time.sleep(1.5)
    sh("shell", "am", "start", "-n", ACT,
       "--es", "debug_url", f"http://127.0.0.1:{PORT}",
       "--es", "debug_name", "e2e-v1-4299",
       "--es", "debug_server_type", "opencode")
    for _ in range(15):
        time.sleep(1)
        r = sh("logcat", "-d")
        if "NavGraph: Debug channel → SessionList" in (r.stdout or ""):
            log("entry OK → SessionList")
            return True
    log("entry FAIL（15s 无 SessionList 标志）")
    return False

def goto_list_from_anywhere():
    for _ in range(3):
        root = dump_xml(); st = screen_state(root)
        if st == "list": return True
        if st == "chat":
            sh("shell", "input", "keyevent", "KEYCODE_BACK"); time.sleep(1.5)  # chat→list 合法
        elif st == "dialog":
            dismiss_dialogs(root)
        else:
            # unknown/other：BACK 两次尝试回列表（列表页 BACK 禁令只针对已确认在列表）
            sh("shell", "input", "keyevent", "KEYCODE_BACK"); time.sleep(1.2)
    root = dump_xml()
    if screen_state(root) != "list":
        entry(); time.sleep(2)
    return screen_state(dump_xml()) == "list"

def open_chat(session_idx=None):
    if not goto_list_from_anywhere(): return False
    root = dump_xml()
    rows = by_desc(root, "切换会话")
    if not rows:
        newb = by_desc(root, "新建会话")
        if newb:
            tap_node(newb[0]); time.sleep(2.2); action("new_session_via_open")
            return True
        return False
    row = rows[session_idx % len(rows)] if session_idx is not None else rng.choice(rows)
    tap_node(row); time.sleep(rng.uniform(1.8, 2.8))
    action("open_session", row=rows.index(row), total=len(rows))
    return True

def send_prompt(text):
    root = dump_xml()
    if screen_state(root) != "chat": return False
    inp = nodes(root, lambda n: n.get("class") == "android.widget.EditText")
    if not inp: return False
    tap_node(inp[0]); time.sleep(rng.uniform(0.9, 1.6))
    type_ascii(text)
    time.sleep(rng.uniform(0.3, 0.9))
    btn = by_desc(dump_xml() or ET.Element("x"), "发送")  # IME 位移：重 dump 再定位
    if not btn:
        btn = nodes(dump_xml() or ET.Element("x"), lambda n: (n.get("content-desc") or "") == "发送")
    if not btn:
        log("发送键定位失败（dump 后无发送）"); return False
    tap_node(btn[0])
    state["turns"] += 1
    action("send", prompt=text, turn=state["turns"])
    return True

def busy(root=None):
    root = root or dump_xml()
    return root is not None and bool(by_desc(root, "停止"))

def screenshot(tag):
    try:
        fn = time.strftime("%H%M%S") + "_" + tag + ".png"
        sh("shell", "screencap", "-p", "/sdcard/" + fn)
        sh("pull", "/sdcard/" + fn, os.path.join(EVID, fn))
        sh("shell", "rm", "-f", "/sdcard/" + fn)
        action("screenshot", file=fn)
    except Exception as e:
        log("screenshot fail " + str(e))

# ---------- 流式期间的人类行为 ----------
def during_stream():
    """流进行中随机行为：读历史/回底/乱点/干看着/中断。返回是否中断。"""
    deadline = time.time() + 90
    behaved = False
    while time.time() < deadline:
        root = dump_xml()
        if not busy(root):
            break
        r = rng.random()
        if r < 0.28:  # 向上读历史（关键竞态面：流中读历史→非贴底）
            swipe(600, 900, 600, 1800, rng.randint(220, 420))
            time.sleep(rng.uniform(0.6, 2.2))
            if rng.random() < 0.5: swipe(600, 1000, 600, 1900, rng.randint(150, 300))
            behaved = True
        elif r < 0.42:  # fling 上抛
            sh("shell", "input", "swipe", "600", "800", "560", "2100", "120")
            time.sleep(rng.uniform(0.8, 1.8)); behaved = True
        elif r < 0.55:  # 回底（快速定位）
            bot = by_desc(root, "快速定位")
            if bot: tap_node(bot[0]); time.sleep(rng.uniform(0.8, 1.5))
            behaved = True
        elif r < 0.70:  # 随机安全点击
            exploratory_tap(root); behaved = True
        elif r < 0.80 and time.time() - state["started"] > 120 and rng.random() < 0.5:
            # 中断（停止）——中断竞态面，频率受控
            stp = by_desc(root, "停止")
            if stp:
                tap_node(stp[0]); state["interrupts"] += 1
                action("interrupt_stop"); time.sleep(2)
                return True
        else:
            time.sleep(rng.uniform(1.5, 5.0))  # 干看着
    return False

def exploratory_tap(root):
    """随机点可点击内容（黑名单过滤 + 后备 BACK 恢复）。"""
    root = root or dump_xml()
    if root is None: return
    cand = []
    for n in nodes(root, lambda n: n.get("clickable") == "true" and center(n.get("bounds", ""))):
        t = ((n.get("text") or "") + " " + (n.get("content-desc") or "")).strip()
        if not t: t = "(blank)"
        if BLOCKLIST.search(t): continue
        cand.append((n, t))
    if not cand: return
    n, t = rng.choice(cand)
    tap_node(n)
    state["explor"] += 1
    action("exploratory_tap", target=t[:40])
    time.sleep(rng.uniform(0.8, 2.5))
    root2 = dump_xml()
    if screen_state(root2) == "dialog":
        dismiss_dialogs(root2)
    elif rng.random() < 0.38 or screen_state(root2) == "other":
        sh("shell", "input", "keyevent", "KEYCODE_BACK")
        time.sleep(1.0)

# ---------- 生命周期 ----------
def app_home_away():
    sh("shell", "input", "keyevent", "KEYCODE_HOME")
    action("home")
    time.sleep(rng.uniform(20, 240))
    sh("shell", "am", "start", "-n", ACT)
    time.sleep(rng.uniform(2, 4))
    action("return_from_home")

def cold_restart():
    screenshot("cold_restart_pre")
    action("cold_restart")
    entry()
    time.sleep(2)

def wake_check():
    r = sh("shell", "dumpsys", "window")
    if "mDreamingLockscreen=true" in (r.stdout or "") or "mAwake=false" in (r.stdout or ""):
        sh("shell", "input", "keyevent", "KEYCODE_WAKEUP")
        time.sleep(0.6)
        sh("shell", "input", "swipe", "600", "1800", "600", "700", "150")
        action("wake")
        time.sleep(1.0)

def health():
    if not shok("shell", "echo", "hi", timeout=10):
        log("设备离线，等待重连")
        action("device_offline")
        subprocess.run([ADB, "wait-for-device"], timeout=600)
        sh("reverse", f"tcp:{PORT}", f"tcp:{PORT}")
        return
    r = sh("shell", "pidof", PKG)
    if not r.stdout.strip():
        if state["started"] and time.time() - state["started"] > 60:
            log("app 进程不在——崩溃或被杀，取证并重启")
            screenshot("app_gone")
            crash = sh("logcat", "-d", "-b", "crash").stdout
            with open(os.path.join(EVID, time.strftime("%H%M%S") + "_crash.txt"), "w") as f:
                f.write(crash or "(empty crash buffer)")
            state["crashes"] += 1
            action("crash_restart", n=state["crashes"])
            entry(); time.sleep(2)

def health_loop():
    last_crash_poll = 0
    while not stop_flag.is_set():
        try: health()
        except Exception as e: log("health err " + str(e))
        # crash buffer 轮询（独立于进程存活：ANR/native crash）
        if time.time() - last_crash_poll > 60:
            last_crash_poll = time.time()
            try:
                cb = sh("logcat", "-d", "-b", "crash").stdout
                if cb.strip() and "FATAL" in cb:
                    marker = os.path.join(EVID, ".crash_seen")
                    sig = str(len(cb))
                    old = open(marker).read() if os.path.exists(marker) else ""
                    if sig != old:
                        open(marker, "w").write(sig)
                        screenshot("fatal_present")
                        with open(os.path.join(EVID, time.strftime("%H%M%S") + "_fatal.txt"), "w") as f:
                            f.write(cb[-20000:])
                        log("crash buffer 有 FATAL（已取证）")
            except Exception as e: log("crash poll err " + str(e))
        stop_flag.wait(30)

class LogcatCapture(threading.Thread):
    """连续捕获 + 轮转（宿主侧文件，判读一律离线 grep）。
    --pid 过滤：MIUI 系统刷屏 2MB/s 会 4 分钟转出窗口——只抓 app 进程
    （全部探针 tag 与 AppLogger 同 pid）。每次轮转重解析 pid，崩溃重启自动跟随。"""
    def __init__(self, max_files=60, max_bytes=20_000_000):
        super().__init__(daemon=True); self.max_files = max_files; self.max_bytes = max_bytes
    def run(self):
        seq = 0
        while not stop_flag.is_set():
            pidr = sh("shell", "pidof", PKG).stdout.strip().split()
            if not pidr:
                time.sleep(5); continue
            fn = os.path.join(LOGDIR, f"cap_{seq:03d}.log")
            try:
                p = subprocess.Popen([ADB, "-s", SERIAL, "logcat", "-v", "time", "--pid=" + pidr[0]],
                                     stdout=open(fn, "w"), stderr=subprocess.DEVNULL)
                while not stop_flag.is_set():
                    time.sleep(5)
                    try:
                        if os.path.getsize(fn) > self.max_bytes: break
                    except OSError: break
                    if p.poll() is not None: break
                    # app 重启换 pid → 跟随
                    nowpid = sh("shell", "pidof", PKG).stdout.strip().split()
                    if nowpid and nowpid[0] != pidr[0]: break
                    if not nowpid: continue  # 短暂不在（重启中）继续写旧流
                p.kill()
            except Exception as e:
                log("capture err " + str(e)); time.sleep(5)
            caps = sorted(f for f in os.listdir(LOGDIR) if f.startswith("cap_"))
            while len(caps) > self.max_files:
                os.remove(os.path.join(LOGDIR, caps[0])); caps = caps[1:]
            seq += 1

# ---------- 主行为循环 ----------
def pick_flow():
    r = rng.random()
    if r < 0.46: return "chat"
    if r < 0.60: return "switch"
    if r < 0.70: return "newsession"
    if r < 0.78: return "away"
    if r < 0.84: return "explore"
    if r < 0.90: return "read_history"
    if r < 0.95: return "idle"
    return "coldrestart"

def flow_chat():
    if busy():
        during_stream(); return
    ok = send_prompt(rng.choice(PROMPTS))
    if not ok:  # dump 失败/迷航：一次导航重试
        open_chat()
        ok = send_prompt(rng.choice(PROMPTS))
    if not ok:
        action("send_failed_skip"); return
    interrupted = during_stream()
    # 等收尾（停止→发送 或超时）
    t0 = time.time()
    while time.time() - t0 < 150:
        root = dump_xml()
        if not busy(root):
            # 完结后偶尔读历史再回底（完结滚动竞态面）
            if rng.random() < 0.35:
                swipe(600, 900, 600, 2000, rng.randint(200, 400))
                time.sleep(rng.uniform(1.0, 3.0))
                root2 = dump_xml()
                bot = by_desc(root2, "快速定位")
                if bot and rng.random() < 0.7: tap_node(bot[0])
            break
        time.sleep(3)
    else:
        log("流式 150s 未收尾（疑似卡流）——取证")
        screenshot("stuck_stream")
        root = dump_xml()
        stp = by_desc(root, "停止")
        if stp: tap_node(stp[0])
    if interrupted and rng.random() < 0.3:
        root = dump_xml()
        redo = by_desc(root, "还原")
        if redo:
            tap_node(redo[0]); action("retry_after_interrupt"); time.sleep(2); during_stream()
    time.sleep(rng.uniform(3, 45))  # 人类读回复

def flow_switch():
    open_chat(session_idx=rng.randrange(0, 6))

def flow_newsession():
    if not goto_list_from_anywhere(): return
    root = dump_xml()
    newb = by_desc(root, "新建会话")
    if newb:
        tap_node(newb[0]); time.sleep(rng.uniform(1.8, 2.6))
        action("new_session")
        send_prompt(rng.choice(PROMPTS))
        during_stream()
    time.sleep(rng.uniform(2, 15))

def flow_away():
    if rng.random() < 0.12: cold_restart()
    else: app_home_away()

def flow_explore():
    root = dump_xml()
    st = screen_state(root)
    if st == "dialog": dismiss_dialogs(root); return
    if st not in ("chat", "list"):
        goto_list_from_anywhere(); root = dump_xml()
    for _ in range(rng.randint(1, 3)):
        exploratory_tap(root)
        root = dump_xml()
        time.sleep(rng.uniform(0.5, 2.0))

def flow_read_history():
    root = dump_xml()
    if screen_state(root) != "chat":
        open_chat(); root = dump_xml()
    for _ in range(rng.randint(2, 6)):
        swipe(600, 850, 620, 2050, rng.randint(180, 420))
        time.sleep(rng.uniform(0.7, 2.4))
    root2 = dump_xml()
    bot = by_desc(root2, "快速定位")
    if bot and rng.random() < 0.75: tap_node(bot[0])
    action("read_history")

def flow_idle():
    # 人类离开：2-8 分钟静默；期间保活唤醒（禁改系统设置 → 用输入唤醒）
    dur = rng.uniform(120, 480)
    log(f"idle {dur:.0f}s")
    t0 = time.time()
    while time.time() - t0 < dur and not stop_flag.is_set():
        wake_check()
        time.sleep(45)
    action("idle", secs=round(dur))

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--hours", type=float, default=10.0)
    ap.add_argument("--seed", type=int, default=None)
    args = ap.parse_args()
    if args.seed is not None: rng.seed(args.seed)
    os.makedirs(LOGDIR, exist_ok=True); os.makedirs(EVID, exist_ok=True); os.makedirs(REP, exist_ok=True)
    state["started"] = time.time()
    state["deadline"] = state["started"] + args.hours * 3600
    log(f"driver 启动：{args.hours}h seed={args.seed} pid={os.getpid()}")
    action("driver_start", hours=args.hours, seed=args.seed)

    # 前置自检：设备/服务器/入口
    if not shok("shell", "echo", "hi", timeout=15):
        log("设备不可达，先 wait-for-device"); subprocess.run([ADB, "wait-for-device"], timeout=600)
    sh("reverse", f"tcp:{PORT}", f"tcp:{PORT}")
    if not entry():
        log("入口失败——退出（人工排查）"); sys.exit(2)

    cap = LogcatCapture(); cap.start()
    hl = threading.Thread(target=health_loop, daemon=True); hl.start()

    n = 0
    while time.time() < state["deadline"] and not stop_flag.is_set():
        n += 1
        try:
            wake_check()
            flow = pick_flow()
            action("flow", n=n, flow=flow, elapsed_min=round((time.time() - state["started"]) / 60, 1))
            {"chat": flow_chat, "switch": flow_switch, "newsession": flow_newsession,
             "away": flow_away, "explore": flow_explore, "read_history": flow_read_history,
             "idle": flow_idle, "coldrestart": flow_away}[flow]()
            # 进度心跳（cron 巡检读）
            json.dump({"started": state["started"], "deadline": state["deadline"],
                       "elapsed_h": round((time.time() - state["started"]) / 3600, 2),
                       "loops": n, "turns": state["turns"], "crashes": state["crashes"],
                       "interrupts": state["interrupts"], "exploratory": state["explor"],
                       "dialogs": state["dialogs"], "alive": True, "pid": os.getpid()},
                      open(PROGRESS, "w"))
        except KeyboardInterrupt:
            break
        except Exception as e:
            log(f"flow 异常 {type(e).__name__}: {e}")
            screenshot("flow_exc")
            try: goto_list_from_anywhere()
            except Exception: entry()
            time.sleep(5)

    stop_flag.set()
    json.dump({**{k: state[k] for k in state}, "alive": False, "ended": time.time()},
              open(PROGRESS, "w"))
    action("driver_end", turns=state["turns"], crashes=state["crashes"])
    log(f"driver 结束：turns={state['turns']} crashes={state['crashes']} "
        f"interrupts={state['interrupts']} exploratory={state['explor']}")

if __name__ == "__main__":
    main()
