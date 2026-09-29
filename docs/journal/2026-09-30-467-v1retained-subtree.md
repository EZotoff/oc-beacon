# #467 V1外部注入不渲染定罪+retained-subtree探针补盲（2026-09-30）

> 状态：进行中
> 关联：（spec 路径，若有）·（issue 编号，若有）
> 来源：用户反馈 / grilling / E2E / 顺带发现

<!-- 过程中的取证/验证证据直接写本文件；backlog.md 只留 ≤3 行卡片。 -->

## §1 开工侦查：盲区定案 + 定罪设计（零新探针代码）

**盲区定案**（修正 471④ 会话遗留怀疑）：A11yDiag 是**组合门控**探针（MarkdownContent.kt:569/664/714 都在组合体内）——内容经 preParsed/pilot state 对象流动时外层组合体跳过重组 → A11yDiag 静默但渲染照常。内容级探针实际在响：r3 日志（/tmp/471x-r3-full.log）03:04:30 后 ChunkDiag 38 条、全文 MDPilot 243 条、A11yDiag path=render 全程 0 条。结论：无真盲路径；A11yDiag 语义=组合事件，ChunkDiag/MDPilot=内容事件（#473 注记待精化，非本批必改）。

**定罪链条**（现有探针够用，不新增代码）：
- L0 wire：宿主侧第二订阅者 `curl -N 127.0.0.1:4199/global/event`（服务端是否真发）
- L1 收到+解析：`[msg] MessageUpdated sid=… role=…`（MessageEventHandler:483）
- L1.5 流式增量：`[flush] deltas=N`（:397）
- L2 持久化：run-as Room 直查
- L3 渲染：MDPilot/ChunkDiag + 截图视觉（不信 dump，#477 污染在案）
- 连接态：Connected/Disconnected + death-snapshot lastEventAgoMs（重连时打）

**关键代码事实**：#441-A2 流式期望哨兵只接 DSH 帧源（SseConnectionManager.kt:136-144 dshFrameSources.forEach，v1 legacy SSE 无哨兵）；#267 REST 传输失败回灌踢重连只在 app 自己发请求时触发——与 #467「注入零渲染但用户自发消息不受影响」假设吻合。

**实验矩阵**（v1 免费档 nemotron，数数式 prompt）：
- **S0 基线**：健康连接注入 prompt_async → 期望全链渲染；若即零渲染则 bug 确定性，当场定罪
- **S1 黑洞**：iptables 对 4199 ESTABLISHED 连接 DROP（新连接放行→注入 curl/wire 证据不受影响），窗口内注入 → 期望 app 零渲染 + wire 有事件 + [msg] 零新行 = 「静默死连接」定罪
- **S1b 黑洞内 app 发消息**：验证「用户自发触发恢复」差异（REST 回灌踢重连）
- **S2 恢复**：删规则 → 读超时/退避梯子/death-snapshot lastEventAgoMs（巨大值=静默死铁证）/SSE_PRIORITY backfill 自动补齐？——「重启即恢复」是否仍真

## §2 四场景定罪（S0/S1b/S2）+ 根因闭环

**S0 基线（健康连接注入）**：03:30:22 prompt_async=204（数 1-40）→ wire 6×message.updated+41×part.delta+9×part.updated → app `[msg]` MessageUpdated 计数实质增长、`[flush] deltas` 2→7、MDPilot 27→36 → 全链渲染正常。**外部注入本身无罪**。

**S1b（reverse --remove 伪断连）**：03:33:29 撤销 reverse 监听 → 既有隧道连接仍活了 ~14s（MessageUpdated 23→33 真实增长，41-80 轮流式到 03:33:43.452 戛然而止）→ 之后进入静默死亡。教训：`reverse --remove` ≠ 断连（同 471④ kill-server 结论族）。

**S2（kill-server 真断连窗口注入）**：03:37:01 kill adb-server → 03:37:02 注入（81-120 轮）→ 服务端照常完成（wire 526→650 行）→ **app 全程零接收**（msg 计数冻结在 46）→ 03:37:52 复隧道 → 03:37:54 Connected → **03:37:55.2 `reconnect backfill: +6 msgs (SSE_PRIORITY)`** → msg 46→52、MDPilot 71→79 → **免重启自动补渲染**。卡片「重启即恢复」已过时——恢复链路本身健康，缺的只是检测。

**根因闭环（#441/#467 同根定罪）**：
1. death-snapshot `lastEventAgoMs=240737/247887`：最后事件 03:33:43.45 → 4 分 04 秒 app 连接层**零行为**（无超时日志、无退避梯子、无任何 SseClient 行）。
2. 全文 `SSE read timed out` 计数 = 0；历史 r2/r3 日志同样为 0（11 条连接生命周期）——**#108 withTimeoutOrNull(40s) 防护从未触发过**。
3. 一条 TCP 流论证：v1 心跳与事件同流交错（wire 实证心跳 ~6s/次持续发）；事件停 ⇒ 心跳停 ⇒ app 零字节接收。零字节下 40s 超时 4 分钟不返回 ⇒ `readByte()`（SseClient.kt:57）在 OkHttp engine 通道桥上的挂起**不可被协程取消抢占**。
4. Ktor `socketTimeout`（V1:185/V2:124 都配了 45s）按设计只封顶**响应头等待**，流中不生效——两侧注释自认「流中由 #108 应用层防护接管」，该假设正是今日证伪点。**V1、V2 双双裸奔**。
5. 03:37:44 的检测救场来自 `Reconnecting server ... after network recovery`（OpenCodeService 网络回调）——野外靠 MIUI 网络抖动 churn（r2/r3 各 213/235 次）碰运气恢复；无网络事件的静默死亡（NAT 黑洞/MIUI 杀 socket）→ #441「输出期间渲染静默」。

**修法方向（根修，待用户裁决开工）**：读循环改为「读任务 vs 超时」竞争——超时分支显式 `response.cancel()`（Ktor HttpResponse.cancel() 会取消底层 OkHttp call → 原生阻塞读立即解除）后 break 走既有梯子；或 SSE 专用裸 OkHttp 传输（ExportOkHttpClient 先例：okio source 超时对流读真实生效）。心跳契约已在服务端（~6s/次），客户端只需真执法 45s。

**方法学教训（记 #473 域）**：
- device 侧 grep 的命令文本会被 adbd `in ShellService:` 记入 logcat，下轮 poll 匹配到自身 → 每轮 +1 假增长；监控一律 pull 到宿主再 grep。
- 设备 nohup logcat 启动行的 pkill -f 模式会自匹配杀掉自己；pkill 只按进程名。
- 宿主 nohup 后台进程不挺过 run_code 退出；持久抓取用托管后台作业或设备侧落盘。

## §3 服务端真相 + UI 态补证 + 证据缺口记录

**REST 终态核验**（GET /session/{sid}/message，03:41）：三轮全部服务端完整完成——1-40（40 纯数字）/41-80（40 数字）/81-120（40 数字），各含 step-start/reasoning/text/step-finish。B 轮虽在 app 侧只收到 8s 增量，服务端全量完成；C 轮在 app 全盲窗口内完成。注入与流式在服务端零问题。

**UI 态补证（FSM 日志代替像素）**：03:33:43.444-445 `Busy/Streaming --SseStatus--> Idle [force-complete]` + `--SseIdle--> Idle` → **静默全程 UI 显示 Idle + 冻结的半截 B 轮**（无生成指示、无断连条幅）——与 #467 原始观感（底部旧内容/视口停中部/看起来「完成了但被截断」）完全同构。03:33:43.478 L3 探针 `absent from active but SSE fresh (33ms ago) -> keep current status` 是最后一条会话级活性证据。

**视觉验证缺口（诚实记录）**：/tmp/467s0.png、467s1.png、467s2.png（3.3/3.4/3.5MB，md5 5492468f…/c7a52fc9…/00c66285…）未能判读——本会话路由纯文本（read_image 网关拒绝）、子代理路由同拒、zai 视觉 MCP 401（key 失效，同 bigmodel 双 key 全灭族）、markitdown/playwright 旁路全封。定罪不依赖像素：探针链（[msg]/[flush]/MDPilot）+ REST 终态 + FSM 状态演化三层文本证据已闭环。截图原地保留，zai key 修复后可补视觉背书。

**结论与去向**：#467 定罪完毕——「V1 外部注入不渲染」= 静默死连接无检测的表现型，注入本身与恢复链路均无罪；根因与修法归 #441（P1）域：读循环竞争式超时 + `response.cancel()` 强杀底层 call（或 SSE 专用裸 OkHttp 传输）。待用户裁决修法开工批次。

## §4 修复批次：StreamStallWatchdog 加固落地 + 根因矩阵修正（重要反转）

**代码交付**：新增 `StreamStallWatchdog`（45s 字节级 stall 执法，poll 5s；触发双保险强杀：① response 自身 Job cancel → 引擎 cleanup 关 socket ② channel.cancel(cause) → 挂起读异常恢复）+ CancellationException 翻译防线（防强杀以取消形态浮出时被上层 catch 当主动取消跳过梯子）；V1/V2 双客户端接线，行级 markProgress 打点。单测 4/4（虚拟时钟：阈值单发/持续进展续命/半程静默阈值边界/onStall 异常吞噬）；全量回归 **3698/0/0**。

**E2E 五场景**（修复版真机 04:07-04:22）：
1. 健康流式：看门狗零误报（心跳 ~6s vs 阈值 45s = 7.5x 余量）
2. kill-server 干净拆链：EOF 即刻（+0.185s，ClosedByteChannelException←okio EOF）→ 梯子 → Connected 3s → 恢复 19/19 + `[persist] msgs=163`（含断连窗口注入轮 121-160）
3. reverse --remove ×3：黑洞均未形成（04:11 流式中移除转发存活 98s+ 并全程投递两轮注入；04:19/04:20 空闲移除两轮连接存活，PATCH 探针 0.8s 回证）
4. **SIGSTOP v1**（确定性零字节无 FIN）：**#108 行级 40s 超时精确触发**（04:15:26 STOP → 04:16:04.164 fire）→ 梯子（连接至冻结端口被 #305 socketTimeout 45s 正确封顶）→ SIGCONT 后 1.4s Connected + 19/19 恢复
5. 断连窗口注入全链两轮：服务端全量完成（REST 终态）+ 恢复后免重启补齐

**根因矩阵修正（推翻 §2 单一机制论）**：

| 死亡形态 | 字节态 | #108 行级 40s | 实测结果 |
|---|---|---|---|
| SIGSTOP 服务冻结 | 零字节无 FIN | ✓ 精确触发 | 秒级检测恢复 |
| kill-server 干净拆链 | 即刻 EOF | n/a（EOF 先到） | 即刻检测恢复 |
| reverse 僵尸拆链 | 未观测 | ✗（历史一次） | 4min04s 零检测挂死 |

「OkHttp 桥阻塞读不可取消」不再成立为普适机制——SIGSTOP 证明零字节停顿下 withTimeoutOrNull 正常返回。4min04s 僵尸挂死整夜仅一次（03:33 S1b）、4 次定向复现全部失败 = **adbd 拆链稀有竞态，机制未定**。野生复发判据已备：force-cancelling 日志在=字节级停顿（本看门狗接管）/不在而事件停=事件级丢失（另一形态）。

**交付定位（诚实）**：本批是**加固层**而非已证明根修——五场景零回归、零误报、恢复链三验全绿；但看门狗强杀效果未在真实僵尸模式上得到验证（该模式未再现）。#441 残余方向：①僵尸模式复发时抓包级证据（host lo 抓包需 root，sudo 不可用已排除）或 v1 侧订阅者状态；②若确认为「字节在流而事件选择丢失」，防御应上事件级哨兵（#441-A2 目前 DSH-only，v1 legacy 空缺——SseConnectionManager:141-142）。
