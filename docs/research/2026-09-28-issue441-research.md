# #441 app SSE 长连接随机断连：输出期间渲染静默（服务端正常）——深度调研报告

> **一句话结论**：#441 的「输出期间渲染静默」是 **DSH 事件长连接的会话级/传输级死亡缺少应用层自愈**——最大结构性缺口是 follow 逻辑流收到 End/StreamError 帧后引擎无动作、followed 幂等集不清理、对账只在重连基线触发（DshRemoteMuxEngine.kt:282-287 / :161 / DshConnectionOrchestrator.kt:330-337），叠加 DSH 通道没有任何应用层活性哨兵（V1/V2 SSE 有 40s 心跳超时，DSH 只有传输层 ping）与 networkMonitor 注入未使用（SseConnectionManager.kt:84），测试环境再被 adb reverse 隧道静默死亡与电池优化限制放大；推荐「先装定罪探针（S）→ 落地逻辑流自愈+静默 watchdog（M）」，模块级重写（L）留作复发后的架构出路。

- 调研日期：2026-09-28 · 性质：只读调研（零源码修改）
- 卡片：backlog.md:83-85（P1，`bug` `dsh`）
- 术语澄清：本卡标题「SSE 长连接」是事件长连接泛称；**实证样本全部来自 DSH 线面**（WS `/api/remote.mux`，remote.mux 引擎），V1（SseClient）/V2（SseClientV2）SSE 线面同族风险单列于 §4.6。v2@4096 systemd 常驻 + Basic Auth 指 OpenCode V2 真实环境（backlog.md:80-81），与 DSH 服务是两个不同线面。

---

## 1. 问题陈述

**用户可感知症状**：
- assistant 输出（流式 turn）进行期间，app 渲染突然全静默——MDPilot 零 append、消息不再增长；
- 服务器侧一切正常：prompt 被受理（RPC accepted）、turn 照常完成并生成标题（backlog.md:84）；
- 恢复方式只有两种：**重启 app 立即恢复**；或等到某次重连成功后「渲染终稿」（一次性补出最终内容，中间过程丢失）（docs/journal/2026-09-25-437-streaming-md-stable-reveal.md:501）。

**触发条件**（已观测）：
- 随机、无固定操作前置；437 goal 轮次中呈「每会话第 3 turn 起必挂、新会话首 turn 秒起」的轮换模式（journal:336）——该模式后被判定为断连误诊（journal:394-395）；
- 毒害面双重：①测试通道——流式/滚动类真机取证反复被阻断（journal:358、:381、:522、:780）；②生产可用性缺陷（backlog.md:85）。

## 2. 已知历史（卡片 + journal 取证沉淀，逐条带引用）

1. **首次撞上与误诊（二十四世轮，2026-09-26 晚）**：流式卡顿归因取证期间发现「DSH 会话队列不稳：每会话第 3 turn 起 stall（续写类 prompt 必挂）；新会话首 turn 秒起」（journal:336）；当晚仪器验证连续被「DSH 队列 stall」阻断（journal:358、:381）。
2. **翻案定罪（二十五世轮，2026-09-26 深夜）**：用户裁决记录——「DSH 服务正常（app 长连接断连是独立 bug，登记 backlog；"队列 stall"实为断连误诊）」（journal:394-395）；同轮根修路线图把「app SSE 长连接断连（电池优化嫌疑）独立修复」列入 backlog（journal:429）。卡片 #441 随即登记（commit 802448f4，2026-09-26 20:22，git log -S 实证；backlog.md:83-85）。
3. **毒害实录（二十六~五十一世轮）**：S10/S11 第一 turn 均静默完成于服务端、重连后渲染终稿（journal:501）；S13 装机后复测被「断连/未渲染」阻断（journal:522）；「#441 markdown 稳态粒度」交付时仪器验证被断连通道阻断、以用户体感验收（journal:780）；Goal 终章挂账清单第 5 条即本卡「测试通道毒害源」（journal:789）；四十八/四十九世轮取证三连败——「通道断连/屏态漂移/IP 漂移交替毒害」，自动盲扫在该偶发症状上不可行（journal:903）。
4. **电池优化线索**：二十世轮方法论沉淀「会话上下文饱和（模型停摆）与电池优化掐连接是采样空转两大外因……用户侧建议关闭 app 电池优化」（journal:263）；二十八世轮真机实证 tap 判定窗内**电池优化横幅在场**（误点过「修复」按钮）（journal:470）——即卡片所写「横幅曾警告」（backlog.md:84），对应 BatteryOptimizationBanner.kt:30-58（HomeScreen 常驻，strings.xml:16-17「Background connections may be killed」）。
5. **同族已修部分（边界收窄）**：
   - **#436**：WS 401 → markAuthFailure → awaitCookie 永久挂起且不置 TokenNeeded（docs/research/436-reconnect-map.md §3/§5 候选1）——已修：401 先走持久化 token 重交换 recoverAuth 自愈（commit 8188a055；DshRemoteMuxEngine.kt:255-266 现码）；
   - **#447**：V2 探测 /api/health 404 → UNKNOWN → V1 线面吃 SPA HTML → SSE 0 事件即断 → 条幅常驻（handoff-oc-beacon-card-intervention.md §10.1）——已修：/api/event 线面探针（backlog.md:81，真机 v2 回路绿）；
   - **#448**：初判「SseClient.parseEvent 单帧即断流」经核码**勘误不成立**（V1/V2 parse 均 per-frame try-catch）；真实缺口（非 event-stream 无快检、坏帧无计数）已落地 SseProtocolMismatchException 嗅探 + 坏帧计数 + 冷却倒计时排程（commit c12f2fa4；docs/journal/2026-09-27-v2.md #448 迁入块）。**注意：#448 勘误明确划清了「SSE 解析断流」与本卡的边界——V1/V2 SSE 帧解析不是本卡根因。**
6. **今日调研交叉**：#442 调研把本卡列为「#442 真机复测的前置环境风险」并建议「A 批次开工前先确认流式通道稳定」（docs/research/2026-09-28-issue442-research.md:208、:257）；#438 调研的 shouldYieldPairing 死接线发现（ScrollCompensation.kt:366-369，docs/research/2026-09-28-issue438-research.md:123）属滚动配对域，**与本文档的 #441 不同域**，但其「状态记忆变量声明在每帧重置的作用域 → 防御从未生效」病理模式与本报告 §4.1 发现的「followed 幂等集跨流生命周期不清理」同族（状态作用域错误导致防御失活），方法论互鉴。
7. **编号分歧（如实标注）**：journal 四十三/五十一/五十二世轮标题中的「#441 markdown 稳态粒度/#441 稳态粒度」（journal:747、:750、:928、:948、:960）与 backlog.md:281「用户裁决（2026-09-26 #441 后续）」中的「#441」是**历史误标**——#441 编号在 2026-09-26 20:22 已由本卡（SSE 断连）占用（commit 802448f4 修改了 backlog 首段「下一编号：#442」），markdown 稳态粒度工作当时无卡直开、事后由 **#443**（backlog.md:280-281）承接。本报告一律以 backlog.md:83-85 现行文本为准。

## 2.5 历史取证 → 代码位置对照表

| 历史取证（journal/backlog） | 对应代码位置（现状） | 状态 |
|---|---|---|
| 二十五世轮「DSH 服务正常、app 静默」 | 事件源头=DSH WS 引擎（DshRemoteMuxEngine.kt:150-272）；渲染链完好（§3.4） | 待定罪（§4.1/4.2） |
| 「重连后渲染终稿」 | 重连代更替 → onOpen 重 follow + subscribed 基线 → reconcile 回填（DshRemoteMuxEngine.kt:197-221；DshConnectionOrchestrator.kt:330-337/:375） | 现有自愈仅此一条路径 |
| 「重启 app 即恢复」 | 进程级 scope 重建（SseConnectionManager.kt:95-105）+ 全新连接代 | 现象吻合 |
| 「每会话第 3 turn 起 stall」轮换模式 | 无会话级代码对应——通道级症状（支持 §4.3 环境因素而非会话状态机） | 已由 journal:394-395 定性为断连误诊 |
| 电池横幅在场（journal:470） | BatteryOptimizationBanner.kt:30-58（HomeScreen 常驻） | §4.4 线索 |
| #436 候选1「401 awaitCookie 永挂」 | DshRemoteMuxEngine.kt:255-266 已先走 recoverAuth | 已修（8188a055），残余见 §4.5 |
| #448「单帧即断流」 | SseClient/SseClientV2 parse per-frame try-catch | 勘误不成立；嗅探/计数已修（c12f2fa4） |
| gap 检测「后续可接恢复」 | SseConnectionManager.kt:127-131 仅观测未接线 | 本报告 §6.3 建议顺带消化 |

## 3. 现状代码走读（关键路径 → 历史取证对应点）

### 3.1 连接总入口与主循环（SseConnectionManager）
- 单例管理每服务器连接：构造注入 14 依赖（SseConnectionManager.kt:78-94），自持 `CoroutineScope(SupervisorJob()+IO)`（:95-105）——进程级存活，不随组件销毁。
- **`networkMonitor` 注入但全文件零调用**（:84 仅有构造参数；全仓 grep 唯一其它使用点在 OpenCodeConnectionService）——manager 自身不感知网络变化。
- 主循环 `runSseConnectionLoop`（:442-671）：冷却检查（:465-469）→ 握手探测（:490）→ DSH 分支（wireKind==MUX，:491-554）/ SSE 分支（:556-669）→ 指数退避（base 1s×2ⁿ，cap 30s/normal、5s/aggressive、60s/conservative，:825-837）+ 倒计时排程 `_reconnectAt`（:819-822，#409/#448 排程语义）。
- DSH 分支：AUTH_REQUIRED → recoverAuth/awaitCookie（:496-511，#436 修复后语义）；UNREACHABLE → 退避（:512-527）；ONLINE → `runDshEventLoop`（:536-541）**挂起直到取消**——「engine 自重连不返回」（DshConnectionOrchestrator.kt:300 注释），异常退出才落外层兜底退避（:545-554）。
- SSE 分支：V2/V1 客户端（:572-577）→ collect 首事件置 Connected + `backfillActiveForServer` 断连窗口补漏（:592-614）；流正常完成/异常 → 计数/退避/冷却（:616-669）；`recoverMessages` REST 快照补漏（:766-807 附近）。

### 3.2 DSH WS 引擎（DshRemoteMuxEngine，0.1.2 remote.mux）
- 专属 OkHttpClient，**pingInterval 25s**（:26、:78-80）——DSH 服务端零心跳、无空闲踢线，活性全靠客户端传输层 ping（DshWsEventClient.kt:31、:41 同款注释）。
- `muxLoop`（:150-272）：while(true) 连接 → onOpen 开 $events/session/control/workspace 四逻辑流 + 限界集 follow（:197-221）→ `closed.await()` 等断（:248-249）→ 401 特判 recoverAuth→awaitCookie（:255-266）→ 否则退避重连（:268-270，DshBackoff 500ms×2ⁿ cap 10s 带抖动，DshWsEventClient.kt:96-116）。
- **帧处理`handleMuxMessage`（:275-288）：`StreamError` 仅记 W 日志、`End`（逻辑流结束）完全无动作（:284-286）**；动态 follow 幂等集 `followed.add` 去重（:159-171）——**流已 End 的会话在本连接代内永不重开**（:161 `if (!followed.add(...)) return`）。
- 聚焦 follow（#333）：ChatRoute 进窗兜底 requestFollow（:140-147），同样受 followed 幂等集拦截。

### 3.3 编排与对账（DshConnectionOrchestrator）
- `DshProtocolRoutingFrameSource` 按协议选引擎（:132-197），引擎跑在自建 `CoroutineScope(SupervisorJob()+IO)`（:135）。
- `run`（:303-371）：帧 Channel(UNLIMITED) 串行消费；**`session/subscribed` 帧才置 pendingBaseline → settleMs 静默窗 → reconcile**（:330-337）；reconcile 用 subscribed 基线对比本地 seq 水位算缺口 → session.history 回填（:375 起）。**对账唯一触发时机 = （重）连接后的基线帧**，稳态期无周期对账。
- 每事件 seq 水位跨重连存活（SseConnectionManager.kt:118）；连接代内 gap（订阅队列溢出丢事件）只记录 gapDetected 观测、未接恢复（:127-131 注释「后续可接」）。

### 3.4 事件→渲染下游（症状呈现层）
- 帧回调在 OkHttp 读线程 → Channel → mapper → EventDispatcher.processEvent（SseConnectionManager.kt:415-428）；MDPilot/StreamingMarkdownState 前缀差分 append 管线（sse-scroll 铁律 1/2，AGENTS.md:104-113）。本卡症状=事件源头断供，下游管线无辜（服务端完成 + 重连后能渲染终稿证明渲染链完好）。

### 3.5 V1/V2 SSE 线面（对照物）
- 应用层 40s 心跳读超时 `readRawLineBytesWithTimeout`（SseClient.kt:25、:93-98；SseClientV2.kt:38、:178-183——半开 TCP 挂死 #108 防护）；socket 读超时封顶 45s（SseClient.kt:31）；SSE 请求 requestTimeout 置 MAX（SseClient.kt:196-200）。**即 V1/V2 线面「静默 40s = 主动断开重连」的应用层哨兵存在；DSH 线面没有对应物（仅传输层 ping）。**

### 3.6 宿主与网络层
- OpenCodeConnectionService：FGS（DATA_SYNC/SPECIAL_USE）+ PARTIAL WakeLock 续期（:611-650 附近，acquireWakeLock :619-638）；网络恢复 kick：`networkMonitor.networkState.debounce(2s).distinctUntilChanged()` 仅在 **Available** 且有活跃服务器时 `reconnectAll()`（:209-218）——**Available→Available 的网络切换（WiFi 换 AP/路由变化）被 distinctUntilChanged 吞掉，不 kick**。
- NetworkMonitor：ConnectivityManager 回调 → 四态 StateFlow（NetworkMonitor.kt:71-110）；无 network id/属性变化信号。
- DI HttpClient（RPC/REST 用）：OkHttp engine（铁律，AGENTS.md:114）+ HttpTimeout 120s（NetworkModule.kt:80-84）——**WS 引擎不共用此 client**（自建 pingInterval client，DshRemoteMuxEngine.kt:78-80），120s requestTimeout 不作用于 WS。

### 3.7 DSH 事件通道生命周期（现状图解）

```
runSseConnectionLoop (SseConnectionManager.kt:442)
  └─ probe ONLINE → runDshEventLoop (:536，挂起直到取消)
       └─ DshConnectionOrchestrator.run (:303，帧消费循环)
            └─ DshRemoteMuxEngine.muxLoop (:150，while(true))
                 ├─ connect → onOpen → 开 $events/session/control/workspace + 限界集 follow
                 ├─ closed.await() ←─ 仅 onFailure/onClosed 唤醒  ←【半开 TCP 靠 ping 25s 兜底】
                 │     ├─ 401 → recoverAuth →（失败）awaitCookie 挂起   ← #436 已修主路径
                 │     └─ 其他 → DshBackoff(0.5s×2ⁿ cap 10s) → 重连
                 └─ 帧分发 handleMuxMessage (:275)
                      ├─ Item → 合成器 → orchestrator frames → dispatch → 渲染
                      ├─ StreamError → 仅记 W 日志          ←【无自愈 · §4.1】
                      └─ End → 无动作（followed 不清理）     ←【无自愈 · §4.1】
会话级对账 reconcile：仅由「(重)连接后的 subscribed 基线 + settle 静默窗」触发（:330-337）
```

**图解**：传输层断线有完整自愈链（ping→onFailure→退避→重连→重 follow→对账）；**逻辑流层断线（End/StreamError）与事件泵静默（socket 活但无帧）两条路径没有任何出口**——即 §4.1/§4.2 两个结构性缺口的图形化表述。V1/V2 SSE 线面另有一条应用层出口（40s 心跳读超时=主动断开重连，SseClient.kt:93-98），DSH 线面无对应物。

## 4. 根因分析（未解决部分；每条标注证据与置信度）

### 4.1 【高·结构性缺口】follow 逻辑流级死亡无自愈（主嫌疑）
**链**：服务器对某会话 follow 流发 End/StreamError（如订阅队列溢出、会话状态机边界、durable 恢复重放）→ 引擎无动作（DshRemoteMuxEngine.kt:284-286）→ `followed` 幂等集不清理（:161）→ 该会话在本连接代内事件永久静默 → 对账只在重连基线触发（DshConnectionOrchestrator.kt:330-337），稳态无补救 → 直到整条 WS 代更替（重连/重启 app）才重 follow + reconcile 补终稿。
**与症状吻合点**：WS transport 可保持 Connected（ping 通、横幅绿、RPC 正常）而「MDPilot/渲染全静默 + 服务端 turn 照常完成 + 重启 app 即恢复 + 重连后渲染终稿」（backlog.md:84；journal:501）——全部现象一次解释。
**证据**：上述代码走读；服务端确有事件丢弃形态（订阅队列溢出，SseConnectionManager.kt:127-131 注释）。
**置信度**：缺口存在=高；是否为 S10/S11 观测事件主凶=中。**待验证**：复现窗口 `logcat -d | grep -E "流错误 streamId|remote.mux 断开|丢弃无法解码"`——若静默窗内有「流错误/end」而零「断开」，即坐实；反之若「remote.mux 断开（连续失败 N）」刷屏则主凶转向 4.3/4.4。

### 4.2 【高·结构性缺口】DSH 通道无应用层活性哨兵
**链**：pingInterval 25s 只证明 socket 往返活，不证明事件泵/逻辑流在供帧；busy/Streaming turn 期间事件静默（>40s 级）无任何检测与动作。V1/V2 SSE 有 40s 应用层哨兵（SseClient.kt:93-98），DSH 无对应物；DSH 服务端零心跳（DshWsEventClient.kt:31、:41 注释自认「doze 冻结 socket 靠重连兜底」——但重连依赖 onFailure，事件泵 wedged 不触发 onFailure）。
**证据**：两条引擎代码（DshRemoteMuxEngine.kt:178-272 无任何帧龄检查）；SessionStateService 持有 busy/Streaming 真相（AGENTS.md:65）却无消费者做通道健康判定。
**置信度**：缺口=高；作为独立诱因（事件泵 wedged 而无 End 帧）=中。**待验证**：DEBUG 探针记录「最后帧龄/连接态/会话 streaming 态」三元组，静默窗内若 连接态=Connected 且帧龄持续增长即坐实。

### 4.3 【高·测试环境放大器】adb reverse 隧道静默死亡 / IP 漂移 / 死 transport
**链**：reverse 隧道或无线调试通道退化 → **长连接 WS 死而新建 RPC 可用**（或全部拒绝）→ 前者完美复刻「RPC accepted + 渲染静默」，后者复刻「整 turn 静默直到外部重建通道」；恢复脚本（debug-entry/hard-restart）顺带重建 reverse →「重连后渲染终稿/重启即恢复」。生产用户不走 reverse，但同族=网络切换/NAT 重绑定。
**证据**：reverse 因 wifi/USB 抖动**静默丢失**、每轮 E2E 前必查（docs/journal/2026-08-21-p1-p2-dev-batch.md:172）；mDNS 死 transport 致 reverse **静默失败**、每次 adb 重连后失效（handoff-oc-beacon-card-intervention.md §10.5）；四十八/四十九世轮「IP 漂移」交替毒害（journal:880-910、:903）。
**置信度**：作为 437 轮次测试通道毒害主因=高；作为生产缺陷根因=不适用（生产另有 4.4/4.5）。**待验证**：复现窗口前后 `adb reverse --list` 对照（handoff §10.5 现成脚本 /tmp/check-rev2.sh 思路）。

### 4.4 【中·卡片原始怀疑】电池优化/MIUI 省电冻结进程
**链**：电池限制激活（横幅在场实证，journal:470）→ MIUI 冻结/杀后台（FGS+PARTIAL WakeLock 在场，OpenCodeConnectionService:619-638，但 MIUI 深度省电可绕过）→ OkHttp 读写线程全冻结 → 无 ping、无 onFailure、无重连、无渲染 → 一切自愈机制同时失效 → 重启恢复。
**证据**：journal:263（「电池优化掐连接」方法论沉淀）+ journal:470（横幅在场）+ backtick 卡片原文「疑电池优化杀后台 socket（横幅曾警告）」（backlog.md:84）。反面证据：437 取证时 app 多在前台/自动化驱动中，冻结通常针对后台——不完全自洽。
**置信度**：贡献因子=中，主凶=低-中。**待验证**：复现窗 `dumpsys deviceidle` / `dumpsys battery` + 冻结前后 logcat 时间戳断层；或引导用户「无限制」电池设置后观察复发率。

### 4.5 【低·残余面】鉴权/协议族已修后的小残差
- 401→recoverAuth 自愈已落地（DshRemoteMuxEngine.kt:255-266），但 recoverAuth 失败仍落 awaitCookie 无限轮询（:263-265，DshConnectionRegistry 实现）——436 候选1 残余面（低概率：restart 即恢复且无需重输 token 与该形态矛盾）。
- V2 探测/#448 族已修（backlog.md:81；c12f2fa4）；V1/V2 SSE 帧解析确认无罪（#448 勘误）。
**置信度**：低。**待验证**：logcat `DSH 0.1.2 token required` / `remote.mux 401`（436-reconnect-map.md §5 判别式）。

### 4.6 【高·顺带发现】网络切换不 kick（Available→Available 盲区）
`networkMonitor` 注入 SseConnectionManager 零使用（:84）；服务层恢复 kick 只看 Available 转换且 debounce 2s + distinctUntilChanged（OpenCodeConnectionService.kt:209-218）——**同态网络切换（WiFi↔WiFi、路由迁移）零信号**；reverse 隧道死亡更不产生任何 ConnectivityManager 事件。生产「网络切换后长连接半开」依赖引擎 ping 超时兜底（≤~50s），逻辑流死亡（4.1）则完全无兜底。
**置信度**：高（代码直接可证）。

### 4.7 根因链整合（工作假设）
生产路径大概率=**4.1 或 4.2 单独/叠加**（偶发逻辑流死亡或事件泵静默），**4.6 放大恢复延迟**，**4.4 在后台/锁屏场景叠加**；437 轮次测试通道毒害=**4.3 为主、4.1/4.2 叠加**。四条链共享同一「修复杠杆」：会话级活性哨兵 + 逻辑流自愈 + 周期对账（§5 方案 A）对 4.1/4.2/4.3/4.6 全部有效，对 4.4 部分（进程冻结时哨兵也冻结，但恢复后立即补救而非无限挂死）。

### 4.8 判别矩阵（复现窗口内可完成的分诊，配合方案 B 探针）

| 观测项 | 4.1 逻辑流死 | 4.2 事件泵静默 | 4.3 reverse/隧道 | 4.4 进程冻结 | 4.5 鉴权 |
|---|---|---|---|---|---|
| 横幅连接态 | 绿（Connected） | 绿 | 红（重连中/倒计时） | 停更（冻结期） | TokenNeeded/Connecting |
| logcat「remote.mux 断开」 | 0 条 | 0 条 | 刷屏递增 | 0 条（冻结期无日志） | 前置 401 记录 |
| logcat「流错误 streamId=」 | ≥1 条（关键证据） | 0 条 | 无关 | 冻结期无 | 无关 |
| 帧龄探针（方案 B 装后） | 持续增长 | 持续增长 | 连接失败无帧 | 探针整体停更 | 连接失败 |
| 同窗 RPC（curl/发送） | 通 | 通 | 拒绝或通（视隧道形态） | 不可发 | 401 |
| adb reverse --list（真机环境） | 正常 | 正常 | 失效/陈旧条目 | 正常 | 正常 |
| logcat 相邻行时间断层 | 无 | 无 | 无 | 大断层（分钟级） | 无 |

## 5. 修复方案空间
### 方案速览

| 方案 | 一句话 | 影响面 | 风险 | 工作量 | 覆盖根因链 |
|---|---|---|---|---|---|
| B 定罪探针 | 先观测后动手 | DEBUG 日志 | ≈0 | S | 不修复，供分诊 |
| A 增量自愈 | follow 状态机 + 静默哨兵 + 切换 kick | DSH 连接域 4 文件 | 中低 | M | 4.1/4.2/4.3/4.6（4.4 部分） |
| C 模块重写 | 三线面统一 EventTransport 抽象 | 整个连接域 | 高 | L | 全部 + 架构根治 |



### 方案 A（推荐·增量自愈）：逻辑流生命周期 + 应用层静默哨兵 + 网络切换 kick
1. **A1 follow 流状态机**：引擎把 followed 从 Set 升级为 sessionId→流状态（active/ended/failed）；收到 End/StreamError → 移出幂等集 + 退避重开（带次数上限）；连续失败 → 置「half-open」并主动触发 reconcile（DshRemoteMuxEngine.kt:159-171/:282-287 改造）。
2. **A2 事件静默哨兵**：orchestrator 增加 watchdog——SessionStateService busy/Streaming 会话存在时，N 秒（建议 90-120s，> provider 中继 22s 零 delta 实测上限 journal/2026-09-27-v2.md「22s 零 delta」+ 余量）无该会话帧 → 主动 reconcile 探测（session.history 对比水位）；连续 2 轮无进展 → 整连接代更替（复用 reconnectServer）。
3. **A3 网络切换 kick**：networkMonitor 增 network id/transport 属性信号（NetworkMonitor.kt:91-109 onCapabilitiesChanged 已有钩子）→ 切换即 reconnectAll；SseConnectionManager 的未用注入要么接线要么删除。
- **影响面**：DshRemoteMuxEngine / DshConnectionOrchestrator / SseConnectionManager / NetworkMonitor + OpenCodeConnectionService kick 点；UI 零改动。
- **风险**：中低——A2 需防「provider 慢思考」误杀（哨兵阈值 > 40s SSE 心跳语义、只探测不强断）；A1 防重开风暴（退避+上限）；不碰 SSE 滚动四铁律（AGENTS.md:104-113 全在渲染管线，本方案在连接/事件域）、不换 engine（AGENTS.md:114 维持 OkHttp）、不违反 SessionStateService 单一真相源（watchdog 只读 statusFlow，AGENTS.md:65）。
- **TDD 切入**：①先写 `FollowStreamLifecycleTest`——End 帧后同 session 可重开、幂等集清理、退避上限（钉 A1 不变量）；②`SilenceWatchdogTest`（假帧源+虚拟时钟 advanceTimeBy）——Streaming 会话静默 90s → reconcile 恰好一次、idle 会话不触发、provider 慢思考窗口（<阈值）不触发（钉 A2 不变量）；③ `NetworkSwitchKickTest`——同态切换触发 reconnectAll（钉 A3）。

### 方案 B（前置·定罪探针）：先观测后动手
DEBUG-only 连接健康探针：连接代 id、最后帧龄（100ms 粒度限频）、followed 集快照、End/StreamError 计数、onFailure 计数、横幅态对照；每 30s 汇总一条 AppLogger。配套用户现场定罪协议（复现时报「会话+时刻」→ logcat 直判 4.1/4.2/4.3/4.4 哪条链）。理由：#441 偶发 + 437 自动盲扫三连败教训（journal:903「自动盲扫在该偶发症状上不可行」）+ diagnosing-bugs 方法论。
- **影响面**：仅 DEBUG 分支 + 日志；风险≈0。**TDD 切入**：探针纯函数（帧龄/状态归并）单测。

### 方案 C（激进·重写）：DSH 连接域统一「事件传输抽象」
把 V1 SSE / V2 SSE / DSH WS 三线面收拢为统一 `EventTransport` 契约（connect / frames / **liveness（接口级强制：每线面必配应用层哨兵）** / reconcile / kick 五职责）；DSH 引擎重写为「传输重连 + 会话订阅状态机（subscribed/active/ended/failed/half-open）+ 周期对账」三件套，follow 生命周期显式建模；网络监听下沉为 transport 信号源；SseConnectionManager 退化为路由+聚合。消灭「三套 liveness 语义、三套重连参数」的结构性根源。
- **影响面**：大——SseConnectionManager DSH 分支、orchestrator、两引擎、服务层 kick；触碰 #436/#447/#448 已修语义（需全量回归其测试）。
- **风险**：高（连接域是 #436 探针分类、#448 协议嗅探、seq 水位、对账回填等多张已修卡的承重区）；收益是架构级根治 + 三线面一致可观测。
- **与铁律兼容性**：仍用 OkHttp（WS 原生 OkHttpClient + SSE 走 Ktor OkHttp，AGENTS.md:114）；渲染四铁律不动；SessionStateService 真相源不动。
- **TDD 切入**：先写契约测试 `EventTransportContractTest`（liveness 必配、帧序保真、reconcile 幂等三不变量）+ 三实现逐线面迁移（V2 先行——最简、已有哨兵语义）。

## 6. 建议

1. **先 B 后 A，C 挂账**：B（S，1 批次）立刻收口定罪探针——437 三连败证明盲修浪费轮次；A（M，2-3 批次：A1 状态机 → A2 哨兵 → A3 kick + 各自真机验证）对 4/5 条根因链有效，且每步独立可验、可回滚；C（L，4+ 批次）仅在 A 落地后仍复现、或 #439/#442 系真机复测持续被通道毒害时启动（届时与 #442 的 flush 深拆共享「先探针后动手」纪律）。
2. **真机验证协议**（对齐 AGENTS.md:95-97 验证铁律 + docs/device-testing.md）：复现窗采集 logcat（探针 + 「流错误/end/remote.mux 断开」）、`adb reverse --list`、`dumpsys deviceidle` 三件套；判据=「静默窗内哨兵触发 → 会话事件恢复/终稿补出」且无新增重连风暴（ onFailure 计数不翻倍）。
3. **修复时同步消化**：A3 顺带处理 networkMonitor 死注入（接线或删除）；A1 顺带评估 gapDetected 接恢复（SseConnectionManager.kt:127-131「后续可接」）。
4. **关联卡片动作**：本卡修复后通知 #442（环境前置解除，issue442-research:257）；与 #436/#447/#448 的边界已在 §2.5 划清，勿重复修；#452（chatEntries remember key 恒等冻结条目）是「渲染静默」另一独立机制，症状同族、机制不同，排查时先看探针帧龄即可分辨（帧在流=渲染层 #452 族；帧静默=本卡）。
5. **预估工作量**：B=S（1 批）；A=M（2-3 批）；C=L（4+ 批）。

## 7. 引用清单

**backlog / 仓库根**
- backlog.md:83-85（#441 卡片）；:80-81（#447/#448 族与 v2 systemd 4096 环境）；:280-281（#443 承接旧「#441 稳态粒度」误标）
- AGENTS.md:65（SessionStateService 单一真相源）；:95-97（验证铁律）；:104-113（SSE 滚动四铁律）；:114（Ktor OkHttp engine 铁律）
- handoff-oc-beacon-card-intervention.md §10.1（#447 根因链）、§10.2（#448 副产品与本卡同族疑）、§10.5（reverse 静默失败/死 transport/每次重连失效）
- git：802448f4（#441 登记，2026-09-26 20:22）；8188a055（#436 token 自愈）；7d8ae62b/c12f2fa4/e33dec01（#447/#448/服务化，docs/journal/2026-09-27-v2.md 修复段）

**journal**
- docs/journal/2026-09-25-437-streaming-md-stable-reveal.md:263（电池掐连接方法论）、:306-341（二十四世轮+队列 stall :336）、:358/:381（验证被 stall 阻断）、:392-429（二十五世轮翻案 :394-395、登记 :429）、:470（电池横幅在场）、:471/:481/:501（S10/S11 静默+重连渲染终稿）、:522（S13）、:608、:780（稳态粒度验证被阻断）、:789（终章挂账）、:880-910/:903（取证三连败/IP 漂移）、:747/:750/:928/:948/:960（「#441 稳态粒度」误标史）
- docs/journal/2026-09-27-v2.md（重连条幅+#447/#448 修复记录；#448 勘误迁入块；zhipuai 中继 22s 零 delta 实测）
- docs/journal/2026-08-21-p1-p2-dev-batch.md:172（reverse 静默丢失）
- docs/journal/2026-08-30-dsh-integration-and-disconnect-design.md:72（DSH 编排/水位/每服务器独立 WS 引擎设计原案）

**既有调研**
- docs/research/436-reconnect-map.md（全篇：断连→重连路径测绘、§3 鉴权重灾区、§5 根因候选排序）
- docs/research/sse-scroll-stability-iron-laws.md（SSE→UI 管线与四铁律——本卡修复不得触碰）
- docs/research/2026-09-28-issue442-research.md:208、:257（#441 为 #442 前置环境风险）
- docs/research/2026-09-28-issue438-research.md:123（shouldYieldPairing 死接线——不同域、同「状态作用域错误」病理模式）

**源码（行号为 2026-09-28 HEAD）**
- app/src/main/kotlin/dev/leonardo/ocbeacon/service/SseConnectionManager.kt:78-105（构造/scope）、:84（networkMonitor 未使用）、:118（seq 水位）、:127-131（gap 检测仅观测）、:236-269（start/stopConnection）、:317-364（reconnectAll/reconnectServer）、:406-429（runDshEventLoop）、:442-671（主循环；DSH 分支 :491-554、SSE 分支 :556-669）、:766-807（recoverMessages）、:789-812（updateServerConnected）、:819-837（退避+排程）
- app/src/main/kotlin/dev/leonardo/ocbeacon/data/api/dsh/DshRemoteMuxEngine.kt:26（ping 25s）、:78-80（client pingInterval）、:140-177（follow 幂等集 :159-171）、:150-272（muxLoop；401 分支 :255-266；退避 :268-270）、:275-288（**End/StreamError 无动作 :284-286**）
- app/src/main/kotlin/dev/leonardo/ocbeacon/data/api/dsh/DshConnectionOrchestrator.kt:132-197（帧源路由/scope :135）、:300-371（run；subscribed→settle→reconcile :330-337）、:375 起（reconcile）
- app/src/main/kotlin/dev/leonardo/ocbeacon/data/api/dsh/DshWsEventClient.kt:31/:41（服务端零心跳/doze 注释）、:96-116（DshBackoff）、:134-135（0.1.1 pingInterval）
- app/src/main/kotlin/dev/leonardo/ocbeacon/data/api/SseClient.kt:25/:31/:93-98（40s/45s 心跳哨兵）、:196-200（SSE requestTimeout=MAX）
- app/src/main/kotlin/dev/leonardo/ocbeacon/data/api/v2/SseClientV2.kt:38/:51/:178-197（V2 同款哨兵+活跃期不发 heartbeat 语义）
- app/src/main/kotlin/dev/leonardo/ocbeacon/data/api/NetworkMonitor.kt:71-110（回调语义，无切换信号）
- app/src/main/kotlin/dev/leonardo/ocbeacon/service/OpenCodeConnectionService.kt:209-218（Available-only kick + debounce/distinct）、:611-650（WakeLock）
- app/src/main/kotlin/dev/leonardo/ocbeacon/service/ConnectionLifecycleCoordinator.kt:85-150（connect/disconnect 显式编排，无后台自动拆线）
- app/src/main/kotlin/dev/leonardo/ocbeacon/di/NetworkModule.kt:66-89（DI HttpClient 120s 超时；WS 不共用）
- app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/home/components/BatteryOptimizationBanner.kt:30-58 + app/src/main/res/values/strings.xml:16-17（电池横幅）