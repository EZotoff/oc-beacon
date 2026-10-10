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
SERIAL = "adb-e69a99d8-yzT17Y._adb-tls-connect._tcp"  # B2: 无线 mDNS 名（2026-10-09；闪断则 driver 自退）
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
    r"附件|attachment|安全访问|文件选择|选择文件"
    r"导出|导入|备份|还原全部|注销账户", re.I)

# debug 通道服务器类型(driver-529 三端腿参数化覆写;默认 v1 语义不变)
DEBUG_SERVER_TYPE = "opencode"
# Basic Auth 凭据(v1 免密时空串=不传 extras,10h/5h soak 语义不变;v2 腿需要)
DEBUG_USERNAME = ""
DEBUG_PASSWORD = ""

PROMPTS = [
    "give me the big table", "show me the long code", "more", "again", "ok",
    "hello", "give me the nested list", "explain", "continue please",
    "one more table", "code please", "short answer please", "hi again",
    "the chinese table now", "headings please", "checklist please",
]

rng = random.Random()
state = {"turns": 0, "crashes": 0, "dialogs": 0, "interrupts": 0, "explor": 0, "started": 0, "deadline": 0}
stop_flag = threading.Event()

# ================== B2 狩猎三件套（2026-10-09，#535） ==================
# #454 换行注入循环 / #464 tap 效果断言+现场抓取 / #477 盲 dump 检测器。
# 只加不改基线语义；全部「捕获供人工复核」，不自动判死活。
HUNT = {"nl_inject": 0, "nl_miss": 0, "tap_total": 0, "tap_susp": 0,
        "blind_checks": 0, "blind_suspect": 0}

def _newest_caps(n=2):
    caps = sorted(f for f in os.listdir(LOGDIR) if f.startswith("cap_"))
    return [os.path.join(LOGDIR, f) for f in caps[-n:]]

def count_sent_prompt():
    """宿主侧 grep（纪律：判读一律离线）——统计 sendPrompt 成功行数。"""
    total = 0
    for f in _newest_caps():
        try:
            with open(f, "r", errors="ignore") as fh:
                total += sum(1 for line in fh if "Sent prompt to session" in line)
        except OSError:
            pass
    return total

def hunt_evidence(tag, extra_logs=True):
    fn = time.strftime("%H%M%S") + "_h_" + tag
    screenshot(tag)
    try:
        xml = sh("shell", "cat", "/sdcard/wd.xml").stdout
        open(os.path.join(EVID, fn + ".xml"), "w").write(xml)
    except Exception:
        pass
    if extra_logs:
        for cmd, name in ((("shell", "dumpsys", "input"), "dumpsys_input.txt"),
                          (("shell", "dumpsys", "meminfo", PKG), "meminfo.txt"),
                          (("shell", "dumpsys", "accessibility"), "dumpsys_a11y.txt"),
                          (("shell", "uptime"), "uptime.txt")):
            try:
                open(os.path.join(EVID, fn + "_" + name), "w").write(
                    sh(*cmd, timeout=20).stdout[:2_000_000])
            except Exception:
                pass

def hunt_logcat_tail(tag, lines=400):
    try:
        f = _newest_caps(1)[0]
        with open(f, "r", errors="ignore") as fh:
            tail = fh.readlines()[-lines:]
        open(os.path.join(EVID, time.strftime("%H%M%S") + "_h_" + tag + "_logcat.txt"), "w").writelines(tail)
    except Exception:
        pass

def user_bubble_count(text_eq):
    """屏上 text 恰等于 text_eq 的节点数（v1 悲观消息：POST 成功+SSE 回显后上屏）。"""
    root = dump_xml()
    if root is None: return -1
    return len(nodes(root, lambda n: (n.get("text") or "").strip() == text_eq))

def flow_nl_inject():
    """#454：Run+两次 keyevent 66 注入换行 → 发送 → 断言「上屏回显 or POST 计数」
    双通道任一（2026-10-09 判别链教训：POST grep 通道在 LogcatCapture 缺席的
    one-shot 场景假阴性；上屏回显=悲观消息模式的最强用户可见信号）。
    态守卫：仅 desc=发送（idle）时执行——停止态=忙/错误重试轮（wire-probe 会话
    模型残留选择实证：1m25s 错误窗会吞掉注入窗口造成 miss 假象）。"""
    if not open_chat():
        return
    root = dump_xml()
    if screen_state(root) != "chat":
        return
    btn0 = by_desc(root, "\u53d1\u9001")
    if not btn0:
        action("h454_skip_state"); return
    pre = count_sent_prompt()
    pre_bubble = user_bubble_count("run")
    inp = nodes(root, lambda n: n.get("class") == "android.widget.EditText")
    if not inp:
        return
    tap_node(inp[0]); time.sleep(rng.uniform(0.9, 1.4))
    type_ascii("Run")
    time.sleep(0.3)
    sh("shell", "input", "keyevent", "66"); time.sleep(0.3)
    sh("shell", "input", "keyevent", "66"); time.sleep(rng.uniform(0.6, 1.2))
    # 输入域仍含内容（换行已注入）→ 发送；IME 关闭动画防伪影：每次点击前
    # 重 dump 取稳态坐标 + 最多 3 次稳态重试（首次短验证 2/2 MISS 的竞争解释
    # 之一=动画中段坐标陈旧 tap 落空——三次稳态全失败才定罪，2026-10-09）
    HUNT["nl_inject"] += 1
    ok = False
    for attempt in range(3):
        root2 = dump_xml()
        if by_desc(root2 or ET.Element("x"), "\u505c\u6b62"):  # 停止=忙窗竞态（上一轮错误重试/流式中）→ 跳过本轮
            action("h454_skip_busy")
            return
        field = nodes(root2 or ET.Element("x"), lambda n: n.get("class") == "android.widget.EditText")
        if not field or not (field[0].get("text") or "").strip():
            time.sleep(1.5)  # 已清空=可能已发出，落入下方计数判定
        else:
            btn = by_desc(root2, "\u53d1\u9001")  # 发送
            if btn:
                tap_node(btn[0])
        t0 = time.time()
        while time.time() - t0 < 8:
            if count_sent_prompt() > pre or user_bubble_count("run") > pre_bubble:
                ok = True; break
            time.sleep(1.5)
        if ok:
            break
        action("h454_attempt", n=attempt + 1)
    if ok:
        action("h454_pass"); during_stream()
    else:
        HUNT["nl_miss"] += 1
        action("h454_miss", pre=pre, now=count_sent_prompt())
        log("H454 MISS: prompt POST \u672a\u53d1\u51fa\uff08\u6ce8\u5165\u6362\u884c\u540e\uff09")
        hunt_evidence("454miss"); hunt_logcat_tail("454miss")
    time.sleep(rng.uniform(3, 20))

def _dump_signature(root):
    if root is None: return ""
    parts = []
    for n in nodes(root):
        t = ((n.get("text") or "") + "|" + (n.get("content-desc") or "")).strip()
        if t and t != "|":
            parts.append(t)
    return "\n".join(parts)[:20000]

def exploratory_tap_hunted(root):
    """#464：可点击目标 tap 后效果断言——前后 dump 签名全同+无对话框+目标是显式
    动作件（Button/带文案）→ 捕获现场（dumpsys input 看 InputDispatcher 消费）。
    幂等/无视觉反馈的合法 no-op 会少量误捕，上限 10 次防刷屏，人工复核定罪。"""
    root = root or dump_xml()
    if root is None: return
    cand = []
    for n in nodes(root, lambda n: n.get("clickable") == "true" and center(n.get("bounds", ""))):
        t = ((n.get("text") or "") + " " + (n.get("content-desc") or "")).strip()
        if not t: t = "(blank)"
        if BLOCKLIST.search(t): continue
        c = center(n.get("bounds", ""))
        if c and c[1] > 2280: continue
        cand.append((n, t))
    if not cand: return
    n, t = rng.choice(cand)
    cls = n.get("class") or ""
    actionable = ("Button" in cls) or (t != "(blank)")
    pre_sig = _dump_signature(root)
    tap_node(n)
    state["explor"] += 1
    HUNT["tap_total"] += 1
    action("exploratory_tap", target=t[:40])
    time.sleep(rng.uniform(0.8, 2.0))
    root2 = dump_xml()
    if screen_state(root2) == "dialog":
        dismiss_dialogs(root2)
        return
    if (actionable and root2 is not None and HUNT["tap_susp"] < 10
            and _dump_signature(root2) == pre_sig):
        HUNT["tap_susp"] += 1
        action("h464_suspicious_tap", target=t[:40], cls=cls[-30:])
        log("H464 SUSP: tap \u96f6\u6548\u679c " + t[:40])
        hunt_evidence("464tap"); hunt_logcat_tail("464tap", 200)

def hunt_blind_check():
    """#477：聊天屏 dump 总文本量塌到近零（<200 字符）→ 盲 dump 疑似——
    截图像素通道+dumpsys accessibility 现场抓取。仅在 chat 屏且刚完成一轮回复
    后调用（此时健康 dump 恒有大量文本节点）。"""
    root = dump_xml()
    if root is None or screen_state(root) != "chat":
        return
    total = sum(len((n.get("text") or "").strip()) for n in nodes(root))
    HUNT["blind_checks"] += 1
    if total < 200:
        HUNT["blind_suspect"] += 1
        action("h477_blind_suspect", dump_chars=total, uptime_min=round(
            (time.time() - state["started"]) / 60, 1))
        log("H477 BLIND SUSPECT: dump total text=" + str(total))
        hunt_evidence("477blind", extra_logs=True)

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
    # 2026-10-10 加固：无线 adb 闪断时 subprocess 超时（02:47 driver 死因——
    # entry() 恢复流内 reverse 挂死 20s，TimeoutExpired 裸抛炸掉 driver）。
    # 设备定向命令超时不再裸抛：先自愈重连一次再重试；仍败则抛给调用方既有
    # except Exception 链。host 侧命令（mdns/disconnect/connect）不经此路径。
    try:
        return subprocess.run([ADB, "-s", SERIAL] + list(args), capture_output=True, text=True, timeout=timeout)
    except subprocess.TimeoutExpired:
        log(f"sh 超时自愈: {' '.join(args[:3])}")
        if heal_adb():
            return subprocess.run([ADB, "-s", SERIAL] + list(args), capture_output=True, text=True, timeout=timeout)
        raise


def heal_adb(cycles=3):
    """无线闪断自愈：断开全部 TCP 设备 → mDNS 重发现目标 → 重连。
    SERIAL 为 mDNS 自动连接形态（adb-e69a99d8-…._adb-tls-connect._tcp），
    端口随手机无线调试会话轮换——必须重发现而非重连旧口。
    成功后 SERIAL 更新为 host:port 形态（后续 -s 全走新名）。模拟器为本地
    设备不受 disconnect 影响；escalate kill-server 留人工（kill 会连带
    模拟器重连，代价大于收益）。"""
    global SERIAL
    token = SERIAL.split("._adb-tls")[0].replace("adb-", "")  # e69a99d8
    for cycle in range(cycles):
        subprocess.run([ADB, "disconnect"], capture_output=True, text=True, timeout=10)
        try:
            out = subprocess.run([ADB, "mdns", "services"], capture_output=True, text=True, timeout=15).stdout or ""
        except Exception:
            out = ""
        for line in out.splitlines():
            parts = line.split()
            if len(parts) >= 3 and parts[0] == f"adb-{token}":
                hostport = parts[2]
                r = subprocess.run([ADB, "connect", hostport], capture_output=True, text=True, timeout=20)
                if "connected" in (r.stdout or "") or "already" in (r.stdout or ""):
                    SERIAL = hostport
                    log(f"heal_adb 成功 cycle={cycle} 新 SERIAL={SERIAL}")
                    time.sleep(2)
                    return True
        log(f"heal_adb cycle={cycle} 未发现 {token}（手机休眠/无线调试关闭）——30s 后重试")
        time.sleep(30)
    log("heal_adb 放弃：需人工（唤醒手机/无线调试/USB）")
    return False

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

def foreign_top() -> str:
    """宿主侧解析顶层 Activity：非本包/非桌面 即返回其组件名（空串=正常）。"""
    try:
        r = sh("shell", "dumpsys", "activity", "activities", timeout=15)
        m = re.search(r"topResumedActivity=ActivityRecord\{[^}]* u0 (\S+)", r.stdout or "")
        top = m.group(1) if m else ""
    except Exception:
        return ""
    if not top or PKG in top or "launcher" in top.lower() or "miui.home" in top:
        return ""
    return top

def escape_foreign_if_any():
    """外来顶层（文件选择器等）→ BACK 关闭 + 重启拉回；成功返回 True。"""
    top = foreign_top()
    if not top: return False
    log("外来顶层 Activity：" + top + "——执行逃脱")
    action("escape_foreign", top=top[:60])
    for _ in range(2):
        sh("shell", "input", "keyevent", "KEYCODE_BACK"); time.sleep(1.2)
        if not foreign_top(): return True
    sh("shell", "am", "start", "-n", ACT); time.sleep(2.5)
    if not foreign_top() and screen_state(dump_xml()) in ("chat", "list"):
        return True
    return False

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
    for attempt in range(3):
        try:
            _entry_once()
            return
        except Exception as e:
            log(f"entry 失败({attempt}) {type(e).__name__}: {e}——heal 后重试")
            if not heal_adb():
                raise
            time.sleep(3)


def _entry_once():
    sh("reverse", f"tcp:{PORT}", f"tcp:{PORT}")
    sh("shell", "am", "force-stop", PKG)
    sh("logcat", "-c")
    time.sleep(1.5)
    sh("shell", "am", "start", "-n", ACT,
       "--es", "debug_url", f"http://127.0.0.1:{PORT}",
       "--es", "debug_name", "e2e-v1-4299",
       "--es", "debug_server_type", DEBUG_SERVER_TYPE,
       *(["--es", "debug_username", DEBUG_USERNAME] if DEBUG_USERNAME else []),
       *(["--es", "debug_password", DEBUG_PASSWORD] if DEBUG_PASSWORD else []))
    def marker_or_ui_ok():
        r = sh("logcat", "-d")
        if "NavGraph: Debug channel → SessionList" in (r.stdout or ""):
            return True
        return screen_state(dump_xml()) in ("chat", "list")
    for _ in range(15):
        time.sleep(1)
        if marker_or_ui_ok():
            log("entry OK → SessionList")
            return True
    # 逃脱序列：外来 Activity（MIUI 文件选择器等）可能占住本 task 顶层，
    # am start 的 cold start 被 transition.abort 吞掉——BACK 关它后重启一次
    for _ in range(2):
        sh("shell", "input", "keyevent", "KEYCODE_BACK"); time.sleep(1.2)
    sh("shell", "am", "start", "-n", ACT,
       "--es", "debug_url", f"http://127.0.0.1:{PORT}",
       "--es", "debug_name", "e2e-v1-4299",
       "--es", "debug_server_type", DEBUG_SERVER_TYPE,
       *(["--es", "debug_username", DEBUG_USERNAME] if DEBUG_USERNAME else []),
       *(["--es", "debug_password", DEBUG_PASSWORD] if DEBUG_PASSWORD else []))
    for _ in range(10):
        time.sleep(1)
        if marker_or_ui_ok():
            log("entry OK（逃脱外来 Activity 后）")
            return True
    sh("shell", "input", "keyevent", "KEYCODE_HOME"); time.sleep(1)
    sh("shell", "am", "start", "-n", ACT)
    for _ in range(8):
        time.sleep(1)
        if screen_state(dump_xml()) in ("chat", "list"):
            log("entry OK（HOME 逃生后）")
            return True
    log("entry FAIL（逃脱序列耗尽——外来 Activity 占屏）")
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
        # 输入栏禁区（y>2280）：附件外层容器无 desc 走不进文本黑名单（16:07 陷阱实证），
        # 该区只允许动作路径按精确 desc 定位点击
        c = center(n.get("bounds", ""))
        if c and c[1] > 2280: continue
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
        # 假阴性双检（无障碍子系统被 dump 风暴打挂时 pidof 会空返——16:07 实证）：
        # 间隔 10s 两次皆空 且 顶层不是外来 Activity 才判死；外来顶层走逃脱
        time.sleep(10)
        r2 = sh("shell", "pidof", PKG)
        if r2.stdout.strip(): return
        if foreign_top():
            escape_foreign_if_any(); return
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
    if r < 0.93: return "idle"
    if r < 0.97: return "nl_inject"  # B2 狩猎：#454 换行注入循环（4%）
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
    hunt_blind_check()
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
        exploratory_tap_hunted(root)
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
            escape_foreign_if_any()
            flow = pick_flow()
            action("flow", n=n, flow=flow, elapsed_min=round((time.time() - state["started"]) / 60, 1))
            {"chat": flow_chat, "switch": flow_switch, "newsession": flow_newsession,
             "away": flow_away, "explore": flow_explore, "read_history": flow_read_history,
             "idle": flow_idle, "coldrestart": flow_away,
             "nl_inject": flow_nl_inject}[flow]()
            # 进度心跳（cron 巡检读）
            json.dump({"started": state["started"], "deadline": state["deadline"],
                       "elapsed_h": round((time.time() - state["started"]) / 3600, 2),
                       "loops": n, "turns": state["turns"], "crashes": state["crashes"],
                       "interrupts": state["interrupts"], "exploratory": state["explor"],
                       "dialogs": state["dialogs"], "alive": True, "pid": os.getpid(), "hunt": dict(HUNT)},
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
    log("B2 狩猎汇总: " + json.dumps(HUNT))
    json.dump(HUNT, open(os.path.join(REP, "hunt_report.json"), "w"))

if __name__ == "__main__":
    main()
