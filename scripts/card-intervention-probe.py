#!/usr/bin/env python3
"""run-card-probe.py — 增长期卡片介入取证主控 v2（~/card-lab 持久版）
用法: python3 run-card-probe.py full
"""
import json, os, re, subprocess, sys, threading, time, urllib.request
ADB = os.path.expanduser("~/android-platform-tools/platform-tools/adb")
SERIAL = "adb-e69a99d8-yzT17Y (2)._adb-tls-connect._tcp"
PKG = "dev.leonardo.ocbeacon.dev"
V1 = "http://127.0.0.1:4199"
ENV = dict(os.environ, ANDROID_ADB_SERVER_PORT="5038")
LAB = os.path.expanduser("~/card-lab")
def sh(*args, timeout=60):
    return subprocess.run([ADB, "-s", SERIAL, *args], capture_output=True, text=True, timeout=timeout, env=ENV)
def api(method, path, body=None, timeout=200):
    req = urllib.request.Request(V1 + path, method=method,
        data=json.dumps(body).encode() if body else None,
        headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.loads(r.read() or b"null")
def logcat_grep(pattern):
    r = sh("logcat", "-d", "-v", "time", timeout=90)
    return [l for l in (r.stdout or "").splitlines() if re.search(pattern, l)]
class GrowthWatcher(threading.Thread):
    def __init__(self, n=4):
        super().__init__(daemon=True)
        self.n = n; self.triggered = threading.Event()
        self.last_h = {}; self.streak = 0; self.proc = None
    def run(self):
        self.proc = subprocess.Popen([ADB, "-s", SERIAL, "logcat", "-v", "time"],
            stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True, env=ENV)
        for line in self.proc.stdout:
            m = re.search(r"ScrollDiag.*RESIZE t=\d+ key=(t_msg\S*) h (\d+)->(\d+)", line)
            if not m: continue
            key, old, new = m.group(1), int(m.group(2)), int(m.group(3))
            if new > old and new > self.last_h.get(key, 0):
                self.streak += 1; self.last_h[key] = new
                if self.streak >= self.n: self.triggered.set()
            else: self.last_h[key] = new
    def stop(self):
        if self.proc: self.proc.terminate()
def drift_state():
    for l in reversed(logcat_grep(r"DEBUG-drift")):
        m = re.search(r"atBot=(\w+)", l)
        d = {"atBot": m.group(1) if m else "?"}
        for k, pat in [("autoOn", r"autoOn=(\w+)"), ("idx", r"idx=(\d+)"), ("off", r"off=(-?\d+)")]:
            mm = re.search(pat, l)
            if mm: d[k] = mm.group(1)
        if m or "autoOn" in d: return d
    return None
def tap(pattern, desc=False, last=False):
    cmd = ["python3", LAB + "/tap-text-phone.py", pattern] + (["--desc"] if desc else []) + (["--last"] if last else [])
    r = subprocess.run(cmd, capture_output=True, text=True, env=dict(ENV, SERIAL=SERIAL))
    out = (r.stdout or "").strip()
    print("   tap[%s] -> %s" % (pattern, out))
    return out.startswith("tapped")
PROMPT = ("请详细介绍计算机网络中 TCP 拥塞控制的全貌：慢启动、拥塞避免、快速重传、快速恢复，"
          "以及 BBR 与传统基于丢失的算法的对比。要求分多个小节、每节多段展开，总长度越长越好。")
def dump_logs(tag):
    lines = logcat_grep(r"CardExpand|RESERVE|SGR-435|DEBUG-drift|ScrollDiag.*RESIZE|RB-EXP|LEAP")
    p = "%s/card-full-%s.log" % (LAB, tag)
    with open(p, "w") as f: f.write("\n".join(lines))
    print("  [%s] %d 行 -> %s" % (tag, len(lines), p))
    for l in lines[-10:]:
        seg = l.split("): ", 1)[-1] if "): " in l else l
        print("   ", seg[:130])
def full():
    sid = open(LAB + "/card-sid.txt").read().strip()
    print("== 前置：回底 ==")
    time.sleep(1)
    ok, _ = tap("滚动到底部", desc=True), None
    print("  回底 tap:", ok)
    time.sleep(2.5)
    print("== 清 logcat / 开录屏 ==")
    sh("logcat", "-c")
    rec = subprocess.Popen([ADB, "-s", SERIAL, "shell", "screenrecord", "--time-limit", "175", "/sdcard/card-full2.mp4"], env=ENV, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    time.sleep(1)
    print("== 发送长输出请求（后台线程）==")
    res = {}
    def _send():
        try: res["info"] = api("POST", "/session/%s/message" % sid, {
            "parts": [{"type": "text", "text": "".join(PROMPT)}],
            "model": {"providerID": "zhipuai", "modelID": "glm-5.3"}})
        except Exception as ex: res["err"] = str(ex)
    threading.Thread(target=_send, daemon=True).start()
    print("== 等待增长期（最多 240s）==")
    gw = GrowthWatcher(); gw.start()
    ok = gw.triggered.wait(240)
    print("  增长期:", "已触发" if ok else "超时")
    if not ok: gw.stop(); return
    time.sleep(6)  # 增长持续：确保正文输出中（思考完毕、标题稳定）
    print("== 增长期 drift:", drift_state())
    print("== 离底：快速定位 → 最新 Q ==")
    tap("快速定位", desc=True)
    time.sleep(1.8)
    ok2 = tap("^Q\\d+$", last=True)
    if not ok2:
        print("  sheet 无 Q 行，退路下滑离底")
        sh("shell", "input", "swipe", "720", "700", "720", "1900", "500")
    time.sleep(2.2)
    print("== 离底后 drift:", drift_state())
    print("== 增长期中 tap 思考块标题（100ms 按压注入）==")
    t1 = tap("思考完毕")
    print("== 收集 6s episode/flush 并发日志 ==")
    time.sleep(6)
    dump_logs("p1-grow-tap")
    print("== 反向 toggle ==")
    tap("思考完毕")
    time.sleep(5)
    dump_logs("p2-grow-toggle")
    gw.stop()
    try: sh("shell", "pkill", "-INT", "screenrecord", timeout=10)
    except Exception: pass
    time.sleep(3)
    try: sh("pull", "/sdcard/card-full2.mp4", LAB + "/card-full2.mp4", timeout=90)
    except Exception as e: print("  pull 失败:", e)
    print("DONE")
def full2():
    """EP11: 增长期 toggle 上一完结 turn 的思考块 —— episode × 流式 flush 并发取证"""
    sid = open(LAB + "/card-sid.txt").read().strip()
    print("== 清 logcat / 开录屏 ==")
    sh("logcat", "-c")
    rec = subprocess.Popen([ADB, "-s", SERIAL, "shell", "screenrecord", "--time-limit", "175", "/sdcard/card-ep11.mp4"], env=ENV, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    time.sleep(1)
    print("== 发送 turn C（后台线程）==")
    res = {}
    def _send():
        try: res["info"] = api("POST", "/session/%s/message" % sid, {
            "parts": [{"type": "text", "text": "".join(PROMPT)}],
            "model": {"providerID": "zhipuai", "modelID": "glm-5.3"}})
        except Exception as ex: res["err"] = str(ex)
    threading.Thread(target=_send, daemon=True).start()
    print("== 等待增长期（最多 240s）==")
    gw = GrowthWatcher(); gw.start()
    ok = gw.triggered.wait(240)
    print("  增长期:", "已触发" if ok else "超时")
    if not ok: gw.stop(); return
    time.sleep(8)  # turn C 增长持续
    print("== 增长期 drift:", drift_state())
    print("== 离底：快速定位 → 倒数第二 Q（turn B = 完结 turn）==")
    tap("快速定位", desc=True)
    time.sleep(1.8)
    okB = tap("--INDEX2--")  # 占位，下一行实际执行
    import subprocess as _sp
    _r = _sp.run(["python3", LAB + "/tap-text-phone.py", r"^Q\d+$", "--last", "--index", "2"],
                 capture_output=True, text=True, env=dict(ENV, SERIAL=SERIAL))
    print("   tap[Q^--index2] -> " + (_r.stdout or "").strip())
    okB = (_r.stdout or "").startswith("tapped")
    if not okB:
        print("  倒数第二 Q 未找到，退路：下滑离底后继续")
        sh("shell", "input", "swipe", "720", "700", "720", "1900", "500")
    time.sleep(2.2)
    print("== 离底后 drift:", drift_state())
    t1 = tap("思考完毕")
    print("== 收集 6s episode × flush 并发日志 ==")
    time.sleep(6)
    dump_logs("ep11-p1")
    print("== 反向 toggle ==")
    tap("思考完毕")
    time.sleep(5)
    dump_logs("ep11-p2")
    gw.stop()
    try: sh("shell", "pkill", "-INT", "screenrecord", timeout=10)
    except Exception: pass
    time.sleep(3)
    try: sh("pull", "/sdcard/card-ep11.mp4", LAB + "/card-ep11.mp4", timeout=90)
    except Exception as e: print("  pull 失败:", e)
    print("DONE")
def full3():
    """EP12 v4: 等旧流式结束 → 离底定位+关autoOn → 发 turn C → 增长确立即 tap"""
    sid = open(LAB + "/card-sid.txt").read().strip()
    print("== 步骤-1：重启 app（保 SSE 新鲜）==")
    sh("shell", "am", "force-stop", PKG)
    time.sleep(1.5)
    import subprocess as _sp2
    env2 = dict(ENV, OCBEACEN_SERVICE_JSON="/tmp/card-service.json", PATH=os.path.expanduser("~/android-platform-tools/platform-tools") + ":" + ENV.get("PATH", ""))
    _sp2.run(["bash", os.path.expanduser("~/文档/code/mine/oc-beacon/scripts/debug-entry.sh"), SERIAL, PKG], env=env2, cwd=os.path.expanduser("~/文档/code/mine/oc-beacon"), capture_output=True, timeout=60)
    time.sleep(1.5)
    subprocess.run(["python3", LAB + "/tap-text-phone.py", "CARD-EP1[12]"], capture_output=True, text=True, env=dict(ENV, SERIAL=SERIAL))
    time.sleep(2)
    print("== 步骤0：等待旧流式结束 ==")
    for i in range(48):
        r = subprocess.run(["python3", LAB + "/peek.py"], capture_output=True, text=True, env=dict(ENV, SERIAL=SERIAL))
        if "正在流式输出" not in (r.stdout or ""):
            print("   旧流式已结束（%d 轮探测）" % i)
            break
        time.sleep(5)
    else:
        print("   旧流式仍在，继续（可能排队）")
    print("== 步骤1：快速定位 → 最新 Q（turn B，当前最新完结 turn）==")
    tap("快速定位", desc=True)
    time.sleep(1.8)
    r = subprocess.run(["python3", LAB + "/tap-text-phone.py", r"^Q\d+$", "--last", "--index", "2"],
                       capture_output=True, text=True, env=dict(ENV, SERIAL=SERIAL))
    print("   tap[倒数第二Q=turnB] -> " + (r.stdout or "").strip())
    time.sleep(2.0)
    print("== 步骤1.5：微下滑关闭 autoOn（防 MSGEFFECT 拉回贴底）==")
    sh("shell", "input", "swipe", "720", "1700", "720", "1560", "350")
    time.sleep(1.2)
    d = drift_state()
    print("   drift:", d)
    print("== 步骤2：确认思考块在视口 ==")
    ok = tap("思考完毕")  # 这次不点！只验证存在——不行，tap 会点。改为只 dump 检查
    print("   （注意：上面这下已触发一次 toggle，作为 phase0 展开——正好让 turn B 思考块展开，后续点它是收起方向）")
    time.sleep(2.5)
    print("== 步骤3：清 logcat / 开录屏 ==")
    sh("logcat", "-c")
    rec = subprocess.Popen([ADB, "-s", SERIAL, "shell", "screenrecord", "--time-limit", "175", "/sdcard/card-ep12.mp4"], env=ENV, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    print("== 步骤4：发 turn C（后台）==")
    res = {}
    def _send():
        try: res["info"] = api("POST", "/session/%s/message" % sid, {
            "parts": [{"type": "text", "text": "".join(PROMPT)}],
            "model": {"providerID": "zhipuai", "modelID": "glm-5.3"}})
        except Exception as ex: res["err"] = str(ex)
    threading.Thread(target=_send, daemon=True).start()
    print("== 步骤5：等增长期 ==")
    gw = GrowthWatcher(); gw.start()
    ok = gw.triggered.wait(240)
    print("  增长期:", "已触发" if ok else "超时")
    if not ok: gw.stop(); return
    time.sleep(2.5)  # 增长确立
    print("== 步骤6：增长期 tap turn B 思考块（收起方向）==")
    t = subprocess.run(["python3", LAB + "/tap-text-phone.py", "思考完毕"],
                       capture_output=True, text=True, env=dict(ENV, SERIAL=SERIAL))
    print("   tap -> " + (t.stdout or "").strip())
    print("== 步骤7：收集 6s 并发日志 ==")
    time.sleep(6)
    dump_logs("ep12-p1")
    print("== 步骤8：反向 toggle（展开）==")
    subprocess.run(["python3", LAB + "/tap-text-phone.py", "思考完毕"],
                   capture_output=True, text=True, env=dict(ENV, SERIAL=SERIAL))
    time.sleep(5)
    dump_logs("ep12-p2")
    gw.stop()
    try: sh("shell", "pkill", "-INT", "screenrecord", timeout=10)
    except Exception: pass
    time.sleep(3)
    try: sh("pull", "/sdcard/card-ep12.mp4", LAB + "/card-ep12.mp4", timeout=90)
    except Exception as e: print("  pull:", e)
    print("DONE")
def full4():
    """EP13: 干净会话方案 —— turnB(长,同步等完) → 离底关autoOn → turnC(短,密集增长) → 增长期 tap"""
    import random
    sid_new = api("POST", "/session", {"title": "CARD-RUN"})["id"]
    open(LAB + "/card-sid.txt", "w").write(sid_new)
    print("== 新会话:", sid_new)
    print("== 重启 app 进会话 ==")
    sh("shell", "am", "force-stop", PKG)
    time.sleep(1.5)
    env2 = dict(ENV, OCBEACEN_SERVICE_JSON="/tmp/card-service.json", PATH=os.path.expanduser("~/android-platform-tools/platform-tools") + ":" + ENV.get("PATH", ""))
    subprocess.run(["bash", os.path.expanduser("~/文档/code/mine/oc-beacon/scripts/debug-entry.sh"), SERIAL, PKG], env=env2, cwd=os.path.expanduser("~/文档/code/mine/oc-beacon"), capture_output=True, timeout=60)
    time.sleep(1.5)
    r = subprocess.run(["python3", LAB + "/tap-text-phone.py", "CARD-RUN"], capture_output=True, text=True, env=dict(ENV, SERIAL=SERIAL))
    print("   tap[CARD-RUN] ->", (r.stdout or "").strip())
    time.sleep(2)
    print("== turn B：长文（同步阻塞至完成）==")
    t0 = time.time()
    api("POST", "/session/%s/message" % sid_new, {
        "parts": [{"type": "text", "text": "".join(PROMPT)}],
        "model": {"providerID": "zhipuai", "modelID": "glm-5.3"}}, timeout=420)
    print("   turn B 完成，耗时 %.0fs" % (time.time() - t0))
    time.sleep(2)
    print("== 离底：快速定位 → turn B 的 Q → 微下滑关 autoOn ==")
    tap("快速定位", desc=True)
    time.sleep(1.8)
    r = subprocess.run(["python3", LAB + "/tap-text-phone.py", r"^Q\d+$", "--last"], capture_output=True, text=True, env=dict(ENV, SERIAL=SERIAL))
    print("   tap[Q] ->", (r.stdout or "").strip())
    time.sleep(1.8)
    sh("shell", "input", "swipe", "720", "1700", "720", "1560", "350")
    time.sleep(1.2)
    print("   drift:", drift_state())
    print("== phase0：展开 turn B 思考块 ==")
    r = subprocess.run(["python3", LAB + "/tap-text-phone.py", "思考完毕"], capture_output=True, text=True, env=dict(ENV, SERIAL=SERIAL))
    print("   tap ->", (r.stdout or "").strip())
    time.sleep(2.5)
    print("== 清 logcat / 录屏 ==")
    sh("logcat", "-c")
    rec = subprocess.Popen([ADB, "-s", SERIAL, "shell", "screenrecord", "--time-limit", "150", "/sdcard/card-ep13.mp4"], env=ENV, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    print("== turn C：短密集增长（数数）==")
    res = {}
    def _send():
        try: res["info"] = api("POST", "/session/%s/message" % sid_new, {
            "parts": [{"type": "text", "text": "请从1慢慢数到40，每个数字单独一行，不要跳数"}],
            "model": {"providerID": "zhipuai", "modelID": "glm-5.3"}})
        except Exception as ex: res["err"] = str(ex)
    threading.Thread(target=_send, daemon=True).start()
    gw = GrowthWatcher(n=3); gw.start()
    ok = gw.triggered.wait(180)
    print("  增长期:", "已触发" if ok else "超时")
    if not ok: gw.stop(); return
    time.sleep(2.0)
    print("== 增长期 tap turn B 思考块（收起）==")
    r = subprocess.run(["python3", LAB + "/tap-text-phone.py", "思考完毕"], capture_output=True, text=True, env=dict(ENV, SERIAL=SERIAL))
    print("   tap ->", (r.stdout or "").strip())
    time.sleep(6)
    dump_logs("ep13-p1")
    print("== 反向 toggle（展开）==")
    subprocess.run(["python3", LAB + "/tap-text-phone.py", "思考完毕"], capture_output=True, text=True, env=dict(ENV, SERIAL=SERIAL))
    time.sleep(5)
    dump_logs("ep13-p2")
    gw.stop()
    try: sh("shell", "pkill", "-INT", "screenrecord", timeout=10)
    except Exception: pass
    time.sleep(3)
    try: sh("pull", "/sdcard/card-ep13.mp4", LAB + "/card-ep13.mp4", timeout=90)
    except Exception as e: print("  pull:", e)
    print("DONE")
def full5():
    """EP14: turnB(长) → 发turnC(长) → 增长中跳转离底(滚底已发生) → 首tap即P1取证 → 反向P2"""
    sid_new = api("POST", "/session", {"title": "CARD-R2"})["id"]
    open(LAB + "/card-sid.txt", "w").write(sid_new)
    print("== 新会话:", sid_new)
    sh("shell", "am", "force-stop", PKG)
    time.sleep(1.5)
    env2 = dict(ENV, OCBEACEN_SERVICE_JSON="/tmp/card-service.json", PATH=os.path.expanduser("~/android-platform-tools/platform-tools") + ":" + ENV.get("PATH", ""))
    subprocess.run(["bash", os.path.expanduser("~/文档/code/mine/oc-beacon/scripts/debug-entry.sh"), SERIAL, PKG], env=env2, cwd=os.path.expanduser("~/文档/code/mine/oc-beacon"), capture_output=True, timeout=60)
    time.sleep(1.5)
    r = subprocess.run(["python3", LAB + "/tap-text-phone.py", "CARD-R2"], capture_output=True, text=True, env=dict(ENV, SERIAL=SERIAL))
    print("   tap[CARD-R2] ->", (r.stdout or "").strip())
    time.sleep(2)
    print("== turn B：长文（同步阻塞至完成）==")
    t0 = time.time()
    api("POST", "/session/%s/message" % sid_new, {
        "parts": [{"type": "text", "text": "".join(PROMPT)}],
        "model": {"providerID": "zhipuai", "modelID": "glm-5.3"}}, timeout=420)
    print("   turn B 完成 %.0fs" % (time.time() - t0))
    time.sleep(2)
    print("== turn C：另一篇长文（流式中跳转）==")
    PROMPT2 = "请详细介绍 HTTP/1.0、HTTP/1.1、HTTP/2、HTTP/3 的演进历程，每一代的小节都要展开多段论述，越长越好。"
    res = {}
    def _send():
        try: res["info"] = api("POST", "/session/%s/message" % sid_new, {
            "parts": [{"type": "text", "text": PROMPT2}],
            "model": {"providerID": "zhipuai", "modelID": "glm-5.3"}}, timeout=500)
        except Exception as ex: res["err"] = str(ex)
    threading.Thread(target=_send, daemon=True).start()
    gw = GrowthWatcher(n=4); gw.start()
    ok = gw.triggered.wait(200)
    print("  增长期:", "已触发" if ok else "超时")
    if not ok: gw.stop(); return
    time.sleep(6)  # 滚底已发生；思考完毕标题稳定
    print("== 增长中跳转：快速定位 → 倒数第二 Q（turn B）==")
    tap("快速定位", desc=True)
    time.sleep(1.8)
    r = subprocess.run(["python3", LAB + "/tap-text-phone.py", r"^Q\d+$", "--last", "--index", "2"], capture_output=True, text=True, env=dict(ENV, SERIAL=SERIAL))
    print("   tap[Q^turnB] ->", (r.stdout or "").strip())
    time.sleep(1.8)
    sh("shell", "input", "swipe", "720", "1700", "720", "1560", "350")
    time.sleep(1.2)
    print("   drift:", drift_state())
    print("== P1：增长期 tap turn B 思考块（首次 toggle）==")
    r = subprocess.run(["python3", LAB + "/tap-text-phone.py", "思考完毕"], capture_output=True, text=True, env=dict(ENV, SERIAL=SERIAL))
    print("   tap ->", (r.stdout or "").strip())
    time.sleep(6)
    dump_logs("ep14-p1")
    print("== P2：反向 toggle ==")
    subprocess.run(["python3", LAB + "/tap-text-phone.py", "思考完毕"], capture_output=True, text=True, env=dict(ENV, SERIAL=SERIAL))
    time.sleep(5)
    dump_logs("ep14-p2")
    gw.stop()
    print("DONE")
if __name__ == "__main__":
    if len(sys.argv) > 1 and sys.argv[1] == "full5": full5()
    elif len(sys.argv) > 1 and sys.argv[1] == "full4": full4()
    elif len(sys.argv) > 1 and sys.argv[1] == "full2": full2()
    elif len(sys.argv) > 1 and sys.argv[1] == "full3": full3()
    else: full()