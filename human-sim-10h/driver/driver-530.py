#!/usr/bin/env python3
# driver-530.py — #530 专项 soak：深链叠栈弹回 + 黑屏挂起复现与自动取证
#
# 复用 human-sim-10h/driver/driver.py 基建（护栏/动作台账/logcat 轮转思想）。
# 静态调研（docs/research/2026-10-08-530-nav-reset-blackscreen-static.md）+
# 矩阵实验结论驱动的行为设计：
#   · 形态权重围绕当晚序列：深链（无action/action/action+SINGLE_TOP）×前台/后台、
#     IME keyevent 输入、Chat 进出、不可达窗（reverse 断/复）、周期栈复位
#   · 探针每 5s：屏幕亮度（黑屏判定 3 连续 <10/255）、MainActivity 实例计数、
#     mCurrentFocus、帧计数增量
#   · 黑屏自动处置：轻量四件套取证（logcat 标记/screencap/dumpsys window+activity/
#     gfxinfo）→ force-stop 复位 → 续跑；弹回事件记 nav_reset
import os, sys, json, time, random, threading, subprocess, re

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import driver as d  # noqa: E402

BASE530 = os.path.join(d.BASE, "reports", "530")
LOGS530 = os.path.join(d.BASE, "logs530")
EVID530 = os.path.join(d.BASE, "evidence", "530")
for p in (BASE530, LOGS530, EVID530):
    os.makedirs(p, exist_ok=True)
d.ACTIONS = os.path.join(BASE530, "actions.jsonl")
REP530 = BASE530

PKG = "dev.leonardo.ocbeacon.dev"
ACT = PKG + "/dev.leonardo.ocbeacon.MainActivity"
OPEN_SESSION = "dev.leonardo.ocbeacon.OPEN_SESSION"
SERVER_ID = os.environ.get("OCBEACON_SERVER_ID", "dcccb037-9fc8-4077-9126-196c0f272ba2")
MINUTES = float(os.environ.get("OCBEACON_MINUTES", "30"))

rng = random.Random()
st = {"cycles": 0, "deep_noaction": 0, "deep_action": 0, "deep_singletop": 0,
      "deep_bg": 0, "ime": 0, "sends": 0, "unreachable": 0, "stack_reset": 0,
      "nav_reset": 0, "blackcreens": 0, "recoveries": 0, "probe_err": 0}

def log(msg):
    line = time.strftime("%H:%M:%S") + " " + msg
    print(line, flush=True)
    with open(os.path.join(REP530, "driver.log"), "a") as f:
        f.write(time.strftime("%Y-%m-%d ") + line + "\n")

def action(kind, **kw):
    with open(d.ACTIONS, "a") as f:
        f.write(json.dumps({"ts": time.time(), "kind": kind, **kw}, ensure_ascii=False) + "\n")

# ---------- serial 动态解析（mDNS 无线：断连重发现，条目名带空格） ----------
def pick_serial():
    r = subprocess.run([d.ADB, "devices"], capture_output=True, text=True, timeout=15)
    for line in r.stdout.splitlines():
        parts = line.split("\t")
        if len(parts) == 2 and "e69a99d8" in parts[0] and parts[1].strip() == "device":
            return parts[0]
    return None

def sh(*args, timeout=25):
    ser = pick_serial()
    if ser is None:
        return subprocess.CompletedProcess(args, 1, "", "no device")
    d.SERIAL = ser
    return subprocess.run([d.ADB, "-s", ser] + list(args), capture_output=True, text=True, timeout=timeout)

d.sh = sh  # 基建内所有 adb 调用走动态 serial

# ---------- 观测原语 ----------
def instance_count():
    r = sh("shell", "dumpsys", "activity", "activities", timeout=20)
    return r.stdout.count("cmp=" + ACT)

def focus_str():
    r = sh("shell", "dumpsys", "window", "windows", timeout=20)
    m = re.search(r"mCurrentFocus=Window\{[^}]*\s+(\S+)", r.stdout or "")
    return m.group(1) if m else "?"

def brightness():
    r = sh("shell", "screencap", "-p", "/sdcard/_b530.png", timeout=20)
    if r.returncode != 0:
        return None
    r2 = sh("shell", "ls", "-la", "/sdcard/_b530.png", timeout=10)
    if r2.returncode != 0:
        return None
    # 拉回本地分析（避免设备端 python 缺失）
    lp = "/tmp/_b530.png"
    subprocess.run([d.ADB, "-s", pick_serial() or "", "pull", "/sdcard/_b530.png", lp],
                   capture_output=True, timeout=25)
    try:
        from PIL import Image
        img = Image.open(lp).convert("L")
        w, h = img.size
        px = [img.getpixel((x, y)) for x in range(0, w, 100) for y in range(0, h, 100)]
        return sum(px) / len(px)
    except Exception:
        return None

def screen_tag():
    """粗屏态：黑/Chat/列表/Home（dump 太重，这里用亮度+焦点即可粗分；细化在事件时 dump）"""
    return None

def dump_state():
    r = sh("shell", "uiautomator", "dump", "/sdcard/_u530.xml", timeout=25)
    r2 = sh("shell", "cat", "/sdcard/_u530.xml", timeout=15)
    return r2.stdout or ""

def classify_ui(xml):
    if "添加服务器" in xml: return "home_servers"
    if "切换会话" in xml: return "session_list"
    if "Big Pickle" in xml or "提问" in xml or "帮我处理" in xml: return "chat"
    if "新建会话" in xml: return "session_list"
    return "other"

# ---------- 行为原语 ----------
def am_start_deep(variant):
    """variant: noaction | action | singletop | action_session"""
    base = ["shell", "am", "start", "-W", "-n", ACT]
    if variant == "noaction":
        cmd = base + ["--es", "debug_url", "http://127.0.0.1:4199",
                      "--es", "debug_username", "opencode", "--es", "debug_name", "Host-4199"]
    elif variant == "action":
        cmd = ["shell", "am", "start", "-W", "-n", ACT, "-a", OPEN_SESSION,
               "--es", "server_id", SERVER_ID]
    elif variant == "action_session":
        cmd = ["shell", "am", "start", "-W", "-n", ACT, "-a", OPEN_SESSION,
               "--es", "server_id", SERVER_ID,
               "--es", "session_path", "/home/leonardo/文档/code/mine/oc-beacon"]
    else:  # singletop
        cmd = ["shell", "am", "start", "-W", "-n", ACT, "-a", OPEN_SESSION,
               "--es", "server_id", SERVER_ID, "-f", "0x20000000"]
    r = sh(*cmd, timeout=30)
    return r.stdout or r.stderr

def entry_coldstart():
    """debug-entry 语义冷启（logcat 先清再全量判——SSE 恢复日志会冲掉 -t 窗口头）"""
    sh("reverse", "tcp:4199", "tcp:4199")
    sh("shell", "logcat", "-c")
    sh("shell", "am", "force-stop", PKG)
    time.sleep(1)
    am_start_deep("noaction")
    for _ in range(15):
        time.sleep(1)
        r = sh("shell", "logcat", "-d", timeout=25)
        if "NavGraph: Debug channel → SessionList" in (r.stdout or ""):
            return True
    return False

def go_home_bg():
    sh("shell", "input", "keyevent", "HOME")
    time.sleep(1.5)

def open_chat_by_tap():
    xml = dump_state()
    if classify_ui(xml) != "session_list":
        return False
    m = re.search(r'text="New session - 2026-09-30T18:14:34.415Z"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml)
    if not m:
        return False
    x = (int(m.group(1)) + int(m.group(3))) // 2
    y = (int(m.group(2)) + int(m.group(4))) // 2
    sh("shell", "input", "tap", str(x), str(y))
    time.sleep(3)
    xml2 = dump_state()
    return classify_ui(xml2) == "chat"

def ime_type_backspace_seq():
    """聚焦输入框 → keyevent 序列（数字/字母/退格混合）→ BACK 收键盘（不发送）"""
    xml = dump_state()
    m = re.search(r'text="([^"]*提问[^"]*)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml)
    if not m:
        m = re.search(r'text="([^"]*帮我处理[^"]*)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml)
    if not m:
        return False
    x = (int(m.group(2)) + int(m.group(4))) // 2
    y = (int(m.group(3)) + int(m.group(5))) // 2
    sh("shell", "input", "tap", str(x), str(y))
    time.sleep(1.8)
    for k in rng.sample(["7", "8", "9", "4", "5", "6", "SPACE", "DEL", "DEL", "H", "E", "L"], 8):
        sh("shell", "input", "keyevent", "KEYCODE_" + k)
        time.sleep(rng.uniform(0.15, 0.5))
    time.sleep(0.6)
    sh("shell", "input", "keyevent", "BACK")  # 收键盘
    time.sleep(1.2)
    return True

def clear_overstack():
    """栈叠过高时复位：BACK×6 + 必要时冷启"""
    global last_entry_ok
    n = instance_count()
    if n >= 3:
        for _ in range(min(n, 5)):
            sh("shell", "input", "keyevent", "BACK")
            time.sleep(1.2)
        st["stack_reset"] += 1
        action("stack_reset", before=n, after=instance_count())
        if classify_ui(dump_state()) == "other":
            entry_coldstart()

# ---------- 探针线程：黑屏判定 + 自动取证 ----------
probe_stop = threading.Event()
black_streak = 0

def forensic(tag):
    """黑屏轻量四件套取证"""
    ts = time.strftime("%H%M%S")
    os.makedirs(EVID530, exist_ok=True)
    sh("shell", "screencap", "-p", "/sdcard/_blk530.png")
    subprocess.run([d.ADB, "-s", pick_serial() or "", "pull", "/sdcard/_blk530.png",
                    os.path.join(EVID530, f"black_{tag}_{ts}.png")], capture_output=True, timeout=30)
    for name, args in (("window", ["shell", "dumpsys", "window", "windows"]),
                       ("activity", ["shell", "dumpsys", "activity", "activities"]),
                       ("gfx", ["shell", "dumpsys", "gfxinfo", PKG])):
        r = sh(*args, timeout=30)
        with open(os.path.join(EVID530, f"black_{tag}_{ts}_{name}.txt"), "w") as f:
            f.write(r.stdout or r.stderr)

def probe_loop():
    global black_streak
    while not probe_stop.is_set():
        try:
            b = brightness()
            n = instance_count()
            foc = focus_str()
            if b is not None:
                with open(os.path.join(REP530, "probe.log"), "a") as f:
                    f.write(json.dumps({"ts": time.time(), "b": round(b, 1),
                                        "inst": n, "focus": foc}) + "\n")
                if b < 10.0:
                    black_streak += 1
                    if black_streak == 3:  # ~15s 持续黑
                        st["blackcreens"] += 1
                        log(f"BLACKSCREEN detected inst={n} focus={foc} — 取证")
                        action("blackscreen", inst=n, focus=foc)
                        forensic(time.strftime("%H%M%S"))
                        sh("shell", "am", "force-stop", PKG)
                        time.sleep(2)
                        entry_coldstart()
                        st["recoveries"] += 1
                        black_streak = 0
                else:
                    black_streak = 0
        except Exception as e:
            st["probe_err"] += 1
            with open(os.path.join(REP530, "probe_err.log"), "a") as f:
                f.write(f"{time.time()} {e!r}\n")
        probe_stop.wait(5)

# ---------- 主循环 ----------
def main():
    deadline = time.time() + MINUTES * 60
    threading.Thread(target=probe_loop, daemon=True).start()
    log(f"driver-530 start minutes={MINUTES} server={SERVER_ID}")
    ok = entry_coldstart()
    log(f"基线冷启 {'OK' if ok else 'FAIL'}")
    if not ok:
        log("基线失败：检查 v1:4199/reverse")
        return
    weights = [("deep_action", 3), ("deep_action_session", 2), ("deep_singletop", 2),
               ("deep_noaction", 2), ("ime_session", 3), ("bg_deep", 2),
               ("unreachable", 1), ("back_mix", 2)]
    names = [w[0] for w in weights]
    wts = [w[1] for w in weights]
    while time.time() < deadline:
        st["cycles"] += 1
        act = rng.choices(names, weights=wts)[0]
        try:
            if act == "deep_action":
                before = instance_count()
                am_start_deep("action")
                st["deep_action"] += 1
                time.sleep(3)
                after = instance_count()
                ui = classify_ui(dump_state())
                # 弹回判定：新实例盖顶使 Chat 消失进列表/Home
                action(act, before=before, after=after, ui=ui)
                if after > before:
                    st["nav_reset"] += 1
                    log(f"nav_reset: {before}->{after} inst ui={ui}")
            elif act == "deep_action_session":
                before = instance_count()
                am_start_deep("action_session")
                st["deep_action_session"] = st.get("deep_action_session", 0) + 1
                time.sleep(3)
                after = instance_count()
                ui = classify_ui(dump_state())
                action(act, before=before, after=after, ui=ui)
                if after > before:
                    st["nav_reset"] += 1
                    log(f"nav_reset: {before}->{after} inst ui={ui}")
            elif act == "deep_singletop":
                before = instance_count()
                am_start_deep("singletop")
                st["deep_singletop"] += 1
                time.sleep(2.5)
                action(act, before=before, after=instance_count())
            elif act == "deep_noaction":
                before = instance_count()
                am_start_deep("noaction")
                st["deep_noaction"] += 1
                time.sleep(2)
                action(act, before=before, after=instance_count())
            elif act == "ime_session":
                if open_chat_by_tap():
                    ime_type_backspace_seq()
                    st["ime"] += 1
                    action("ime_session", ok=True)
                else:
                    entry_coldstart()
                    action("ime_session", ok=False, fallback="coldstart")
            elif act == "bg_deep":
                go_home_bg()
                time.sleep(rng.uniform(2, 6))
                before = instance_count()
                am_start_deep("action")
                st["deep_bg"] += 1
                time.sleep(3)
                after = instance_count()
                ui = classify_ui(dump_state())
                action("bg_deep", before=before, after=after, ui=ui)
                if after > before:
                    st["nav_reset"] += 1
            elif act == "unreachable":
                sh("reverse", "--remove", "tcp:4199")
                time.sleep(rng.uniform(8, 15))
                sh("reverse", "tcp:4199", "tcp:4199")
                st["unreachable"] += 1
                action("unreachable")
                time.sleep(3)
            elif act == "back_mix":
                for _ in range(rng.randint(1, 3)):
                    sh("shell", "input", "keyevent", "BACK")
                    time.sleep(rng.uniform(0.8, 1.8))
                st["back_mix"] = st.get("back_mix", 0) + 1
                ui = classify_ui(dump_state())
                action("back_mix", ui=ui)
            clear_overstack()
        except Exception as e:
            log(f"cycle err: {e!r}")
            action("cycle_err", err=repr(e))
            time.sleep(3)
        time.sleep(rng.uniform(2, 6))
    probe_stop.set()
    log(f"end stats={json.dumps(st)}")

if __name__ == "__main__":
    main()
