# 2026-09-28 · #454 v1 真机 IME 换行注入后 prompt 未发出 — 根因定位调研

> 关联卡片：backlog #454（P3，`chat` `device` `v1`）· 调研性质：只读根因定位，不改代码
> 调研方法：backlog/journal 考古 + 发送链路源码逐环节走读 + 假设链排序（diagnosing-bugs 思路）

## 1. 问题陈述（症状）

- **观察现场**（backlog.md:266-267；docs/journal/2026-09-27-v2.md:180）：2026-09-27 #453 真机验证（v1@4199，CARD-R2 会话）顺带观察——首次发送用 `adb shell input keyevent 66`（ENTER）向输入框注入换行（消息内容「Run\n\n」）后点发送，**prompt POST 未发出**（服务器侧无 prompt_async 日志）、**乐观气泡悬挂**；紧接着二次「干净发送」正常（服务器 prompt_async 202）。仅复现一次，未再触发。
- **拆成三个可检验事实 + 一个矛盾点**：
  1. 事实 A：首次发送链路未产生 HTTP POST（证据=服务器侧，非客户端日志推断）；
  2. 事实 B：二次发送同一链路完全正常 → 链路无持久性损坏，是**瞬态条件**；
  3. 事实 C：UI 出现过一个「悬挂」的用户气泡观感；
  4. **矛盾点（关键疑点）**：V1 发送路径代码上**不存在本地乐观播种**（见 §3.7-3.8），气泡若真在场，只能来自 SSE 回显——而 SSE 回显意味着 POST 到达过服务器，与事实 A 冲突。此疑点本身是复现验证的第一目标。

## 2. 已知历史

- **观察源头**：#453 思考卡计时验证批次（docs/journal/2026-09-27-v2.md:172-180）。当日环境 v1 1.18.32@4199（同文件 :16）；明确记录「与本次改动无关（发送链路未触碰）」「疑为 IME 换行键与发送动作竞争的边角」。
- **同日旁证**：#452B 独立复验同环境观察到「29.0 与 29.9 疑似双触发（首触发源疑 ESC/IME 伪影，[未确认]）」（docs/journal/2026-09-27-v2.md:225）；且「本环境 app 走 V1 发送（纯 SSE 回显，无 [send-seed] 本地播种）」（:228）——直接支撑 §1 矛盾点。
- **MIUI IME 注入劣迹家族**（真机 houji 多次实证）：
  - #378：UI 打字注入被 MIUI IME 家族限制阻断，「type.sh 输入后 send 未达；会话无 server 侧痕迹」（docs/journal/2026-09-09-378-380-wire.md:115、:322）——**与本卡症状同构**（客户端无 POST、服务器无痕）；
  - 中文 IME 组合态劫持 keyevent 打字（拼音预测污染，「基金」实证；docs/journal/2026-09-09-378-380-wire.md:336②）；
  - ESC(111) 在 MIUI IME 有毒（注入全角括号/冻结 accessibility 嫌疑）全程弃用（docs/journal/2026-09-10-384-385.md:72）；
  - 「!」偶发落全角「！」（docs/journal/2026-08-27-event-card-unification.md:706、:717）；
  - #345「APP 可 dump 但注入 tap 全灭」态两例（docs/journal/2026-09-04-fix-308-always-326-327.md:195）。
- **adb reverse 隧道闪断先例**：adb 守护进程在工具调用边界被回收 → reverse 隧道拆 → app 服务器断连（docs/journal/2026-09-09-378-380-wire.md:336①）——真机测试期间链路瞬断是已知环境病，非 app 缺陷。
- **#447 探测失效背景**：当日 app 对 4199 后端曾走错 API 线面（docs/journal/2026-09-27-v2.md:18、:228），提示环境层（探测/隧道/服务状态）在观察窗口内并非常态。

## 3. 现状代码走读（发送链路逐环节）

完整链路：**IME 层 → 输入框状态 → 发送钮 → busy 分流 → VM 快速失败门 → delegate（防重复/空 parts）→ ensureSession → UseCase → Repository（V1 分流+播种判定）→ V1ApiClient POST**。

### 3.1 IME action：ENTER 不发送，只注入换行
- ChatTextField 用 `BasicTextField`，`keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default)`（app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/input/ChatTextField.kt:106），且**无 keyboardActions 参数** → IME 的 ENTER（keyevent 66）仅向文本提交 `\n`，不构成发送动作。**发送唯一入口是 SendStopButton 点击**（app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/input/SendStopButton.kt:225 `onClick = { if (canSend) onSend() }`）。
- 推论：测试脚本「keyevent 66 ×2 + tap 发送」是合法操作组合；换行注入与发送动作在 app 侧本无耦合，「IME 竞态」若存在，只可能发生在**输入框状态读取时刻**（见 H2）。

### 3.2 状态读取与文本加工
- `sendFromComposer` → `doSend`：`val rawText = inputText.text`（ChatScreenBottomBar.kt:307）读取的是 Compose `TextFieldValue` 状态——**IME 组合态（composing span）未提交的内容不在此值中**，提交时机由输入法决定。
- shell/斜杠命令门控均不命中「Run\n\n」（ChatScreenBottomBar.kt:313-411）。
- `PromptBuilder.buildPromptParts`：无 @file 提及时 `text.trim()`，空则 `emptyList()`，否则单 text part（app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/util/PromptBuilder.kt:20-23）。「Run\n\n」→ trim → "Run"，非空。

### 3.3 busy 分流（发送钮层面的拦截）
- `stableBusy && inputText.text.isNotBlank() && !isShellMode` → 不发送，弹 busy 气泡菜单（ChatScreenBottomBar.kt:501-507）。若 tap 时会话被 FSM 判 Busy/Retry，点击根本不进 sendFromComposer——**又一处「无 POST 但也无错误」的静默点**（菜单会弹出，脚本截图窗口可能漏看）。

### 3.4 VM 门面 + #267 快速失败哨兵（**发送前第一个静默丢弃点**）
- `viewModel.sendMessage` 两个重载入口第一行：`if (fastFailIfLinkBlocked()) return`（app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/ChatViewModel.kt:1377-1385）。
- `fastFailIfLinkBlocked`：`linkState(serverId) != Connected` 即置 `_sendFailure = __server_disconnected__` 并返回 true（ChatViewModel.kt:140-144；哨兵常量 :1571-1572）。
- 三态语义：Connected / Connecting（**含重连退避期**，断连即转 Connecting）/ Disconnected（app/src/main/kotlin/dev/leonardo/ocbeacon/service/ServerLinkState.kt:12-14、:19-34）。「非 Connected 即拦截」是 #267 的设计行为，单测已钉死（ChatViewModelSendTest.kt:233 断连快失败、:252 重连中快失败）。
- 消费端：AlertDialog 本地化断连文案（ChatScreen.kt:525-547）——**UI 有反馈，但脚本驱动 + 无人盯屏的验收环境是盲区**；且设计动机正是「OkHttp retryOnConnectionFailure 会悬挂 15s+」（ChatViewModel.kt:1375-1376 注释）。

### 3.5 ChatSendDelegate（**发送前第二、三个静默丢弃点**）
- 空 parts 早退：`if (parts.isEmpty()) return`（app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/ChatSendDelegate.kt:79）——**无日志、无 UI 反馈、草稿保留**。触发条件=rawText 全空白（trim 后空）且无附件。若 IME 组合态窗口内 rawText 读到 ""/"\n\n"，即走此路径。
- 防重复拦截：`isSendingValue == true` → return，仅 DEBUG log（ChatSendDelegate.kt:110-113）。
- 正常路径：`ensureSession()`（:125，挂起在此则永不 POST，但二次发送会被 isSending 拦——与事实 B 矛盾，排除）→ queueRow 判定（:159-162，busy+非 steer → seedTranscript=false）→ `sendMessageUseCase.sendPrompt`（:163-173）→ 成功 `onSendSuccess` 清输入框（:186）；异常 → AlertDialog（:187-194）；finally 复位 isSending（:195-197）。

### 3.6 UseCase 纯委托
- SendMessageUseCase.kt:15-38：直接 `chatRepository.promptAsync(...).getOrThrow()`，无自有逻辑。

### 3.7 Repository：V1 分流与播种判定
- ChatRepositoryImpl.promptAsync（app/src/main/kotlin/dev/leonardo/ocbeacon/data/repository/ChatRepositoryImpl.kt:207-250）：先 POST（:219-221），成功后仅当 `admission != null && admission.id.isNotBlank() && seedTranscript` 才本地播种用户消息（:232-249，[send-seed] 日志 :234）。注释明确：**「V1（prompt_async 204 无响应体）→ admission=null → 依赖 SSE 回显」**（:227）。
- 即 V1 路径：**POST 与气泡是绑定的**——气泡只能来自 SSE 回显，而回显以 POST 到达为前提。

### 3.8 V1 API 发送端点
- V1ApiClient.promptAsync：`POST {baseUrl}/session/{id}/prompt_async`（app/src/main/kotlin/dev/leonardo/ocbeacon/data/api/v1/V1ApiClient.kt:410-420）；非 2xx 抛 RuntimeException（:421-422 → 走 delegate 失败弹窗）；成功恒返回 null（:424-425）。
- V1/V2 发送机制差异（docs/v1-v2-differences.md:11、:22）：V1 `prompt_async` 204 fire-and-forget vs V2 `prompt` 200 返回 Inbox（PromptAdmission.kt:12 亦有同款注记）。

## 4. 根因假设链（按置信度排序）

> 通用验证环境（v1，127.0.0.1:4199）：`systemctl --user start opencode-v1.service`（unit disabled 按需拉起，scripts/debug-entry.sh:33 自动做）→ `adb -s e69a99d8 reverse tcp:4199 tcp:4199`（scripts/debug-entry.sh:35；reverse 易失注意 docs/device-testing.md:111）→ 入口一律 `./scripts/debug-entry.sh`（docs/device-testing.md:143 纪律；直连 Host-4199 见 :119-122）。双路日志：A=`adb logcat -v time | grep -E 'ChatSendDelegate|ChatViewModel|SseConnectionManager|send-seed|Sent prompt'`；B=`journalctl --user -u opencode-v1.service -f`（盯 prompt_async 行）。

### H1（置信度高）：SSE link 瞬态非 Connected → #267 哨兵静默丢弃发送
- 机制：tap 时刻 linkState ∈ {Connecting(含重连退避), Disconnected}（ServerLinkState.kt:13）→ `fastFailIfLinkBlocked` 拦截（ChatViewModel.kt:140-144/1383）→ 不发 POST（事实 A ✓）、AlertDialog 弹出但脚本环境漏看、草稿保留；数秒后重连完成 → 二次发送正常（事实 B ✓）。
- 诱因候选：adb reverse 回收闪断（§2 先例）、v1 service 拉起初期 SSE 建连窗口、#447 线面探测异常余波。
- 弱点：不能直接解释事实 C（气泡）——气泡需另归因（见 H3）。
- **验证**：①复现协议下每轮 tap 前后立即 uiautomator dump + screencap，查断连条幅（ChatScreen.kt:715 条幅条件=非 Connected）与 AlertDialog；②定向对照：`systemctl --user stop opencode-v1.service` 后 1s 内 tap 发送 → 应复现「无 POST + 断连弹窗」，再 start 后二发 202——若与现场观察全同，H1 定罪；③若多轮复现中哨兵/条幅/弹窗从未出现而 POST 仍缺 → H1 降权。

### H2（置信度中）：MIUI IME 组合态窗口 → rawText 读空 → 空 parts 静默早退
- 机制：keyevent 66 注入的换行停留在 IME 组合态未提交，tap 发送瞬间 `inputText.text` 为 "" 或仅部分（ChatScreenBottomBar.kt:307）→ trim 后空 → `parts.isEmpty()` → ChatSendDelegate.kt:79 **无日志无反馈 return**（事实 A ✓）；随后组合提交，"Run\n\n" 才落进输入框（操作者由此确信消息内容）；二次「干净发送」（input text 直落，无组合态）正常（事实 B ✓，且与「干净」措辞吻合）。
- 依托先例：#378 send 未达（docs/journal/2026-09-09-378-380-wire.md:115）、组合态劫持实证（:336②）。
- 弱点：同样不产气泡（事实 C 另归因）；且要求 tap 恰落在组合提交窗口，概率边角——与本卡 P3 定级一致。
- **验证**：keyevent 66 ×2 后 **0ms / 100ms / 300ms 三档延迟** tap 发送（IME 态发送键坐标 (1086,1616)，docs/journal/2026-09-09-378-380-wire.md:337 沿用，用前 dump 校准）；每轮记录：tap 前输入框 dump 文本、logcat 有无「Sent prompt」、有无弹窗。若出现「tap 时 dump 文本为空/仅 Run 尾巴 + 无 POST + 无任何弹窗」→ H2 实锤（区别于 H1 的特征=无断连弹窗）。

### H3（置信度中，与 H1/H2 复合）：「乐观气泡」= 观察混淆，非发送链路产物
- 机制候选（按可能性）：①SSE 重连恢复期的历史 re-sync/preload 回显把既有消息重新推上屏（配合 H1 的断连-恢复时间线，气泡出现但无新回复 → 「悬挂」观感）；②busy 气泡菜单（ChatScreenBottomBar.kt:502）被误读为消息气泡；③输入框内草稿（发送失败设计=草稿保留，ChatSendDelegate.kt:190-192）被误读。
- 依据：V1 路径代码上不存在乐观播种（§3.7-3.8），SSE 回显又与「POST 未发出」互斥——气泡与「未发出」在 V1 语义下不可同源，必有一方是误读。
- **验证**：复现中一旦出现「悬挂气泡」：立即 screencap + `./scripts/pull-app-db.sh` 直查 Room——气泡若有真实 message id 且 Room 有行 → 来自服务器回显（则「POST 未发出」需复核，根因方向反转）；Room 无行而 UI 有气泡 → 升级调查本地合成路径。

### H4（置信度低）：isSending 卡 true 防重复拦截
- ChatSendDelegate.kt:110-113 拦截可致无 POST 无弹窗（仅 DEBUG log），但 isSending 卡死会同样拦截二次发送——与事实 B（二次正常）矛盾；仅当首次 send 恰被 OkHttp 连接悬挂（#267 注释所述 15s+）后 finally 复位、二次在其后发生才成立。列为兜底假设。
- **验证**：复现轮中 logcat 盯 `already sending, ignoring duplicate`（DEBUG 构建）与 isSending 翻转时序。

### 待验证汇总
H1/H2/H4 均为「客户端发送前静默丢弃」家族，彼此不互斥但主因只有一个；H3 是独立必须澄清的观察矛盾。**唯一能把四者分开的现场证据=断连弹窗/条幅的有无 + tap 时刻输入框实际文本 + Room 直查**。当前无任何一条现场日志留存（journal:180 未记录 logcat），故全部标「待验证」。

## 5. 修复方向（含 TDD 切入点）

**定性前置**：若 H1 定罪 → app 无缺陷（#267 按设计工作，单测已覆盖 ChatViewModelSendTest.kt:233/:252），本卡转环境纪律项（测试期 reverse 保活/服务先行拉起，docs/journal/2026-09-09-378-380-wire.md:336 已有 /tmp/adb_keepalive.sh 治法）；若 H2 定罪 → app 侧真有一个值得修的 UX 洞。无论哪种，以下补强独立成立：

1. **消灭「无日志的静默丢弃点」**（最小修复，与根因无关均收益）：
   - ChatSendDelegate.kt:79 空 parts 早退目前零日志——对照 :111 防重复拦截尚有 DEBUG log，应补 `AppLogger.i`（进应用内 Diagnostics，符合 AGENTS.md 新日志规约），内容含 rawText 长度/附件数（不含明文）；
   - fastFailIfLinkBlocked 拦截时补一条非 DEBUG 日志（现仅置弹窗，logcat 无痕，真机取证盲区即本卡教训）。
2. **TDD 切入点**（先测后改，全部纯 JVM 可测）：
   - 新建 `ChatSendDelegateTest`（当前不存在）：①「全空白 rawText + 无附件 → 早退且不调 sendPrompt」钉成显式契约；②「早退路径发出可观测日志」；③「isSending=true → 忽略重复」对齐 RS-007 注释（ChatSendDelegate.kt:108-109）。
   - `ChatViewModelSendTest` 增例：rawText="\n\n"（含注入换行全空白）→ 不触网、不置 isSending——把 #454 的症状面直接写成回归测试；若裁决加用户反馈，先写 sendFailure 哨兵断言再实现。
   - `SendMessageUseCaseTest` 已有基建可复用做链路穿透断言。
3. **复现协议固化**：§4 双路日志 + 三档延迟 tap + Room 直查写成 scripts 下可重跑脚本（N≥10 轮），作为本卡后续任何一轮验证的标准入口——当前「未复现第二次」的最大缺口就是无现场日志。

## 6. 引用清单 + 一句话结论

| # | 引用 | 用途 |
|---|------|------|
| 1 | backlog.md:266-267 | #454 卡片原文 |
| 2 | docs/journal/2026-09-27-v2.md:175-180 | 观察现场（#453 顺带记录） |
| 3 | docs/journal/2026-09-27-v2.md:225,228 | 同日双触发疑影 + V1 无播种旁证 |
| 4 | docs/journal/2026-09-09-378-380-wire.md:115,322,336,337 | MIUI IME 注入阻断家族 + reverse 闪断 + 发送键坐标 |
| 5 | docs/journal/2026-09-04-fix-308-always-326-327.md:178,195 | #345 注入失活两态 |
| 6 | docs/journal/2026-08-27-event-card-unification.md:706,717 | 全角「！」 IME 先例 |
| 7 | docs/journal/2026-09-10-384-385.md:72 | ESC 毒性 |
| 8 | docs/device-testing.md:37,103-111,119-122,143 | 真机 runbook：reverse/入口纪律/Host-4199 |
| 9 | docs/v1-v2-differences.md:11,22 | V1 prompt_async 204 vs V2 prompt 200 Inbox |
| 10 | app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/input/ChatTextField.kt:94-109 | ImeAction.Default：ENTER 只换行 |
| 11 | app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/ChatScreenBottomBar.kt:295-435,501-507 | sendFromComposer 状态读取 + busy 菜单拦截 |
| 12 | app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/util/PromptBuilder.kt:20-23 | trim 与空 parts 语义 |
| 13 | app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/ChatViewModel.kt:140-144,1375-1385,1571-1572 | #267 快速失败哨兵 |
| 14 | app/src/main/kotlin/dev/leonardo/ocbeacon/service/ServerLinkState.kt:12-34 | 三态（Connecting 含重连退避） |
| 15 | app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/ChatSendDelegate.kt:79,107-198 | 静默早退/防重复/发送主流程 |
| 16 | app/src/main/kotlin/dev/leonardo/ocbeacon/data/repository/ChatRepositoryImpl.kt:207-250 | V1 无播种判定 |
| 17 | app/src/main/kotlin/dev/leonardo/ocbeacon/data/api/v1/V1ApiClient.kt:400-426 | V1 POST 端点与 null admission |
| 18 | app/src/main/kotlin/dev/leonardo/ocbeacon/data/api/message/PromptAdmission.kt:12 | V2-only 播种注记 |
| 19 | app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/ChatScreen.kt:525-547,715 | 失败弹窗 + 断连条幅 |
| 20 | scripts/debug-entry.sh:33-43 | v1 按需拉起 + reverse + Host-4199 直达 |
| 21 | app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/ChatViewModelSendTest.kt:233,252,310 | 哨兵与防重复既有单测 |

**一句话结论**：#454 最可能是「发送前静默丢弃」家族的瞬态边角——首选嫌疑 #267 断连哨兵在 reverse 闪连窗口拦截了发送（H1），次选 MIUI IME 组合态使 tap 时刻读到空文本而走零日志的空 parts 早退（H2），而「乐观气泡」按 V1 代码语义（无本地播种）本身即观察矛盾、是复现验证的第一澄清目标；定罪前不动发送语义，先落「静默丢弃点补日志 + 症状面回归测试」两项低风险补强。
