# Handoff — OC Beacon：流式中卡片介入验证（#437 系）+ 三问答复

生成时间：2026-09-27 01:35（真机实验暂停于此，等待接手）
仓库：/home/leo-tkp/Documents/code/mine/oc-beacon ｜ HEAD：d9cc5d08（工作区唯一改动 = backlog.md，见 §2.2）
接手第一原则：本 session 未改任何生产代码，不要去找代码 diff；先读 §9 卡点，再读 §14 路径。

---

## 0. 状态一句话

用户三问中「fling 跳变」与「卡片/通知介入影响面」已给出分析结论；第三问「流式中点开/收起卡片」的**实验尚未取到关键证据**——唯一一次成功取证发生在模型思考停顿窗（非增长期），增长期并发的核心缺口受制于「真机视口无法离底静止 + dump-tap 延迟导致点空」两个障碍，解法候选见 §9.3。

---

## 1. 任务全景与用户全部指令

### 1.1 用户提出的四项请求（按时间序）

| # | 用户原话要点 | 状态 |
|---|---|---|
| 1 | 「markdown 稳态粒度扩展 我之前不是问了还有哪些大块可以拆分优化吗？你没回我哦」 | ✅ 已给出完整盘点（§4），**但留下一个待决问题（§4.4）用户尚未回应** |
| 2 | 「fling 跳变 看看我们是否有处理以预防？请你看看 git 历史！然后还有一种情况，就是卡片、通知等行为的介入是否会影响？我们之前只是测了纯文字，我期望试试复杂场景下是否能维持高度？以及是否影响到了我们之前的卡片展开收起（因为这个也是走高度引擎的）」 | ⚠️ 分析已答（§5/§6/§3），**实验部分未完成**；其中「通知介入」只有旁证（§8.4），无专门实验 |
| 3 | 「开始验证『流式中点开/收起卡片』这一点吧！IME 键盘这点应该不太需要管吧？因为贴底的高度为 0，按照这个来说似乎没有影响？请你设计实验流程并进行实验！」+ 追加「你用手机的无线调试来做吧！」 | ⚠️ 实验设计完成并执行 8 轮（§7），**关键证据缺失**（§9） |
| 4（支线） | 「发现一个可能跟上文内容消失同源的问题：sse输出的时候在输出上方的卡片、内容往上推会比正在输出的内容慢一点点…请你看看造成这样的根因是什么？是否需要修复？是不是我们的优化措施导致的？这是支线任务，请你专注眼前的任务，后续对此进行系统性的调研与根因分析」 | ⏸ 已立卡 #446（§12），**按用户要求未展开调研** |

### 1.2 用户裁决汇总（覆盖既有语义，接手勿违背）

- **IME 键盘不测**：用户自判「贴底高度为 0 → 似乎没影响」，我认同其机理（reverseLayout 底部锚定，IME 从底部侵入裁的是顶部旧内容方向；贴底判定 idx0+off<100 不受影响）→ 已按裁决跳过
- **真机优先**：弃模拟器，用手机无线调试（adbd over WiFi，非 USB）
- **支线不跑题**：#446 只登记，不在本任务展开
- **本次最后指令**：「不要再动代码了，只需要输出交接文档即可」→ 因此下列动作已停止：真机继续实验、frame-jump 分析（后台 job bash-304 仍在跑，结果未纳入本文件）、backlog 追加登记

### 1.3 上一 goal 的状态（避免接手混淆）

- 上一个 goal「markdown 稳态粒度扩展」**已标记完成**：SafePrefixGate 引用块/ATX 标题行级放行 + 缩进歧义行保护（TDD 6 例），commit 9c794590；全量回归 BUILD SUCCESSFUL；journal 见 docs/journal/2026-09-25-437-streaming-md-stable-reveal.md（五十二世轮）
- 该 goal 的产物已推送到 origin/master（HEAD d9cc5d08 即其收尾 docs commit）
- 当前会话开在 goal 完成之后，属于新的临时任务（用户三问 + 支线），**无活跃 goal**

---

## 2. 本次会话产出物清单

### 2.1 生产代码变更：零

本 session **未修改任何 Kotlin/资源/构建文件**。所有工作在取证、分析、实验脚本（均在 /tmp）与 backlog 登记层面。接手者不需要 review diff。

### 2.2 backlog.md 变更（未提交，工作区有 1 个 modified 文件）

本次新增 3 张卡（均通过 ./scripts/backlog.sh check 校验）：

| 卡号 | 优先级 | 标题 | 来源 |
|---|---|---|---|
| #446 | — | SSE流式active块上移快于turn上方内容(视觉撕裂) `streaming` `render` | 用户支线报告（§12） |
| #447 | P1 | opencode server 2.0.16+ 移除 /api/health 导致 app V2 探测永久失效 `network` `compat` | 真机实验环境排查副产品（§10.1） |
| #448 | P2 | SseClient.parseEvent 对非 JSON 帧零容错(单帧即断流) `network` `robustness` | 同 #447 取证链（§10.2） |

提交建议：这三张卡是纯登记性质，可随下次代码提交一并带上（commit message 建议 `docs: backlog #446/#447/#448`）。**未提交**是本 session 的遗留状态。

### 2.3 交付文档

- 本文件（交接文档）
- 无其他新增仓库文档（取证材料都在 /tmp，见 §11；是否沉淀进 scripts/ 与 docs/journal/ 由接手者按 §14 决定）

---

## 3. 用户三问答复矩阵（闭合度自评）

| 用户问 | 已答部分 | 未闭合部分 |
|---|---|---|
| fling 跳变是否有处理/预防 | ✅ 完整：四层防护链 + 提交级证据 + #437 世轮「窗口零异常」结论（§5） | 无（结论层面已闭合） |
| 卡片介入是否影响高度维持 | ⚠️ 部分：非流式有承接、流式 flush 与视口租约的单向让位关系已定性（§6.1） | ❌ 增长期并发点卡的真实行为（即本次实验目标，§9） |
| 通知介入是否影响 | ⚠️ 理论分析（heads-up 浮层不占 insets/不 pause；下拉 shade 走 fling cancel 路径）+ 一次旁证（EP2 录屏期间模拟器 SystemUI 通知横幅活动，同窗零突跳，§8.4） | ❌ 无专门实验；只覆盖了「通知横幅出现」未覆盖「通知点击跳转/后台断连重连」等形态 |
| 是否影响卡片展开收起（同走高度引擎） | ⚠️ 部分：非流式单测（CardExpandClockTest/StepGroupHeightLedgerTest/BottomPinnedExpandSkipTest）在全量回归中绿；引擎重构未动 episode 机器；#423 I3 视口租约 + fac75665 竞态修复仍在（§6.1） | ❌ 「流式中 episode 与 StreamingGrowLedger 并发」未验证（同一缺口） |
| ①markdown 大块还能拆什么 | ✅ 完整清单（§4） | ⏸ 用户未回答「是否开工公式块」（§4.4） |

---

## 4. 【交付 A】markdown 大块拆分盘点（★此前 handoff 遗漏，此处补全）

这是用户第一问的正式答复，已在本 session 逐条给出。接手若被追问，直接用此表。

### 4.1 已完成行级放行（#441 系，两轮落地）

| 块类型 | 状态 |
|---|---|
| 表格（表头 + 逐行正文，跨批续放经 prevReleasedLineIsTableFamily） | ✅ |
| 无序列表（* / - / + 形态） | ✅ |
| 有序列表（1. / 1) 形态） | ✅ |
| 引用块（> 前缀行级） | ✅ |
| ATX 标题（# 行级） | ✅ |
| 纯文字行（无活动标记时增量直出） | ✅ |
| 围栏代码块（闭合后整块；超预算按行铺开） | ✅ |

实现载体：SafePrefixGate.releaseLength（纯函数，零库依赖，两级放行闸：空行毕业 + 块级行扫描状态机）；关键分支顺序见 app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/markdown/SafePrefixGate.kt（isIndentedCodeLine 必须排在纯文字分支之前，否则 4 空格缩进行会被误放行）。

### 4.2 还能拆的（按价值排序，含我的推荐）

1. **数学公式块 $$...$$** — 现状整块扣留（双美元判定）。可套用围栏同款模式（$$ 开行起、按行放行至闭行）。中价值、低风险（行边界清晰）→ **我推荐的下一步**
2. **懒延续行**（引用/列表的无前缀续行） — 现状扣到空行。理论上「后续出现新块起点即定案」可放，但需跨行块状态机，重释义风险面大 → 中价值高风险，缓做
3. **缩进代码块（≥4 空格）** — 上轮实现为保护性扣留（与嵌套列表歧义）。若引入「前文无打开列表项」的上下文跟踪，可对纯代码块形态行级放行 → 中价值中复杂度

### 4.3 不建议拆的（含理由）

- **HTML 块**：渲染器支持有限，扣留即安全
- **setext 标题（--- 紧随文字行）**：SafePrefixGate KDoc 已自认的理论缺口，拆它会放大重释义面；ATX 已是主流形态，接受现状

### 4.4 渲染层的大块优化（另一维度，非 gate 范围）

- 大表格整表 layout（Mikepenz 表格测量行为）、大代码块整块语法高亮 —— 工程在库侧，与 R2 深水区同族（换状态管理问题）

### 4.5 ⏸ PENDING：等用户拍板的事

我给出的收尾问句是「要开工吗？推荐先做公式块 $$（模式同围栏、低风险快赢）——一句话我就按 TDD 走；懒延续/缩进代码块可以立卡排后面。」
**用户未回答此问**（话题转向 fling/卡片）。接手若用户提起 markdown，请先确认是否仍要做 $$ 公式块；若要做，按 TDD + journal + 真机取证流程走。

---

## 5. 【交付 B】fling 跳变四层防护链（git 历史取证，已答）

结论：**不是「有没有处理」，而是已经形成了机制化四层防护，全部现役**。

| 层 | 关键提交 | 机制 |
|---|---|---|
| ① 本体限速 | f03a89d5（限速 fling + 解析移出主线程）、d7a8ac06（scrollBy 契约违规优雅中止，真机 FATAL 根因）、ac12cf93（foundation 回稳定版 1.11.2，消除 beta ScrollingLogic 与自定义 fling 契约冲突） | SafeFlingBehavior 每帧 ≤ viewport/8 速度帽；挂载点 ChatMessageList.kt:2301 |
| ② 渲染供帧 | 4395ec8c（预组合 1→6 项，防 fling 跳过 agent 长气泡）、47edb53c（预取窗口速度自适应）、0faa6984（超长消息块级分片 + isTurnLast O(N²) 消除，fling 巨帧根因） | ScrollSpeedPrefetchStrategy（FLING=6/FAST_DRAG=3/SLOW=0 档）+ #258 TurnSegmentPlan 分段 |
| ③ fling × 流式补偿竞态 | 744967f4（#239：注入点同步短路 isScrollInProgress —— 根因是高度补偿经反射写 scrollToBeConsumed，帧预算紧张时 poke 落空导致残量跨帧存活到 fling 的 scrollBy 之前 = 一次零位移顿挫）、f09eb29b（流式滚动暂缓：fling 期间 ScrollQuiescence 冻结 append/快照，settle 后整段追平） | ScrollQuiescence 单一静默信号；帽 child 底对齐修正 |
| ④ fling × 锚定互搏 | 09110116、0c8221f3（fling 跳底：effect 启动时读到的 autoScroll 是一次性快照，等待 fling 结束后必须**重新校验**再锚定，尊重用户阅读位置） | ChatScrollController MSGEFFECT/GUARD 双检 |

另：#437 世轮的 fling 取证（journal 世轮记录 + c755f804 勘误）最终结论 **fling 窗口零异常**；此前两次「发现问题」均为取证伪影（一次是读历史态配对的正确行为被误读，一次是 VTRACE 采样伪影）。残留仅 #258（fling 高速段组合帧性能上限，LazyColumn 框架特性：fling 状态下遇到大消息体无法立刻渲染而直接跳过）。

---

## 6. 【交付 C】卡片/通知/IME 介入影响分析（已答部分）

### 6.1 卡片展开收起（走高度引擎）的承接现状

- **episode 机器**：CardExpandReveal（逐帧配对 absorb/dispatch + #430 稳态配对账本 steadyRebase/steady-report，覆盖 episode 外的迟到增长：分批表格逐组落地、asyncParse、图片）
- **视口租约（#423 I3）**：PreRenderCoordinator.hasActiveTransactions 期间，MSGEFFECT 锚底 / GUARD 重锚 / PENDING animate / 守望去抖全部让位，避免与 episode 配对位移的 ±H 战争；进入与等待后复查双检查（评审阻断项补遗）
- **既有竞态修复**：fac75665（展开收起三症状：预热资格门、取消路径反射锚点恢复=收起被滚动打断时 snap 无配对、集内回退派发在用户滚动时让位=与 fling 双发竞态）；634a3e11（#423 展开闪现根修：配对前移同遍合并 + FLUSH 拒绘单点）
- **单测面**：CardExpandClockTest / StepGroupHeightLedgerTest / StepGroupSlicingTest / BottomPinnedExpandSkipTest / CardExpandReveal 相关均在上轮全量回归中绿

### 6.2 本次新发现的理论缺口（本实验要验证的东西）

**ScrollCompensation.kt 的流式 flush 单点不检查视口租约**：对该文件 grep hasActiveTransactions/episode 零命中。即让位关系是**单方向**的（滚动锚定让位 episode；但流式 ledger 的 pre-draw flush 不因 episode 激活而让位）。理论冲突面：同帧内 episode 派发 ΔH_card 与 ledger 派发 ΔH_stream 可能双发。
**注意**：这只是静态代码观察得出的「理论缺口」，**EP2 的实测反例表明实际可能被共享的 PreRenderCoordinator flush 单点天然串行化了**（EP2 收起 episode 窗口内 RESERVE flush 无并发记录）——但那一窗恰是模型思考停顿期，不构成增长期证据。**正反两面都需增长期数据裁决**。

### 6.3 通知介入（理论 + 旁证）

| 介入形态 | 分析 | 实测 |
|---|---|---|
| heads-up 横幅 | 浮层显示于 SystemUI 层，不占 app window insets、不 pause Activity（不触发 onStop）→ 布局零变化，帧继续生产 → 理论零高度影响 | 仅旁证（§8.4） |
| 下拉通知栏 shade | 触摸被 SystemUI 抢占 → fling 走 cancel/abort 路径（SafeFlingBehavior 有 abort 分支与 DEBUG 探针）；shade 透明覆盖不 pause，ledger 继续 flush 无欠账 | 未测 |
| 通知点击跳转/回前台 | 走 Activity onNewIntent/onResume，非本引擎职责 | 未测 |

### 6.4 IME（用户裁决跳过，机理记录）

imePadding 作用于 bottom bar 层，改变列表可视高度。reverseLayout 底部锚定下，视口缩短优先裁掉顶部旧内容方向，贴底判定（firstVisibleItemIndex==0 && offset<100）不受影响 → 用户判断「没影响」与机理分析一致。列表两处 onSizeChanged（ChatMessageList.kt:1409 分片 item 的 ChunkDiag、:1786 的 ScrollDiag RESIZE）**均为 DEBUG 取证探针，非生产承接逻辑** → 若未来真机在 IME 场景出现症状，此处无兜底，需新立案。

---

## 7. 实验全轨迹（EP1–EP8，含失败原因）

环境说明：EP1–EP3 在模拟器（Pixel6_Android36 headless，1080x2400）+ V2 server（opencode 2.0.16，无鉴权直连走 adb reverse）；EP4–EP8 在真机（小米 houji 1440x3200，无线 adb）+ 先后经历 V2/代理/V1 三种 server 形态。

| 轮 | 环境 | 目标 | 结果 |
|---|---|---|---|
| EP1 | 模拟器+V2 | 一轨三场景（S2 流式中展开 / S3 流式中收起 / S1 非流式对照） | tap 命中用户气泡内文本（prompt 里写了 bash 字样）→ 未触卡；录屏 40.7s/979 帧**零突跳** |
| EP2 | 模拟器+V2 | 非贴底方案（B1 下滑离底 → B2 点卡 → B3 反向） | B1 下滑 500px **未离底**（drift: atBot=true scrolling=true→false autoOn=false idx=0 off=0）；B2 tap 命中 local_shell 卡（命令文本 printf CARDPROBE）→ **✅ 成功触发流式中收起 episode（本 session 唯一硬证据）**；录屏 1336 帧零突跳；期间发现 29.75s「流式空窗」经查为**模型思考期**（非 SSE 断连） |
| EP3 | 模拟器+V2 | 超长内容（3000 字）+ 增长期检测 + 点击 | **未完成**：run_code 执行超时（120s deadline）被打断 |
| EP4 | 真机+V2 | 真机首轮 | **失败**：无线 adb 重连后 reverse 表挂在死 transport，app 落「目录为空 + 服务器已断开」 |
| EP5 | 真机+代理 | 绕过 2.0.16 缺失 /api/health | **部分成功**：探测与 SSE 一度打通（app 日志出现 SseClient 收到事件、MessageUpdated、SGR-435 attach streaming=true、RESIZE 0→199、flush/release 配对），但模型输出为错误文本「Header中未收到Authorization参数，无法进行身份验证。」（zhipuai key 未送达）→ 该轮**实际验证了端到端链路健康度**（§8.2） |
| EP6 | 真机+V1 | 增长期检测（12s 触发）+ 点卡 | 增长检测成功；tap 命中思考摘要文本（非点击区）且受 dump-tap 延迟影响 → 未触发 toggle；logcat 记录 **142 次 RESIZE** 配对健康 |
| EP7 | 真机+V1 | 目标改为思考块标题行（思考完毕 = 唯一 toggle 入口） | P2 dump 命中「思考完毕」并发出 tap，但 tap 时刻该块已被增长推走 → **无 CardExpand 日志**；配对节奏 66–206px / 250–300ms 全程稳定 |
| EP8 | 真机+V1 | 下滑 600px 离底后稳定点击 | **失败**：下滑仍零位移（idx=0 off=0）；后续 tap 全 NOT_FOUND（思考块已滚出视口） |

### 7.1 模型与点击目标的演化（为什么最后用思考块）

1. 初始用 V2 server 的 longcat-2.5-preview-free → 实测**不触发工具调用**（turn 元信息「1 步 · 0 个工具」），拿不到 shell 卡
2. 切 glm-5.3（zhipuai-coding-plan）→ V2 下能跑 bash（EP1 出现 echo CARDPROBE 卡）
3. 换 V1 server 后 glm-5.3 经 openai-compatible provider **同样不触发工具调用** → 无 shell/工具卡可选
4. 遂改用**思考块（ReasoningBlock）**：它同属 CardExpandReveal 家族（标题行本体点击 = 展开/收起唯一入口，[ReasoningBlock.kt:221](app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/components/ReasoningBlock.kt) 的 clickable，**无 streaming 门控**），是合法的卡片介入验证对象

### 7.2 tap 目标的 dump 正则陷阱（血泪，接手必读）

- 匹配词若出现在**用户气泡文本**里（如 prompt 写了 bash / echo），findall 首个命中会指向用户消息 → 直接点到气泡。对策：prompt 内不出现匹配词（EP1→EP6 的改法）
- 匹配词若出现在**思考摘要文本**里（glm 的 reasoning 会复述命令），同样会点错（EP6）
- 展开态与收起态的卡内文本不同（如 local_shell 展开显示命令行，收起后只剩标题），反向 toggle 时匹配词可能 NOT_FOUND

---

## 8. 实验硬证据（可直接引用）

### 8.1 ✅ 成功例：流式中（turn 未完结）收起卡片，episode 全链路正常、零跳变

来源 /tmp/card-ep2.log（点击 00:39:22.762，卡片为 u_msg_local_shell_ 即 shell 卡收起）：

    00:39:23.561 [DEBUG-427] close-pre rep=58 meas=58 fii=0 fiso=0 items=...7:181|8:297|9:355...
    00:39:23.562 [DEBUG-427] paired-shift target=-58 consumed=0 tries=0
    00:39:23.593 ScrollDiag: RESIZE key=u_msg_local_shell_ h 255->197 (d=-58)
    00:39:23.632 [DEBUG-427] close+2f ...8:239...
    00:39:23.633 [DEBUG-422] episode done f=0.000 totalMs=894 completed=true pinPending=false
    00:39:24.014 [DEBUG-427] steady-report d=-58 rep=0 H=58 f=0.001

解读：收起配对目标 -58px 成立、视口项偏移自洽（8:297→239）、episode 894ms 正常收尾、稳态账本正确 rebase（d=-58）。**限制**：该窗内容未见增长（模型思考停顿），故只证明「流式中（宽松意义）episode 正确」，不证明「增长期并发」。

### 8.2 流式配对引擎健康度（真机多次复现）

EP7（/tmp/card-ep7.log）142 次 RESIZE 的稳定形态：

    RESIZE t=... key=t_msg_xxx h 1985->2051 (d=66)     ← 内容增长 66px/批
    RESERVE : measure h=2099 reserved=2033              ← 帽测量与预留
    RESERVE : flush reserved=2033 true=2099
    SGR-435 : flush t=... fii=0 fiso=0 ip=false
    SGR-435 : release t=... capd=66 led=0 set(fii=7) h->2099   ← 单出口放行
    SGR-435 : drop(append/reading-away) ...             ← 贴底跟随族免派发语义

节奏：每 250–300ms 一批，批次 66–206px（66 为一行高度），led=0 表示贴底跟随族零派发（符合 #435「锚即意图」）。EP6 同类数据 142 次 RESIZE。

### 8.3 录屏帧差突跳检测

| 录屏 | 环境 | 帧数/时长 | 结果 |
|---|---|---|---|
| /tmp/card-ep1.mp4 | 模拟器 | 979 帧 / 40.7s | **零突跳**（无 |shift|>80px 帧） |
| /tmp/card-ep2.mp4 | 模拟器 | 1336 帧 | **零突跳** |
| /tmp/card-ep6/7/8.mp4 | 真机 | 105s/218s/218s | **未分析**（后台 job bash-304 进行中，被「停止操作」指令中断；接手可重跑 /tmp/fj-phone-all.sh） |

方法：ffmpeg -r 24 抽帧 → scripts/frame-jump-analyze.py（中列带 1D 互相关估垂直平移，|shift|>480px 或相关性差判突跳）。注意该工具在此前的使用中曾因 VTRACE 采样伪影产生误判，**以帧差为准，勿用 VTRACE 位移下结论**。

### 8.4 通知介入的一次旁证（弱证据，未做专门实验）

EP2 日志中 00:39:14–00:39:30 出现模拟器 SystemUI 的通知卡片刷新（进程 1063 的 SsBaseTemplateCard 反复活动），该时段**正落在流式输出期间且录屏覆盖范围内**，而 EP2 录屏全程零突跳 → 可作「通知横幅出现期间流式滚动未见跳变」的弱旁证。**不足**：未确认通知的可视性/是否 heads-up、未做 notification 与高度引擎的专门时间对齐分析、未覆盖真机。

---

## 9. 核心卡点（接手从这里开始）

### 9.1 阻塞链总览

    要验证「增长期 + 点卡」→ 必须能稳定命中卡片 → 需要视口静止（离底）或低漂移 → 两个手段当前都不可用

### 9.2 卡点一：dump-tap 延迟（已彻底证实，非 UI 问题）

贴底跟随下内容 ~66px/250ms 上移；uiautomator dump + input tap 往返约 1.5–2s → 坐标漂移 400–500px → 点空。EP1/EP6/EP7 的 NOT_FOUND 与 EP7「dump 命中但无 episode 日志」均由此。**结论：贴底跟随态不能用 dump-tap 定位方法做点击取证。**

### 9.3 卡点二：视口无法离底（核心未解谜题）+ 已排除的假设

实测记录：

| 轮 | 手势 | 结果 |
|---|---|---|
| EP2（模拟器，内容可能不足一屏） | 下滑 500px / 250ms | idx=0 off=0，autoOn 已置 false 但视口未动 |
| EP7（真机，内容 3000px+） | 上滑 600px / 150ms | idx=0 off=0 |
| EP8（真机，内容 3000px+） | 下滑 600px / 150ms | idx=0 off=0 |

**已排除的假设**：「内容不足一屏所以不可滚」——EP7/EP8 内容高度远超一屏（RESIZE 记录 3000px+），仍双向零位移。
**仍待验证的假设**：(a) reverseLayout 底部锚定导致双向 bump（需要一个可靠方向定义）；(b) input swipe 时长过短（150ms）被当作极短 fling 且被限速/边界回弹吃掉；(c) 有未知的滚动守卫在把视口拉回（但 drift 显示 autoOn=false，且流式中 GUARD 静默，MSGEFFECT 需 autoOn=true）。
**journal 中的方向裁决**（「上滑=向底部、下滑=向旧内容」）与本次双向 bump 实测冲突，接手需重新标定（建议用 drift 日志的 idx/off 读数做判据，而非体感方向）。

### 9.4 离底解法候选（按性价比排序，均未验证）

1. **慢速长距离拖动**：input swipe 720 1000 720 2700 600（600ms 慢拖，真机 1440x3200）或 input draganddrop
2. **用 app 自带「快速定位」按钮**（dump 中 content-desc=快速定位，位于 top bar）跳到旧消息 → 天然非贴底且视口静止，之后 dump-tap 应可稳定命中
3. **坐标补偿**：实测 dump 往返耗时 T，按 264px/s 速率把 tap y 下调 T*264 px（需先标定速率）
4. **降低漂移速率**：prompt 要求模型「每段之间停顿」，把输出速率压到 <100px/s，使 dump-tap 时间窗内漂移可接受
5. **模拟器退路**：EP2 已在模拟器成功触发过一次；可在模拟器用「增长检测 + 立即固定坐标连点」补齐（代价：失去真机帧特性）

### 9.5 卡点三：V1 模型不触发 tool call

V1 server（1.18.32）+ openai-compatible provider 下 glm-5.3 只思考不执行 bash → 无 shell/工具卡。若要工具卡作为点击目标，需探索 V1 下支持 tool call 的 provider 配置（未探索）。当前用思考块替代（合法但形态单一）。

---

## 10. 环境搭建全记录（重建环境必读）

### 10.1 根因链：opencode 2.0.16 无法配合真机（已立卡 #447）

1. opencode **2.0.16 移除了 GET /api/health**（实测：无鉴权 401、带正确密码 **404** —— 即认证通过但路径不存在）
2. app ApiVersionDetector（app/src/main/kotlin/dev/leonardo/ocbeacon/data/api/version/ApiVersionDetector.kt）的 V2 探测**只认该端点** → tryV2 返回 null
3. tryV1 探 /global/health → server 返回 SPA fallback 的 text/html → 被 content-type 防御（L103/L143）拦下 → 双探皆空 → 返回 UNKNOWN
4. checkHealth（ServerDataStore.kt:175）按 #132 语义「UNKNOWN 保留原值」→ 真机持久化的 V1 **永不被纠正**
5. V1 模式请求 /project、/global/event、/session → V2 server 全部返回 SPA HTML（HTTP 200！）→ SSE parseEvent 抛 JsonDecodingException → 「SSE stream closed after 0 events」→ 重连退避（attempt 递增到 cooldown）→ UI 显示「服务器已断开，正在重连…」+「目录为空」

**额外观察（可观测性缺口，已并入 #447 建议）**：真机日志显示 V1 探测失败有 W 日志，但 **V2 探测在非 2xx 时是静默 return null**（ApiVersionDetector L99）；另有一次新进程启动时**根本没有发出 /api/health 请求**（代理侧 REQ 日志只记录到旧进程发过一条）——探测调用时序存疑，接手若走「修探测」路线需先补日志定位。

### 10.2 副产品：SSE 解析器零容错（已立卡 #448）

同理，日志明确记录：收到 HTML / 裸 '0'（来自手搓 chunked 代理的实验）等非 JSON 对象帧时，parseEvent 直接抛异常并**关闭整条流**。建议改为「跳过该帧 + 计数上报」，心跳/注释帧显式忽略。此卡与 P1（SSE 长连接随机断连）疑为同源族群，接手可对照。

### 10.3 中途试过的补偿方案（均已退役，保留供理解）

- /tmp/health-proxy.py（http.server 版反代）：补 /api/health 合成 JSON；**踩坑**：转发时未剥离 Accept-Encoding，server 回 gzip 而 urllib 已解压，导致 okio GzipSource 报 ID1ID2 不匹配
- /tmp/health-proxy2.py（socket 级透传版）：仅拦截 /api/health，其余字节级转发；**踩坑**：早期手工拼 chunked 结束帧让 app 收到裸 0 触发 #448 现象；最终版工作正常但**结论是修 server 版本更干净**，故退役

### 10.4 当前可用配方（V1 server，实验依赖）

**运行中**：job bash-265 = opencode serve --port 4199 --hostname 127.0.0.1（版本 1.18.32）

安装步骤（可复现）：

    npm i -g --force opencode-ai@1.18.32        # --force 因覆盖 brew 版 opencode
    cd /home/linuxbrew/.linuxbrew/lib/node_modules/opencode-ai && node postinstall.mjs   # npm allow-scripts 拦截了 postinstall
    opencode --version    # 应输出 1.18.32

数据目录处置（**重要，涉及数据安全**）：

- V1 无法读 2.x 库（报错：Database is not empty and has no session table）
- 已将 ~/.local/share/opencode 备份改名为 ~/.local/share/opencode.v2bak.1790442017（内含 2.x 的 opencode.db、repos/、snapshot/ 等）
- **恢复 2.x 环境**：停当前 server → 把 opencode.v2bak.* 改回 ~/.local/share/opencode（先移走 1.18 新建的同名目录）→ 重装/切回 opencode 2.0.16（brew 安装的二进制已被 npm 覆盖，需 brew reinstall 或用 npx）

provider 配置（~/.config/opencode/opencode.jsonc）：

    zhipuai / npm=@ai-sdk/openai-compatible / options.baseURL=https://open.bigmodel.cn/api/coding/paas/v4
    options.apiKey = <内联密钥>（1.18 下 apiKeyEnv 不生效，必须内联）
    models: glm-5.3

**凭据处置（脱敏要求）**：

- 本文件与该 jsonc 中均**不含**明文密钥；密钥来源为 2.x 备份库 opencode.db 的 credential 表（integration_id=zhipuai-coding-plan，value 为 JSON 含 key 字段）
- 本 session 的临时副本在 /tmp/zai-key.txt —— **接手请在使用完毕后删除该文件**（它是明文密钥）
- 如需重新配置，按上述来源重新提取，不要把它写入仓库、journal、commit 或 backlog

鉴权：V1 server 未设 OPENCODE_SERVER_PASSWORD（启动日志有 Warning: server is unsecured），依赖 adb reverse 隧道作为信任域；debug intent 的 debug_password 传任意值即可

### 10.5 真机 adb 操作要点（踩坑汇总）

- **设备名**：真机的 mDNS 名在本次为 `adb-e69a99d8-yzT17Y (2)._adb-tls-connect._tcp`（**带 ' (2)' 后缀**，是名字冲突去重结果）。不带后缀的条目存在但挂在死 transport 上，用它执行 adb -s / reverse 会**静默失败**（reverse --list 会显示旧 transport 的 host-XX 条目）
- **reverse 每次 adb 重连后失效**，必须重设：adb -s "<serial>" reverse tcp:4199 tcp:4199（可用 toybox nc 127.0.0.1 4199 验证真机侧连通，NC=0 即通）
- **device offline 常见原因**：手机「无线调试」休眠（屏幕锁定）；本次即如此，需人工解锁唤醒。注意：mDNS 能解析、IP 能 ping 通**都不代表** adb 可用（TCP 35087 会 Connection refused）
- 入口脚本：scripts/debug-entry.sh <serial> dev.leonardo.ocbeacon.dev（自带 reverse + force-stop + debug intent + 标志校验）；配合 OCBEACEN_SERVICE_JSON=<含密码的临时 json> 使用
- 现成脚本：/tmp/entry-phone.sh（入口封装）、/tmp/hard-restart2.sh（force-stop + am start + reverse 到 4199）、/tmp/phone-peek.sh（dump 文本）、/tmp/tap-text-phone.py（文本定位 tap）、/tmp/shot-phone.sh（截图）、/tmp/check-rev2.sh（reverse 核查）

### 10.6 V1 API 速查（与 V2 差异大，实验脚本依赖）

    # 建会话
    curl -X POST -H 'Content-Type: application/json' -d '{"title":"CARD-EPn"}' http://127.0.0.1:4199/session
    # 发消息（异步受理，立即返回 info；parts 结构 + model 字段名必须 providerID/modelID 全大写）
    curl -X POST -H 'Content-Type: application/json' \
      -d '{"parts":[{"type":"text","text":"..."}],"model":{"providerID":"zhipuai","modelID":"glm-5.3"}}' \
      http://127.0.0.1:4199/session/<SID>/message
    # 其它： /global/health、/project、/session

注意：/models 与 /config/models 在 1.18 也返回 SPA HTML（不是模型清单端点）；/api/* 一律不存在。V2 的 /api/session/{id}/prompt 契约（prompt 包裹 + agents 数组）在 V1 不适用，V1 用 /message + parts。

---

## 11. 工具/脚本/探针索引

### 11.1 实验主控（均在 /tmp，未入仓）

| 文件 | 说明 |
|---|---|
| /tmp/run-phone3.py | **最新主控（EP8）**：增长检测 → 下滑离底 → dump-tap → 反向 toggle → 录屏/logcat 收尾。改 SID/PROMPT/手势参数即可复用 |
| /tmp/run-phone2.py | EP7 版（思考块为目标，上滑尝试） |
| /tmp/run-phone-final.py | EP6 版（真机首轮 V1） |
| /tmp/run-scene-b.py | EP2 版（模拟器非贴底方案，含 B1/B2/B3 分段） |
| /tmp/run-scene.py | EP1 版（模拟器三场景一轨） |
| /tmp/run-scene-c.py | EP3 版（超长内容 + 增长检测；未跑完） |

**GrowthWatcher 机制（本 session 的关键方法学资产）**：主控内起一个线程流式 tail logcat 文件，匹配 `ScrollDiag: RESIZE ... key=t_msg` 且高度递增的行，连续 N 条（默认 3–4）即判定「流式增长期开始」并唤醒主控。**这是把「模型思考期」与「真实输出期」分离的唯一可靠手段**（EP6 之前的所有轮次都因等待窗口落在思考期而误判）。值得沉淀进 scripts/。

### 11.2 分析与取证脚本

| 文件 | 说明 |
|---|---|
| /tmp/tap-text-phone.py | 真机 uiautomator dump → 正则找含匹配词的节点 → tap 中心；输出 tapped x y / NOT_FOUND / DUMP_FAIL |
| /tmp/tap-text.py | 模拟器版（同一逻辑，SERIAL=emulator-5554） |
| /tmp/analyze-ep7.py、analyze-ep6.py、analyze-ep2*.py、analyze-log*.py | logcat 探针切片（按时间窗过滤 CardExpand/RESERVE/RESIZE/SGR/drift） |
| /tmp/fj-phone-all.sh | 真机三段录屏批量抽帧 + frame-jump 分析（**本次未跑完**） |
| scripts/frame-jump-analyze.py | 仓库内工具：抽帧目录 → 垂直平移互相关 → 突跳判定 |
| /tmp/conn-diag*.sh、diag-ver*.sh、diag-probe*.sh | 真机连接/探测诊断套件（含按 pid 过滤 logcat） |

### 11.3 logcat 探针 tag 速查

| tag | 含义 |
|---|---|
| CardExpand | episode 生命周期：DEBUG-422 settle/episode done、DEBUG-427 close-pre/paired-shift/close+2f/steady-report、PRD-warm、DEBUG-420 cancel-on-scroll、DEBUG-425 residual、STEADY |
| SGR-435 | 流式增长账本：attach / flush / release / drop(append/reading-away) |
| RESERVE | 流式高度预备帽：measure / flush（reserved vs true） |
| ScrollDiag | RESIZE：item 高度变化（key 前缀 t_msg_ / u_msg_ / t_msg_local_shell_ 等） |
| ChatScrollController | DEBUG-drift：atBot / scrolling / autoOn / idx / off；MSGEFFECT fire/anchor；GUARD reanchor |
| SGR-GATE | bottom-follow 家族与 gate 状态 |
| ApiVersionDetector | 版本探测：Detected Vx API / non-JSON content-type / Could not detect |
| SseClient / SseConnManager | SSE 连接、Parse error、stream closed、Reconnecting attempt #N |

---

## 12. 支线 #446 详情（用户原文三问，接手勿丢）

用户原话（2026-09-27）：

> 发现一个可能跟上文内容消失同源的问题：sse输出的时候在输出上方的卡片、内容往上推会比正在输出的内容慢一点点，在视觉上的效果就是sse输出已经上推了内容，但是当前outputing块上移的速度比本条消息（turn）中上面的卡片/内容上移的快一些。请你看看造成这样的根因是什么？是否需要修复？是不是我们的优化措施导致的？

**三个子问题必须在调研中逐一回答**：① 根因是什么；② 是否需要修复；③ **是不是我们的优化措施导致的**（即 #437/#440/#441/#442 系改动与 #430 稳态账本、帽 f09eb29b 底对齐等近期改动的因果对照）。

已登记的候选根因方向（写在卡片明细中，接手可从这些假设切入）：

1. streamingHeightReserve 帽的 clip / 底对齐特性（f09eb29b：底对齐后溢出朝上=裁视口外旧文本）→ 帽内内容与帽外 item 边界的移动相位差
2. SafePrefixGate 放行节奏（批）与帽 reserve 释放节奏不在同一帧
3. displayItems diff（列表级 SnapshotStateList）与 item 内部增长（SGR flush）两个 Snapshot 事务的帧错位
4. RESIZE 探针高度与实际绘制高度存在 1 帧滞后（测量假象，需排除）

另：用户明确指出「**可能跟上文内容消失同源**」，而 ChatMessageList 中已有 CONTENT-BLINK 探针（removed>=2 && added<removed 时打印）—— 调研时应先把该探针的历史日志与撕裂现象做时间对齐。相关历史：#428/#437 闪烁族。

---

## 13. 尚未闭合事项清单（接手 checklist）

- [ ] **增长期 + 卡片的 episode 取证**（本任务核心缺口）——先解离底（§9.4）
- [ ] 真机三段录屏 frame-jump 分析（/tmp/fj-phone-all.sh，job bash-304 被中断）
- [ ] 通知介入的专门实验（至少覆盖 heads-up 横幅与 shade 下拉；真机）
- [ ] 结论落库：拿到证据后按 §14.3 三结局处理，并写 docs/journal/
- [ ] 沉淀可复用资产：GrowthWatcher、tap-text-phone.py、诊断脚本套件 → scripts/
- [ ] backlog.md 的 #446/#447/#448 三卡待提交
- [ ] /tmp/zai-key.txt 明文密钥待删除
- [ ] 2.x 环境恢复（若用户要继续用 2.0.16；备份在 ~/.local/share/opencode.v2bak.1790442017）
- [ ] markdown 公式块 $$ 是否开工（等用户拍板，§4.5）
- [ ] #446 系统性调研（用户指定为后续任务）

---

## 14. 接手路径

### 14.1 环境复活（约 5 分钟）

1. 确认 job bash-265（V1 server）存活：curl http://127.0.0.1:4199/global/health 应返回 {"healthy":true,"version":"1.18.32"}；若挂了按 §10.4 重启（**不需要**重新装或重配）
2. 确认真机在线：export ANDROID_ADB_SERVER_PORT=5038; adb devices（找带 ' (2)' 后缀的条目）；不在线需用户解锁手机唤醒无线调试
3. 重设 reverse：adb -s "<serial>" reverse tcp:4199 tcp:4199；bash /tmp/check-rev2.sh 验证
4. 重启 app 到 CARD-EP8 会话：bash /tmp/entry-phone.sh（或 hard-restart2.sh）

### 14.2 补缺口（按 §9.4 顺序试离底，优先候选 2「快速定位」与候选 1「慢速长拖」）

### 14.3 拿到证据后的三种结局处理

| 观察到 | 处置 |
|---|---|
| 无双发、配对正常 | 结论「流式 flush 与 episode 由共享 PreRenderCoordinator 单点天然串行」或「视口租约已足够」→ 补单测锁定该不变式 + journal 收尾（**不需要改生产代码**） |
| 有双发/跳变 | 在 ScrollCompensation.kt 的 flush 入口补 hasActiveTransactions 让位（对齐 ChatScrollController 既有语义）→ **TDD 先写红测试** → 真机取证 → 提交（fix: 前缀） |
| 有跳变但归因他处 | 立新卡，附取证 |

### 14.4 纪律

- 中文输出；gradle 单条多任务禁并发（compile 300s / tests 420s / assemble 600s）
- 改 ChatScreen.kt 前必读 docs/chatscreen-editing-protocol.md
- 任何完成声明前加载 verification skill 并给出真机证据链
- backlog/journal 操作走 ./scripts/backlog.sh（禁手工直编卡片区）

---

## 15. 相关卡片与文档索引

| 位置 | 内容 |
|---|---|
| backlog.md #446 | 撕裂现象（本条支线） |
| backlog.md #447 | opencode 2.0.16+ 移除 /api/health 兼容性（P1） |
| backlog.md #448 | SseClient 非 JSON 帧零容错（P2） |
| backlog.md P1 卡 | app SSE 长连接随机断连（与 #447/#448 疑同源族群） |
| backlog.md P2 卡 | R2 双容器二期（滑动 p90 / 挂账） |
| backlog.md P3 卡 | R2 深水区设计、fling 复发、大块拆分扩展等 |
| docs/journal/2026-09-25-437-streaming-md-stable-reveal.md | #437 世轮全部取证（含 fling 勘误链、R1–R5 高度引擎根修） |
| docs/research/sse-scroll-stability-iron-laws.md | SSE 滚动稳定性铁律与回归历史 |
| app/src/main/kotlin/.../markdown/SafePrefixGate.kt | 稳态粒度放行闸（#441 系产物） |
| app/src/main/kotlin/.../components/CardExpandReveal.kt | episode 机器与 #430 稳态账本 |
| app/src/main/kotlin/.../components/ScrollCompensation.kt | 流式高度引擎 flush 单点（缺口所在，§6.2） |
| app/src/main/kotlin/.../data/api/version/ApiVersionDetector.kt | 版本探测（#447 缺口所在） |

---

## Suggested skills

| skill | 何时用 |
|---|---|
| diagnosing-bugs | 解 §9.3 离底谜题；以及后续 #446 的三子问根因分析（尤其「是不是我们的优化措施导致的」需要改动因果对照） |
| tdd | 若证据指向需要改 ScrollCompensation.kt / 让位逻辑（必须先写红测试） |
| verification-before-completion（AGENTS.md 索引名 verification） | 任何「完成」声明前；本任务验收需真机 logcat + 录屏双证据 |
| mobile-android-design | 仅在需要改动 Compose UI 结构/交互时参考 |
