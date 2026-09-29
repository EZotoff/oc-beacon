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
