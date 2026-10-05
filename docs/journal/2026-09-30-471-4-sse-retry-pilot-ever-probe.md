# #471④ SSE retry 恢复 pilotEverRendered 丢失——复现确认调研（未裁决）

> 2026-09-30 02:00-02:25 真机调研批次。任务：用户指令「修复之前需要分析与确认此问题确实还存在」。
> 结论：**塌缩机制代码可达性仍在，v2 双场景未复现 ever 丢失**；待用户裁决（见 §5）。

## 1. 背景与④的原始定罪

- #471④ 出处（2026-09-30 #472 验收轮发现）：「重连后内容跳变重组期 pilotEverRendered 丢失(ever true→false 实证)→塌缩 -8575px 仍现，仅断连续传时发生，正常轮次不受影响」。
- 原始观测在 #472 修复装包**之后**（v1 4199 服务器路径，t_msg_0e641a99a001 即 v1 会话）。
- #472 的 `pilotTerminalHold`（[StreamingMarkdownPilot.kt:138-141](../src-dne)）只在 `pilotEverRendered=true` 时拦截——**ever=false 的重建帧无防御**。

## 2. 静态定罪链（当前 HEAD = fb9acfcd 复核成立）

```
组合位销毁（StepGroup 窗口化弃树 / 分支换 / partId 变 / part.text 短暂 blank）
→ MarkdownContent remember 归零：pilotEverRendered = false
→ 重连 backfill 全量替换 + RestValidation force-complete（完结态 asyncParse=true）
→ len > ASYNC_PARSE_MIN_CHARS(2048) 且 registry miss（preParsedState=null）
→ asyncTerminal 创建，首帧 State.Loading ≈0 高
→ hold=false（ever=false 不拦截）→ Loading 塌缩帧露出 → Success 弹回 = -8575 族两连跳
```

关键代码位：
- [MarkdownContent.kt:625-660](../../app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/markdown/MarkdownContent.kt)：`var pilotEverRendered by remember { mutableStateOf(false) }`（无 key，组合位销毁即丢）；asyncTerminal 条件创建 + Loading 首帧。
- [PartContent.kt:157-166](../../app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/components/PartContent.kt)：`if (part.text.isNotBlank() && synthetic!=true && ignored!=true)`——text 空窗即销毁路径之一。
- [RenderReadiness.kt:63-](../../app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/components/RenderReadiness.kt)：registry 按 partId 键控；remove() 滚出视口注销；**partId 变化 = miss**。

## 3. 真机实验（v2 4096，设备 houji，APK = HEAD fb9acfcd 即 #478 收口版）

### 实验 A：服务端断连重启（systemctl stop/start opencode-v2）

流式 5960 字中断连（stop 6s 后 start）。黄金序列（logcat 02:05:36-38）：

```
02:05:36.840 SseClientV2: V2 SSE stream opened
02:05:36.93-37.2  reconnect backfill：5 会话全部回填（wine 会话 +2 msgs SSE_PRIORITY）
02:05:37.200 Busy/Streaming --RestValidation--> Busy/Waiting
02:05:37.225 path=pilot hold=false ready=false len=5960    ← pilot 存活（ever=true）
02:05:37.276 path=pilot hold=true  ready=false len=5961    ← #472 hold 桥接启动 ✓
02:05:37.297 RESIZE 16886→16982 (+96)                       ← pilot 终帧保持
02:05:37.354 path=render src=asyncTerminal stateType=Success ← async 就绪换装
02:05:37.389 RESIZE 16982→15652 (d=-1330)                   ← 残余塌缩：归一化差（③族）
02:05:37.893 Busy/Waiting --RestValidation--> Idle [force-complete]
```

**判定**：hold 拦截成功，ever 未丢失，无 -8575；残余 -1330px = pilot 流式原文 vs async 归一化后高度差（#471③ 同族量级）。

### 实验 B：链路断+服务端续生成（adb kill-server 掐链，服务端继续写完）

巧克力轮 len=2522 时掐断 → 服务端续写至 5144 → 18s 后恢复链路 → 重连大跳变（logcat 02:08:31）：

```
02:08:31.016 [ses_f11a6f288] reconnect backfill: +1 msgs (SSE_PRIORITY)
02:08:31.055 path=pilot hold=false ready=false len=2523    ← 断连时刻旧内容
02:08:31.643 Busy/Waiting --RestValidation--> Idle [force-complete]
02:08:31.650 path=pilot hold=true  ready=false len=5144    ← 跳变 2523→5144，hold 拦截 ✓
02:08:31.672 RESIZE 7040→7064 (+24)
02:08:31.722 path=render src=asyncTerminal stateType=Success
02:08:31.751 RESIZE 7064→13033 (d=+5969)                   ← 正向追平（无塌缩）
```

**判定**：内容跳变（2523→5144，前缀一致增量）在 pilot 内被 freeze 挡住，换装 Success 全高，**零负跳**。+5969 为断连期间错过的内容一次性追平（正向，非 bug；帽协议只管流式增长，终态帧不受其约束——观感问题可并入③批次评估）。

**两实验共同点**：v2 backfill 的 partId 稳定 → MarkdownContent 组合位不销毁 → ever=true 保持。**④ 的 ever true→false 未复现**。

## 4. 未验证路径（诚实声明）

1. **v1 事件序列**：v1 SSE `message.updated`（全量 parts 重发）与 v2 `message.part.updated` 事件形状不同；若 v1 reconciler 替换 parts 时 partId 变化 → 组合位销毁 → ever 丢失路径可能仍活。**无法验证：v1 环境 bigmodel key 已 401 失效**（同 key 09-29 16:23 「Largest 25 countries」轮尚成功，17:39 起 401——key 被服务端吊销/过期，与代理无关：proxy/direct 双路均 401）。
2. **弃树重建三联**（流式中断连 + 滚动离屏 + 重连）：需真流配合滚动窗口。v2 免费档 longcat-2.5-preview-free 当日 7+ 轮后被限流——「飞天梦」轮 13 分钟 TTFB 挂死（assistant len=0 无 error，服务端 LLM 队列无限等待），未完成。

## 5. 裁决请求（三选一，尚未裁决）

- **a)（我方推荐）防御性根修**：msgId 级「曾流式渲染」进程 LRU 账本（对齐 #437 `StepGroupLedgerStore` 先例）——重建帧查账补 ever=true → hold 拦截 Loading 塌缩。~30 行+单测；无论原始触发器是否残留都补掉 ever=false 防御缺口。
- **b) 恢复 v1 key 后补 v1 路径验证再定**（用户已指示下一步：查官方文档重新部署 v1 环境）。
- **c) ④ 降级 watch**（标注 v2 未复现）关闭本项。

残余观测归并建议：-1330（归一化差塌缩）与 +5969（正向追平单帧）→ #471③ 归一化重排批次一并评估。

## 6. 环境与工具发现（复用价值）

1. **adb reverse --remove 不掐已建立的 SSE/TCP 连接**——只挡新连接；已建流继续走（实验 B 前置试跑实证：remove 后 TextDelta/RESIZE 持续 20s+）。制造真断连用 `adb kill-server`（链路死、服务端活）或 `systemctl --user stop opencode-v2`（服务端也死）。
2. **run_code 进程退出会带走它 start 的 adb-server**（会话间不保活）——跨 run_code 操作设备前必须重新 `adb start-server` + `adb reverse`。
3. v2 API 前缀 `/api`（`/session` 是 WebUI HTML）：建会话 `POST /api/session -d '{}'` → `.data.id`；注入 `POST /api/session/{id}/prompt -d '{"text":"..."}'`（阻塞型，后台 nohup 保活——**curl 死则轮次可能被 abort**）；Basic Auth opencode + OPENCODE_SERVER_PASSWORD（从 v2 进程 /proc/environ 可读）。
4. v2 免费 fallback 模型 longcat-2.5-preview-free（zhipuai-coding-plan OAuth 失效时默认落到它）：TTFB 10s~2min 波动，当日多轮后限流挂死（无 error 挂队列）。**依赖它做时序敏感实验不可靠**。
5. v1 注入通道备忘（key 恢复后可用）：`POST /session`（`-d '{}'`）→ `.id`；`POST /session/{id}/prompt_async` body `{"parts":[{"type":"text","text":"..."}]}` → 204（异步，SSE 推流）。
6. 本会话测试残留（v2 服务端，均为实验会话可随时删）：ses_f11bbb90（茶文化/run1 401）、ses_f11b9198（茶文化 coding-plan）、ses_f11b68d9（四大发明+通史+steer 污染）、ses_f11b0c7b（咖啡史 7924 字 done）、ses_f11a98d4（葡萄酒史 5960 字，实验 A 载体）、ses_f11a6f288（巧克力史 5144 字，实验 B 载体）、ses_f11a2b430（飞天梦，挂死可删）。
7. run_code 环境：需显式 `export HOME=/home/leonardo`（debug-entry.sh set -u 会炸）；systemctl 需 XDG_RUNTIME_DIR/DBUS_SESSION_BUS_ADDRESS；设备 mDNS serial `adb-e69a99d8-yzT17Y (2)._adb-tls-connect._tcp`。

## 7. 会话末状态快照（供续接）

- 设备：app（dev 包 pid 16132）停在飞天梦会话（ses_f11a2b430），该轮服务端挂死；服务器连接已恢复（reverse tcp:4096 在）。
- v2 服务 active（02:05:35 重启后一直活着）；v1 服务 active 但 key 401。
- 下一批（用户已指示）：查 opencode 官方文档 → 重新部署 v1（换新 key/新鉴权）→ 复跑 v1 路径断连实验（v1 SSE message.updated 全量重发 → 观察 partId 稳定性与 ever 丢失）。
