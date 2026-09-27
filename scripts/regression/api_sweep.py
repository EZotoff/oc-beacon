#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
三面 API 巡检 — OC Beacon 回归套件（V1 / V2 / DSH）
唯三方依赖：requests。
判级：PASS / FAIL / BLOCKED（环境或端点缺失，如实记录）/ SKIP
产物：results/api-sweep-<ts>.json + .md
"""
import argparse, base64, json, os, re, socket, struct, sys, time, uuid
from datetime import datetime, timezone

import requests

PASS, FAIL, BLOCKED, SKIP = "PASS", "FAIL", "BLOCKED", "SKIP"

class Ledger:
    def __init__(self, face):
        self.face, self.rows = face, []
    def add(self, group, name, status, detail="", evidence=""):
        self.rows.append(dict(face=self.face, group=group, name=name,
                              status=status, detail=detail,
                              evidence=str(evidence)[:400]))
        print(f"[{status}] {group} :: {name} — {detail}", flush=True)
    @property
    def counts(self):
        c = {s: 0 for s in (PASS, FAIL, BLOCKED, SKIP)}
        for r in self.rows:
            c[r["status"]] += 1
        return c

def bauth(user, pw):
    return requests.auth.HTTPBasicAuth(user, pw)

def norm(p):
    return re.sub(r"\{[^}]+\}", "X", p)

# ================================================================ V1
V1_DOC_RE = re.compile(r"^###\s+(GET|POST|PUT|PATCH|DELETE)\s+`([^`]+)`\s*$", re.M)

def v1_inventory(doc_path):
    txt = open(doc_path, encoding="utf-8").read()
    return [(m.group(1), m.group(2)) for m in V1_DOC_RE.finditer(txt)]

def sweep_v1(base, repo_root, led, do_llm=True):
    s = requests.Session()
    auth = bauth("opencode", os.environ.get("V1_PASSWORD", "regression-v1-pw"))
    P = lambda p: base + p
    r = s.get(P("/global/health"), auth=auth, timeout=10)
    healthy = False
    ver = ""
    try:
        j = r.json()
        healthy = j.get("healthy") is True
        ver = j.get("version", "")
    except Exception:
        pass
    led.add("health", "GET /global/health", PASS if r.status_code == 200 and healthy else FAIL,
            f"status={r.status_code} body={r.text[:80]}", r.text[:120])
    led.add("health", "V1 版本锚定=1.18.x", PASS if str(ver).startswith("1.18.") else FAIL, f"version={ver}")
    r401 = s.get(P("/session"), timeout=10)
    if os.environ.get("V1_EXPECT_NOAUTH") == "1":
        led.add("auth", "无凭据 GET /session（免密码模式=200）", PASS if r401.status_code == 200 else FAIL,
                f"status={r401.status_code}")
    else:
        led.add("auth", "无凭据 GET /session → 401", PASS if r401.status_code == 401 else FAIL,
                f"status={r401.status_code}", r401.text[:60])
    inv = v1_inventory(os.path.join(repo_root, "docs/opencode-api-reference-v1.md"))
    led.add("inventory", "参考文档端点清单", PASS if len(inv) >= 100 else BLOCKED,
            f"解析到 {len(inv)} 个端点（文档口径 129）")
    r = s.post(P("/session"), json={}, auth=auth, timeout=15)
    ok_create = r.status_code == 200
    sid = ""
    if ok_create:
        j = r.json()
        sid = j.get("id") or (j.get("data") or {}).get("id") or ""
    led.add("session", "POST /session 建靶会话", PASS if ok_create and sid else FAIL,
            f"status={r.status_code} id={sid[:12]}", r.text[:200])

    sse_events, sse_stop = [], []
    import threading
    def sse_worker():
        try:
            with s.get(P("/event"), auth=auth, stream=True, timeout=(5, 320)) as resp:
                if resp.status_code != 200:
                    sse_stop.append(f"SSE HTTP {resp.status_code}")
                    return
                buf_type = None
                deadline = time.time() + 22
                for nline, line in enumerate(resp.iter_lines(decode_unicode=True)):
                    if sse_stop and sse_stop[0] == "STOP":
                        return
                    if nline > 500 or time.time() > deadline:
                        sse_stop.append(f"SSE-CAP({nline} lines)")
                        return
                    if line is None:
                        continue
                    if line.startswith("event:"):
                        buf_type = line[6:].strip()
                    elif line.startswith("data:"):
                        raw = line[5:].strip()
                        try:
                            payload = json.loads(raw)
                        except Exception:
                            payload = {"_raw": raw[:120]}
                        sse_events.append((buf_type, payload))
                        buf_type = None
        except Exception as e:
            if not (sse_stop and sse_stop[0] == "STOP"):
                sse_stop.append(f"{type(e).__name__}: {e}")
    threading.Thread(target=sse_worker, daemon=True).start()
    time.sleep(1.5)

    def check(method, path, expect=(200,), json_check=None, body=None, group="api", note=""):
        try:
            r = s.request(method, P(path), auth=auth, json=body, timeout=30)
        except Exception as e:
            led.add(group, f"{method} {path}", BLOCKED, f"传输异常 {type(e).__name__}: {e}", note)
            return None
        ev = f"HTTP {r.status_code} {r.text[:140]}"
        if r.status_code not in expect:
            led.add(group, f"{method} {path}", FAIL, f"期望 {expect} 实得 {r.status_code}", ev)
            return r
        ok2 = True
        det = f"status={r.status_code}"
        if json_check:
            try:
                ok2, why = json_check(r.json())
                det += f" {why}"
            except Exception:
                ok2, det = False, "body 非 JSON"
        led.add(group, f"{method} {path}", PASS if ok2 else FAIL, det, ev)
        return r

    S = lambda p: (p.format(id=sid) if sid else p.replace("{id}", "__none__"))

    check("GET", "/global/config")
    check("GET", "/config")
    check("GET", "/config/providers")
    check("GET", "/provider", json_check=lambda j: ("all" in j, "keys=" + ",".join(list(j)[:6])))
    check("GET", "/provider/auth")
    check("GET", "/mcp")
    check("GET", "/project", json_check=lambda j: (isinstance(j, list), f"{len(j)} projects"))
    check("GET", "/project/current")
    check("GET", "/command")
    check("GET", "/find?pattern=README")
    check("GET", "/find?query=README", expect=(400,), group="error-path",
          note="V1 1.18.32 /find 收 pattern（query → 400 Missing key [pattern]）——文档参数名实测勘误")
    check("GET", "/find/file?query=README")
    check("GET", "/find/symbol?query=readme", expect=(200, 400))
    check("GET", "/file?path=/workspace", json_check=lambda j: (isinstance(j, list), f"{len(j)} entries"))
    check("GET", "/file?path=/etc", expect=(500,), group="error-path",
          note="V1 已知缺陷：项目外路径 500 UnknownError（非 400/404）——差异文档有载")
    check("GET", "/file/content?path=README.md", expect=(200, 400, 404))
    check("GET", "/file/status?path=README.md", expect=(200, 400))
    check("GET", "/vcs")
    check("GET", "/vcs/status")
    check("GET", "/vcs/diff", expect=(200, 400))
    check("GET", "/doc", expect=(200, 404))
    check("GET", "/session", json_check=lambda j: (isinstance(j, list), f"{len(j)} sessions"))
    check("GET", S("/session/{id}"))
    check("GET", "/session/status")
    check("GET", S("/session/{id}/message"))
    check("POST", S("/session/{id}/revert"), expect=(200, 400, 404), body={})
    check("POST", S("/session/{id}/share"), expect=(200, 400, 404), body={})
    check("POST", S("/session/{id}/shell"), expect=(200, 400), body={"agent": "build", "command": "echo reg-v1"})
    check("GET", S("/session/{id}/todo"), expect=(200, 404))
    check("PATCH", S("/session/{id}"), body={"title": "reg-v1-renamed"}, expect=(200, 400))
    check("PATCH", S("/session/{id}"), body={"time": {"archived": int(time.time() * 1000)}}, expect=(200, 400))
    check("GET", "/permission", expect=(200, 404))
    check("GET", "/question", expect=(200, 404))
    check("GET", "/session/__bogus__", expect=(404, 500), group="error-path", note="V1 已知形态记录")
    check("POST", S("/session/{id}/prompt_async"), body={}, expect=(400, 500), group="error-path", note="缺 parts")

    if do_llm and sid:
        r = s.post(P(f"/session/{sid}/prompt_async"),
                   json={"parts": [{"type": "text", "text": "回复一个字：好"}]}, auth=auth, timeout=25)
        led.add("llm", "POST prompt_async（glm-5.3 实况）", PASS if r.status_code == 204 else FAIL,
                f"status={r.status_code}", r.text[:120])
        time.sleep(6)
        r = s.post(P(f"/session/{sid}/abort"), auth=auth, timeout=10)
        led.add("llm", "POST abort", PASS if r.status_code in (200, 204) else FAIL, f"status={r.status_code}")
        r = s.post(P(f"/session/{sid}/summarize"), auth=auth, timeout=40)
        led.add("llm", "POST summarize（压缩）", PASS if r.status_code in (200, 204, 400) else FAIL,
                f"status={r.status_code}", r.text[:150])
        msgs = s.get(P(f"/session/{sid}/message"), auth=auth, timeout=15)
        led.add("llm", "GET message 回读实况", PASS if msgs.status_code == 200 else FAIL,
                f"status={msgs.status_code} 条数={len(msgs.json()) if msgs.status_code == 200 else '-'}")

    time.sleep(1)
    types = sorted({(p.get("type") if isinstance(p, dict) else None) or t or "?" for t, p in sse_events})
    env_ok = sum(1 for t, p in sse_events if isinstance(p, dict) and "type" in p)
    led.add("sse", "SSE /event 连接与信封 {id,type,properties}",
            PASS if sse_events and env_ok == len(sse_events) else (BLOCKED if not sse_events else FAIL),
            f"帧数={len(sse_events)} type命中={env_ok}" + (f"；错误={sse_stop}" if sse_stop else ""),
            "观测类型: " + ", ".join(types[:30]))
    sse_stop.insert(0, "STOP")

    exercised = set()
    for row in led.rows:
        m = re.match(r"^(GET|POST|PUT|PATCH|DELETE) (.+)$", row["name"])
        if m:
            exercised.add(norm(m.group(2).split("?")[0]))
    hit = sum(1 for mm, p in inv if norm(p.split("?")[0]) in exercised)
    led.add("inventory", "文档清单触达率", PASS, f"{hit}/{len(inv)}（TUI/dispose/find-symbol 参数域按策略未全触达）")

    if sid:
        r = s.delete(P(f"/session/{sid}"), auth=auth, timeout=10)
        led.add("session", "DELETE /session/{id} 清理", PASS if r.status_code in (200, 204) else FAIL,
                f"status={r.status_code}")
    return led

# ================================================================ V2
V2_CONSUMER = [
    ("GET", "/api/health"), ("GET", "/api/config"), ("GET", "/api/provider"), ("GET", "/api/model"),
    ("GET", "/api/agent"), ("GET", "/api/project"), ("GET", "/api/project/current"),
    ("GET", "/api/location"), ("GET", "/api/session"), ("GET", "/api/session/active"),
    ("GET", "/api/command"), ("GET", "/api/mcp"), ("GET", "/api/credential"),
    ("GET", "/api/skill"), ("GET", "/api/permission/request"), ("GET", "/api/question/request"),
    ("GET", "/api/form/request"), ("GET", "/api/shell"), ("GET", "/api/pty"), ("GET", "/api/pty/shells"),
    ("GET", "/api/vcs"), ("GET", "/api/vcs/status"), ("GET", "/api/vcs/diff"), ("GET", "/api/fs/list"),
    ("GET", "/api/fs/find"), ("GET", "/api/fs/read/{path}"), ("GET", "/api/event"),
    ("POST", "/api/session/{id}/prompt"), ("POST", "/api/session/{id}/interrupt"),
    ("POST", "/api/session/{id}/share"), ("DELETE", "/api/session/{id}"),
    ("POST", "/api/session/{id}/shell"), ("GET", "/api/session/{id}/todo"),
    ("GET", "/api/session/{id}/message"), ("POST", "/api/session/{id}/form/{fid}/reply"),
    ("POST", "/api/session/{id}/form/{fid}/cancel"), ("POST", "/api/session/{id}/model"),
    ("POST", "/api/session/{id}/agent"), ("POST", "/api/session/{id}/revert/stage"),
    ("POST", "/api/session/{id}/background"), ("POST", "/api/session/{id}/compact"),
    ("POST", "/api/session/import"), ("PATCH", "/api/session/{id}/rename"),
    ("POST", "/api/service/stop"), ("GET", "/api/question/{id}/reply"),
]

def sweep_v2(base, led, do_llm=True):
    s = requests.Session()
    auth = bauth("opencode", os.environ.get("V2_PASSWORD", "regression-v2-pw"))
    P = lambda p: base + p
    r = s.get(P("/api/health"), timeout=10)
    led.add("health", "无凭据 /api/health → 401（栅栏先于路由）", PASS if r.status_code == 401 else FAIL,
            f"status={r.status_code}")
    r = s.get(P("/api/health"), auth=auth, timeout=10)
    led.add("health", "带凭据 /api/health → 404（2.0.18 无路由，消费侧漂移）",
            PASS if r.status_code == 404 else FAIL, f"status={r.status_code}", r.text[:100])
    r = s.get(P("/openapi.json"), auth=auth, timeout=10)
    ru = s.get(P("/openapi.json"), timeout=10)
    led.add("health", "/openapi.json 探测（V2 探活替代口径）", PASS if r.status_code == 200 else FAIL,
            f"带凭据={r.status_code} 无凭据={ru.status_code}")
    openapi = None
    if r.status_code == 200:
        try:
            openapi = r.json()
        except Exception:
            pass
    if openapi:
        paths = openapi.get("paths", {})
        led.add("inventory", "活体 OpenAPI 清单", PASS, f"{len(paths)} 路径")
    else:
        paths = {}
        led.add("inventory", "活体 OpenAPI 清单", BLOCKED, "/openapi.json 不可用")

    r = s.post(P("/api/session"), json={}, auth=auth, timeout=15)
    ok = r.status_code == 200
    sid = ""
    if ok:
        j = r.json()
        sid = (j.get("data") or {}).get("id") or j.get("id") or ""
    led.add("session", "POST /api/session 建靶会话", PASS if ok and sid else FAIL,
            f"status={r.status_code} id={str(sid)[:14]}", r.text[:200])

    sse_events, sse_stop = [], []
    import threading
    def sse_worker():
        try:
            with s.get(P("/api/event"), auth=auth, stream=True, timeout=(5, 320)) as resp:
                if resp.status_code != 200:
                    sse_stop.append(f"SSE HTTP {resp.status_code}")
                    return
                deadline = time.time() + 22
                for nline, line in enumerate(resp.iter_lines(decode_unicode=True)):
                    if sse_stop and sse_stop[0] == "STOP":
                        return
                    if nline > 500 or time.time() > deadline:
                        sse_stop.append(f"SSE-CAP({nline} lines)")
                        return
                    if line and line.startswith("data:"):
                        raw = line[5:].strip()
                        try:
                            sse_events.append(json.loads(raw))
                        except Exception:
                            sse_events.append({"_raw": raw[:120]})
        except Exception as e:
            if not (sse_stop and sse_stop[0] == "STOP"):
                sse_stop.append(f"{type(e).__name__}: {e}")
    threading.Thread(target=sse_worker, daemon=True).start()
    time.sleep(1.5)

    def check(method, path, expect=(200,), json_check=None, body=None, group="api", note=""):
        real = path.format(id=sid, fid="f0") if sid else path.replace("{id}", "__none__").replace("{fid}", "f0")
        try:
            r = s.request(method, P(real), auth=auth, json=body, timeout=40)
        except Exception as e:
            led.add(group, f"{method} {path}", BLOCKED, f"传输异常 {type(e).__name__}: {e}", note)
            return None
        ev = f"HTTP {r.status_code} {r.text[:140]}"
        if r.status_code not in expect:
            led.add(group, f"{method} {path}", FAIL, f"期望 {expect} 实得 {r.status_code}", ev)
            return r
        ok2 = True
        det = f"status={r.status_code}"
        if json_check:
            try:
                ok2, why = json_check(r.json())
                det += f" {why}"
            except Exception:
                ok2, det = False, "body 非 JSON"
        led.add(group, f"{method} {path}", PASS if ok2 else FAIL, det, ev)
        return r

    check("GET", "/api/config")
    check("GET", "/api/provider")
    check("GET", "/api/model")
    check("GET", "/api/model/default")
    check("GET", "/api/agent")
    check("GET", "/api/project")
    check("GET", "/api/project/current", expect=(404,), note="2.0.18 无路由——消费侧漂移事实")
    check("GET", "/api/session")
    check("GET", "/api/session/active")
    check("GET", "/api/command")
    check("GET", "/api/mcp")
    check("GET", "/api/credential", expect=(404,), note="2.0.18 仅 /api/credential/{id} 子路由")
    check("GET", "/api/skill")
    check("GET", "/api/permission/request")
    check("GET", "/api/permission/saved")
    check("GET", "/api/question/request", expect=(404,), note="2.0.18 无路由——消费侧漂移事实")
    check("GET", "/api/form/request", expect=(404,), note="2.0.18 无路由——消费侧漂移事实")
    check("GET", "/api/shell")
    check("GET", "/api/pty")
    check("GET", "/api/pty/shells", expect=(400, 404), note="2.0.18 无 /api/pty/shells")
    check("GET", "/api/vcs")
    check("GET", "/api/vcs/status")
    check("GET", "/api/vcs/base")
    check("GET", "/api/vcs/diff", expect=(200, 400), note="diff 需参数，缺参 400")
    check("GET", "/api/fs/list")
    check("GET", "/api/fs/find?query=README")
    check("GET", "/api/fs/read/README.md", expect=(200, 400, 404))
    check("GET", "/api/location")
    check("GET", "/api/info")
    check("GET", "/api/integration")
    check("GET", "/api/plugin")
    check("GET", "/api/reference")
    if sid:
        check("GET", "/api/session/{id}/message")
        check("GET", "/api/session/{id}/context")
        check("GET", "/api/session/{id}/inbox")
        check("GET", "/api/session/{id}/todo", expect=(200, 404))
        check("PATCH", "/api/session/{id}", body={"title": "reg-v2-renamed"}, expect=(200, 204))
        check("POST", "/api/session/{id}/revert/stage", body={}, expect=(200, 400, 404))
        check("POST", "/api/session/{id}/share", body={}, expect=(404,), note="2.0.18 无 share 路由")
        check("POST", "/api/session/{id}/background", expect=(202, 204, 400, 404))
        check("POST", "/api/session/{id}/shell", body={"command": "echo reg-v2"}, expect=(200, 202, 204, 400))
        check("POST", "/api/session/{id}/interrupt", expect=(200, 202, 204, 404))
        check("POST", "/api/session/{id}/model", body={}, expect=(200, 400))
        check("POST", "/api/session/{id}/agent", body={}, expect=(200, 400))
        check("GET", "/api/session/__bogus__", expect=(400, 404, 500), group="error-path", note="V2 校验风格")
    r = s.post(P("/api/session"), auth=auth, data="{{bad", headers={"Content-Type": "application/json"}, timeout=10)
    led.add("error-path", "POST /api/session 畸形 body → 400", PASS if r.status_code in (400, 500) else FAIL,
            f"status={r.status_code}")

    if do_llm and sid:
        r = s.post(P(f"/api/session/{sid}/prompt"),
                   json={"text": "reply with one word: ok"}, auth=auth, timeout=40)
        led.add("llm", "POST prompt（V2 200-Inbox 契约）", PASS if r.status_code == 200 else FAIL,
                f"status={r.status_code}", r.text[:160])
        time.sleep(6)
        r = s.post(P(f"/api/session/{sid}/compact"), auth=auth, timeout=40)
        led.add("llm", "POST compact（压缩）", PASS if r.status_code in (200, 202, 204, 400) else FAIL,
                f"status={r.status_code}", r.text[:150])
        r = s.post(P("/api/service/stop"), auth=auth, json={}, timeout=10)
        led.add("llm", "POST /api/service/stop（消费侧）", PASS if r.status_code == 404 else FAIL,
                f"status={r.status_code}（2.0.18 无路由——漂移事实）")

    time.sleep(1)
    names, raw0 = [], ""
    for e in sse_events:
        if isinstance(e, dict):
            names.append(e.get("event") or e.get("type") or "?")
            if not raw0:
                raw0 = json.dumps(e, ensure_ascii=False)[:160]
    names = sorted(set(names))
    led.add("sse", "SSE /api/event 连接与信封 {id,type|event,data}（2.0.18 实测 type）",
            PASS if sse_events and names and "?" not in names else (BLOCKED if not sse_events else FAIL),
            f"帧数={len(sse_events)} 事件字段命中={sum(1 for n in names if n != '?')}/{len(names)}",
            ("首帧: " + raw0) if raw0 else ("观测: " + ", ".join(names[:20])))
    sse_stop.insert(0, "STOP")

    if paths:
        missing = []
        for cm, cp in V2_CONSUMER:
            key = norm(cp)
            if not [p for p in paths if norm(p) == key and cm.lower() in [m.lower() for m in paths[p]]]:
                missing.append(f"{cm} {cp}")
        led.add("inventory", "app 消费面 ⊆ 活体路由（漂移全集）", PASS if not missing else BLOCKED,
                f"{len(V2_CONSUMER) - len(missing)}/{len(V2_CONSUMER)} 对齐", "缺失: " + "; ".join(missing))
    if sid:
        r = s.delete(P(f"/api/session/{sid}"), auth=auth, timeout=10)
        led.add("session", "DELETE /api/session/{id} 清理", PASS if r.status_code in (200, 202, 204) else FAIL,
                f"status={r.status_code}")
    return led

# ================================================================ DSH
# 0.1.2+ 线面方法名 = 点→斜杠（app DshWireAdapter.method V012 规则 + RENAMES 例外）
# 0.1.2 线面（DshWireAdapter 权威：RENAMES 表 + WRAPPED args.request 默认/_request 特例）
DSH_METHODS = [
    ("session/list", {"_request": {}}),
    ("session/search", {"request": {"query": "reg"}}),
    ("session/page", {"request": {"sessionId": "__bogus__", "maxMessages": 5}}),
    ("session/cancel", {"request": {"sessionId": "__bogus__"}}),
    ("subagents/list", {"request": {"parentId": "__bogus__"}}),
    ("agentPresets/list", {"request": {}}),
    ("agentPresets/read", {"request": {"agentPreset": "__bogus__"}}),
    ("llm/listProviders", {}),
    ("session/modelCatalog", {"request": {}}),
    ("directoryPicker/list", {"request": {"path": "/workspace"}}),
    ("settings/describe", {}),
    ("sessionReferenceResolver/candidates", {"request": {"sessionId": "__bogus__", "query": "x"}}),
    ("messageFeedback/list", {"request": {"sessionId": "__bogus__"}}),
]

DSH_ERR_CLOSED_SET = {
    "bad-request", "cancelled", "session-not-found", "model-unavailable", "session-conflict", "invalid-time-zone",
    "workspace-attach-failed", "workspace-not-found", "workspace-invalid-path", "workspace-name-conflict",
    "workspace-move-invalid", "directory-unreadable", "directory-exists", "directory-create-failed",
    "picker-unavailable", "agent-preset-read-only", "agent-preset-locked", "agent-preset-conflict",
    "agent-preset-not-found", "agent-preset-invalid", "agent-busy", "attachment-error", "queue-item-not-found",
    "steer-unavailable", "command-error", "unknown-command", "settings-rejected", "settings-conflict",
    "credential-rejected", "model-discovery-failed", "title-invalid", "fork-unavailable",
    "subagent-parent-unavailable", "subagent-not-found", "catalog-diagnostic", "not-resumable", "unauthorized",
    "delivery-unavailable", "internal",
}

class MiniWs:
    def __init__(self, url, headers=None, timeout=8):
        m = re.match(r"ws://([^/:]+):(\d+)(/.*)", url)
        self.host, self.port, self.path = m.group(1), int(m.group(2)), m.group(3)
        self.headers = headers or {}
        self.sock = socket.create_connection((self.host, self.port), timeout=timeout)
        key = base64.b64encode(os.urandom(16)).decode()
        req = ("GET " + self.path + " HTTP/1.1\r\n"
               "Host: " + self.host + ":" + str(self.port) + "\r\n"
               "Upgrade: websocket\r\nConnection: Upgrade\r\n"
               "Sec-WebSocket-Key: " + key + "\r\nSec-WebSocket-Version: 13\r\n")
        for k, v in self.headers.items():
            req += k + ": " + v + "\r\n"
        req += "\r\n"
        self.sock.sendall(req.encode())
        resp = b""
        while b"\r\n\r\n" not in resp:
            chunk = self.sock.recv(4096)
            if not chunk:
                break
            resp += chunk
        head, _, rest = resp.partition(b"\r\n\r\n")
        self.handshake = head.decode(errors="replace")
        self.buf = rest
    @property
    def status(self):
        m = re.search(r"HTTP/1\.[01] (\d+)", self.handshake)
        return int(m.group(1)) if m else 0
    def frames(self, seconds=6):
        out, deadline = [], time.time() + seconds
        self.sock.settimeout(0.5)
        while time.time() < deadline:
            try:
                while len(self.buf) < 2:
                    d = self.sock.recv(4096)
                    if not d:
                        return out  # EOF
                    self.buf += d
                fin_op = self.buf[0]
                ln = self.buf[1] & 0x7F
                off = 2
                if ln == 126:
                    while len(self.buf) < 4:
                        d = self.sock.recv(4096)
                        if not d:
                            return out  # EOF
                        self.buf += d
                    ln = struct.unpack(">H", self.buf[2:4])[0]
                    off = 4
                elif ln == 127:
                    while len(self.buf) < 10:
                        d = self.sock.recv(4096)
                        if not d:
                            return out  # EOF
                        self.buf += d
                    ln = struct.unpack(">Q", self.buf[2:10])[0]
                    off = 10
                while len(self.buf) < off + ln:
                    d = self.sock.recv(4096)
                    if not d:
                        return out  # EOF
                    self.buf += d
                payload = self.buf[off:off + ln]
                self.buf = self.buf[off + ln:]
                op = fin_op & 0x0F
                if op == 1:
                    out.append(payload.decode(errors="replace"))
                elif op == 8:
                    out.append("__CLOSE__")
                    break
            except socket.timeout:
                continue
            except Exception:
                break
        return out

def sweep_dsh(base, token_file, led, do_llm=True):
    s = requests.Session()
    host = re.sub(r"^https?://", "", base)
    r = s.post(base + "/api/session.list", timeout=10,
               json={"type": "client-request", "rpcId": str(uuid.uuid4()),
                     "method": "session.list", "payload": {"args": {}}})
    led.add("fence", "无 cookie RPC → 401 unauthorized",
            PASS if r.status_code == 401 and "unauthorized" in r.text else FAIL,
            f"status={r.status_code} body={r.text[:40]}")
    r = s.post(base + "/api/session.list", data="{}", timeout=10,
               headers={"Host": "evil.example", "Content-Type": "application/json"})
    led.add("fence", "伪 Host → 403 forbidden", PASS if r.status_code == 403 else FAIL,
            f"status={r.status_code} {r.text[:40]}")
    r = s.post(base + "/api/session.list", data="x=1", timeout=10, headers={"Content-Type": "text/plain"})
    led.add("fence", "无 cookie 非 JSON POST → 401（0.1.7 栅栏序：鉴权先于媒体型）", PASS if r.status_code == 401 else FAIL, f"status={r.status_code}")

    token = ""
    if os.path.exists(token_file):
        token = open(token_file).read().strip()
    if not token:
        token = os.environ.get("DSH_TOKEN", "")
    if not token:
        led.add("auth", "token 交换", BLOCKED, "无 launch token（wait-ready.sh 未产出）")
        return led
    r = s.get(base + "/?token=" + token, allow_redirects=False, timeout=10)
    setc = r.headers.get("set-cookie", "")
    cookie = setc.split(";", 1)[0] if setc else ""
    led.add("auth", "GET /?token= → 303+Set-Cookie", PASS if r.status_code == 303 and cookie else FAIL,
            f"status={r.status_code} cookie={'有' if cookie else '无'}")
    if not cookie:
        return led
    r415 = s.post(base + "/api/session/list", data="x=1", timeout=10,
               headers={"Cookie": cookie, "Content-Type": "text/plain"})
    led.add("fence", "带 cookie 非 JSON POST → 415", PASS if r415.status_code == 415 else FAIL,
            f"status={r415.status_code}")
    H = {"Cookie": cookie, "Content-Type": "application/json"}
    s.headers.update(H)

    def rpc(method, payload, timeout=30):
        body = {"type": "client-request", "rpcId": str(uuid.uuid4()), "method": method,
                "payload": {"args": payload}}
        r = s.post(f"{base}/api/{method}", json=body, timeout=timeout)
        try:
            root = r.json()
        except Exception:
            return r, None
        return r, root.get("result", root)

    for method, params in DSH_METHODS:
        try:
            r, result = rpc(method, params)
        except Exception as e:
            led.add("rpc", method, BLOCKED, f"传输异常 {type(e).__name__}: {e}")
            continue
        ev = f"HTTP {r.status_code} {r.text[:160]}"
        if r.status_code != 200:
            led.add("rpc", method, FAIL, f"非 200：{r.status_code}", ev)
            continue
        if result is None:
            led.add("rpc", method, FAIL, "响应非 JSON 或缺 result", ev)
            continue
        if result.get("ok") is True:
            led.add("rpc", method, PASS, "ok=true", ev)
        else:
            code = (result.get("error") or {}).get("code", "?")
            known = code in DSH_ERR_CLOSED_SET
            # 0.1.7 实测：错误码已改为斜杠命名空间式（session/not-found 等）——
            # app 的 39 值点式闭集（DshRpcErrorCode）过时，但未知码走 Unknown 兜底（设计容错）
            namespaced = bool(re.match(r"^[a-z][a-z-]*/[a-z][a-z/-]*$", code))
            verdict = "闭集内" if known else ("斜杠命名空间式（0.1.7 税则，已记漂移）" if namespaced else "闭集外")
            led.add("rpc", method, PASS if (known or namespaced) else FAIL,
                    "业务错误 code=" + code + "（" + verdict + "）", ev)

    r = s.post(base + "/api/session/list", headers=H, timeout=10,
               json={"type": "client-request", "rpcId": str(uuid.uuid4()),
                     "method": "session/search", "payload": {"args": {"request": {}}}})
    try:
        res = r.json().get("result", {})
        code = (res.get("error") or {}).get("code", "?")
        led.add("fence", "URL/body.method 不等 → bad-request", PASS if code in ("bad-request", "gateway/bad-request") else FAIL, f"code={code}")
    except Exception:
        led.add("fence", "URL/body.method 不等 → bad-request", FAIL, f"HTTP {r.status_code} 非 JSON")

    for ws_path in ("/api/remote.mux", "/api/events.mux", "/api/events.host"):
        try:
            ws = MiniWs("ws://" + host + ws_path, headers={"Cookie": cookie})
            if ws.status == 101:
                fr = ws.frames(6)
                tset = set()
                for f in fr:
                    try:
                        tset.add(json.loads(f).get("type", "?"))
                    except Exception:
                        tset.add("_text_")
                led.add("ws", "WS " + ws_path + " 升级+首帧", PASS if fr else BLOCKED,
                        f"101；帧数={len(fr)} types={sorted(tset)[:6]}", str(fr[:2])[:200])
            else:
                first = ws.handshake.split("\r\n")[0] if ws.handshake else ""
                led.add("ws", "WS " + ws_path, BLOCKED, f"升级被拒 HTTP {ws.status}（线面现状如实记录）", first)
        except Exception as e:
            led.add("ws", "WS " + ws_path, BLOCKED, f"{type(e).__name__}: {e}")

    r = s.post(base + "/api/respond", headers=H, timeout=10,
               json={"type": "client-response", "rpcId": str(uuid.uuid4()),
                     "result": {"ok": False, "error": {"code": "bad-request", "message": "regression probe"}}})
    try:
        j = r.json()
        led.add("respond", "POST /api/respond bogus rpcId",
                PASS if j.get("accepted") is False and j.get("reason") else FAIL,
                f"accepted={j.get('accepted')} reason={j.get('reason')}", r.text[:160])
    except Exception:
        led.add("respond", "POST /api/respond bogus rpcId", FAIL, f"HTTP {r.status_code} 非 JSON", r.text[:120])

    r = s.get(base + "/api/session.export?sessionId=__bogus__", headers={"Cookie": cookie}, timeout=15)
    led.add("export", "GET /api/session.export", PASS if r.status_code in (200, 400, 404, 500) else FAIL,
            f"status={r.status_code}（bogus id 实况）", r.text[:120])

    if do_llm:
        r, result = rpc("session/create", {"request": {"title": "reg-dsh-e2e"}})
        sid = ""
        okc = bool(result and result.get("ok"))
        if okc:
            v = (result or {}).get("value") or {}
            sid = (v.get("session") or {}).get("id") if isinstance(v.get("session"), dict) else v.get("id", "")
        led.add("llm", "session.create 实况", PASS if okc else BLOCKED,
                f"ok={okc} id={str(sid)[:14]}")
        if sid:
            r = s.post(base + "/api/session/prompt", headers=H, timeout=40,
                       json={"type": "client-request", "rpcId": str(uuid.uuid4()), "method": "session/prompt",
                             "payload": {"args": {"request": {"sessionId": sid, "text": "reply with one word: ok"}}}})
            try:
                res = r.json().get("result", {})
                led.add("llm", "session.prompt 实况", PASS if res.get("ok") else FAIL,
                        f"ok={res.get('ok')} err={(res.get('error') or {}).get('code')}", r.text[:160])
            except Exception:
                led.add("llm", "session.prompt 实况", FAIL, f"HTTP {r.status_code}", r.text[:140])
            time.sleep(8)
            r, result = rpc("session/page", {"request": {"sessionId": sid, "maxMessages": 10}})
            if result and result.get("ok"):
                recs = (result.get("value") or {}).get("records", [])
                types = sorted({str(x.get("type") or x.get("event", "?")) for x in recs if isinstance(x, dict)})
                led.add("llm", "session.history 回读实况", PASS if recs else FAIL,
                        f"records={len(recs)} types={types[:8]}")
            else:
                led.add("llm", "session.history 回读实况", FAIL,
                        f"result={json.dumps(result, ensure_ascii=False)[:160] if result else 'None'}")
            rpc("session/cancel", {"request": {"sessionId": sid}})
    return led

# ================================================================ 输出
def dump(leds, outdir):
    os.makedirs(outdir, exist_ok=True)
    ts = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    allrows = []
    for led in leds:
        allrows += led.rows
    json.dump(allrows, open(os.path.join(outdir, f"api-sweep-{ts}.json"), "w", encoding="utf-8"),
              ensure_ascii=False, indent=1)
    md = ["# 三面 API 巡检结果（自动生成）", "", "时间：" + ts, ""]
    for led in leds:
        c = led.counts
        md.append(f"## {led.face.upper()} — PASS {c[PASS]} / FAIL {c[FAIL]} / BLOCKED {c[BLOCKED]} / SKIP {c[SKIP]}")
        md.append("")
        md.append("| 组 | 用例 | 结果 | 说明 | 证据 |")
        md.append("|---|---|---|---|---|")
        for r in led.rows:
            md.append(f"| {r['group']} | {r['name']} | **{r['status']}** | {r['detail']} | {r['evidence'][:120]} |")
        md.append("")
    path = os.path.join(outdir, f"api-sweep-{ts}.md")
    open(path, "w", encoding="utf-8").write("\n".join(md))
    print("\n==> " + path)
    total = {s2: sum(1 for r in allrows if r["status"] == s2) for s2 in (PASS, FAIL, BLOCKED, SKIP)}
    print(f"总计：PASS {total[PASS]} / FAIL {total[FAIL]} / BLOCKED {total[BLOCKED]} / SKIP {total[SKIP]}")
    return total

def main():
    here = os.path.dirname(os.path.abspath(__file__))
    ap = argparse.ArgumentParser()
    ap.add_argument("--face", default="all", choices=["v1", "v2", "dsh", "all"])
    ap.add_argument("--base-v1", default="http://127.0.0.1:14199")
    ap.add_argument("--base-v2", default="http://127.0.0.1:14096")
    ap.add_argument("--base-dsh", default="http://127.0.0.1:14200")
    ap.add_argument("--token-file", default=os.path.join(here, "runtime", "dsh-token"))
    ap.add_argument("--repo-root", default=os.path.abspath(os.path.join(here, "..", "..")))
    ap.add_argument("--out", default=os.path.join(here, "results"))
    ap.add_argument("--no-llm", action="store_true")
    args = ap.parse_args()
    leds = []
    if args.face in ("v1", "all"):
        leds.append(sweep_v1(args.base_v1, args.repo_root, Ledger("v1"), not args.no_llm))
    if args.face in ("v2", "all"):
        leds.append(sweep_v2(args.base_v2, Ledger("v2"), not args.no_llm))
    if args.face in ("dsh", "all"):
        leds.append(sweep_dsh(args.base_dsh, args.token_file, Ledger("dsh"), not args.no_llm))
    t = dump(leds, args.out)
    sys.exit(1 if t[FAIL] else 0)

if __name__ == "__main__":
    main()
