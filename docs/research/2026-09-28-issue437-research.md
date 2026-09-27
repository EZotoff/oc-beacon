# #437 流式 Markdown 稳定揭示渲染——深度调研报告

> **一句话结论**：#437 的核心根因（L4 不稳定尾「先字面排版、后回溯重释义」导致已显示内容高度回溯）已由 SafePrefixGate 两级安全放行闸从源头消灭并在真机上多轮定量验证，本卡剩余工作本质是「验收收口 + 关联残差分卡处置」——未定罪的 #444 fling 回归、未做的 #438② 保 key、O(总内容) 测量成本（#445 深水区）与 beta/stable 未放开是四条明确的尾巴，且修复族曾滋生 #450 回归，收口前必须过真机验收矩阵。

## 0. 调研范围与方法

- **一手来源**：backlog.md 卡片区、docs/specs/ 两份 #437 spec、docs/journal/ 四份相关批次（主档案 967 行全读）、docs/research/sse-scroll-stability-iron-laws.md、AGENTS.md、app 源码与测试（SafePrefixGate/StreamingMarkdownPilot/MarkdownContent/HeldTailAging/HeldTailReveal/ScrollCompensation/ChatMessageList/build.gradle.kts）、git log（只读）。
- **方法**：按卡片 → journal → spec → research → 源码的顺序交叉核对；所有论断标注 文件:行号；置信度分级（高=有真机仪器证据或多源一致；中=单源或推理链完整但缺现场；低/待验证=明确写出验证方法）。
- **边界**：本调研只读，未改动任何源码/配置，未执行 git commit/push/stash；性能数据均引用 journal 已有真机取证，未重新采样（设备状态未知）。

---

## 1. 问题陈述

### 1.1 用户可感知症状（原始主诉）

- 流式输出期间已显示内容**闪烁/跳变**：同文本无新行的情况下视口两态往返翻转，~350px 内容位移 @2-5Hz（真机录屏帧差 A-B 翻转帧定罪，backlog.md:94；spec 2026-09-25-437-streaming-md-stable-reveal-design.md:9）。
- 已显示内容**高度回溯变化**——不稳定尾部先按字面临时排版，闭合符号到达后被库回溯重释义（如同一段文字先按普通段落排、随后 --- 到达升格为 setext 标题，或 * 未闭合先字面显示、闭合后变粗体），排版结果前后翻转（spec …437…design.md:9、:18）。
- 流式非贴底阅读（上拉阅读）时同样闪烁（journal 2026-09-25-437-streaming-md-stable-reveal.md:45，用户补充确认场景）。

### 1.2 触发条件

- SSE 流式输出期间、assistant 消息含 markdown 构造（强调/标题/表格/围栏/列表）时；纯文字段落在旧实现下另有「整段收完才放=整段跳出」的延迟形态（journal …437…:135，8c1c3b2a 修复前用户投诉点）。
- #435 高度引擎只能配对**单调增长**的高度流；任何回溯（负增量）都会穿透视口配对直接上屏（backlog.md:94；spec …437…design.md:22）。

---

## 2. 已知历史（卡片 + journal 取证沉淀，逐条带引用）

### 2.1 定罪链（修复前）

| # | 取证/定罪 | 引用 |
|---|---|---|
| 1 | 六层管线定罪表：跳变主源=L4 解析层「不稳定尾回溯重释义」，次源=L6 完结切换 preParsedState 归一化=另一套布局 | spec …437…design.md:11-20 |
| 2 | #435 引擎只能配对单调增长，故本卡使命=把交给 L5 的高度流变成**单调量子增长** | spec …437…design.md:22 |
| 3 | 方案定型：pilot 前缀差分与 state.append 之间加 SafePrefixGate，**库与渲染层零改动**；稳定块+纯段安全后缀两级放行，尾部扣留超龄进锁高降亮区，完结 EOF 全量 flush | backlog.md:94；spec …437…design.md:26-36 |

### 2.2 交付与二十余轮验收（2026-09-25 ~ 09-27，全部有真机证据）

- **阶段 A-D 一次交付**（5 commits）：SafePrefixGate 两级放行（27 判定用例）+ HeldTailAgingState 超龄状态机 + gate 归一化增量前移（表格粘边空行注入、tasklist/$$ 扣留）+ FlapDetector 非前缀风暴限频 + MDPilot 观测日志（journal …437…:12-15）。
- **真机首批证据**：gate 按批放行→库 stable AST 增长→**库内 unstable tail≈0（第一道闸生效铁证）**；帧差录屏 220 帧无基线两态往返翻转对；全程 0 FATAL（journal …437…:21-26）。
- **用户数学模型定案**：reverseLayout 不变量 scrollPos(t) − ΔH(t) = S₀；落地链含 gate 放行量子化 ≤400ch/批（单帧 1830px 暴涨→≤330px）（journal …437…:58-63）。
- **像素级证伪与两根因修复（75e602b9）**：①全域+Δ 是双重补偿，回归等式（锚内增长才配对）；②streamingMsgId 随在飞 call id 漂移致修饰符脱落（journal …437…:74-81）。
- **R7 等式规则真机成立**：锚内每批精确配对 fiso 354→2862 锁步、多段像素 diff=0.0 完美冻结（journal …437…:91）。
- **超龄量子化（b54650c3）**：中继停顿冲刷使扣留区瞬时积压（chars=1091→单帧 7378px）打穿量子化，首亮 ≤800px、步进 ≤1600px/500ms（journal …437…:91-93；HeldTailAging.kt:62-71）。
- **块级展示重写（a421410a，用户裁决）**：未闭合零输出、闭合整体出、表格按行；**超龄降亮区整体关闭**（HeldTailAging.kt:54-57）（journal …437…:110-112）。
- **纯文字增量直出（8c1c3b2a，用户裁决）**：未完行增量放行至快照尾（字面=最终），消灭中文长段落整段跳出（journal …437…:135）。
- **键保持（817607b4）**：requestPositionAndForgetLastKnownKey 核销锚 key 致按字面 index 重锚（LEAP -7562），同帧回写 key（journal …437…:114-116）——**#444 历史修复链的一员**。
- **高度引擎三件套+五连修**（帽/槽位锚键/视口写入单点网关；深/浅/中位与回合末清零实证）（journal …437…:244-264）。
- **底对齐+流式滚动暂缓（f09eb29b 族）**：贴底统计栏弹跳与 fling 卡顿双修（journal …437…:286-288）——**#444 历史修复链的另一员**。
- **架构深审 + R1-R5 根修**：统一配对谓词 486 格决策表（终审评「教科书级」）、A2 单出口、ScrollQuiescence 单点、B3 差量承载、光标叠加层根修；贴底 p50 18→5ms、贴底零泄漏（journal …437…:431-441、553-565、613-624）。
- **终审 Critical 即修+勘误**：单出口重构引入拒绘表达式方向反转，终审（而非自验）发现并修复；二十七世轮「拒绘语义保持」记录失实特此勘误（journal …437…:648-655）——**自验盲区的方法论教训**。
- **#438① 落地（2026-09-27，20c79a8e）**：gate 大放行壁钟限速（BIG_RELEASE_CH=200/间隔≥200ms，与到达解耦）+ releaseLength maxReleaseChars（空行毕业段纳入批预算）+ 首跑多帧铺开；真机 append max 2087→200（backlog.md:91；StreamingMarkdownPilot.kt:106-112）。
- **#446 撕裂根修（e76aed43，2026-09-27）**：毕业 held 收缩侧撤销一帧延迟（帽协议落地后保护对象已消失）；条带差分定罪 b12 底缝差动 26 帧→2 帧（收敛 88%）（journal 2026-09-27-446-tearing-root-fix.md:25-33）。
- **#450 回归根修（8bc1ba32，2026-09-27）**：#437 的 isStreamingMsg 判据放宽（读组内 completed 字段）撞上签名缓存不敏感 completed 转换 → stale 引用卡 isStreamingTurn=true → 统计栏不收敛；换生命周期签名修复（journal 2026-09-27-v2.md:78-93）——**#437 修复族直接滋生的回归，关联分析必读**。
- **#445 收口（2026-09-27 R4）**：完结窗 -67px 条带步进经四层排除链定论=流式尾段限速铺开节奏（BIG_RELEASE_MIN_INTERVAL_MS=200 可调），设计行为非缺陷；EOF 单帧大跳已被 #438① 消除（journal 2026-09-27-v2.md:101-113）。

### 2.3 未完结的现场等待项

- **#444 fling 下滑跳变复发**（backlog.md:276-278）：历史修复链 817607b4/b54650c3/f09eb29b 俱在，嫌疑=R1-A2 单出口合并后的配对 set 行为变化或键保持通道退化；两轮自动取证窗口内零异常（journal …437…:817-839），**定罪依赖用户报「会话+方向+时刻」**，工具链（scripts/frame-jump-analyze.py 等）已固化入库（journal …437…:914-921）。

---

## 3. 现状代码走读（当前实现关键路径）

### 3.1 渲染入口与 pilot 分支（MarkdownContent.kt）

- 完结态走 preParsedState 分支（归一化+分片，EOF 全量 flush 的最终归宿）：MarkdownContent.kt:565-594。
- 流式分支门槛 overrideState==null && !asyncParse && STREAMING_MD_PILOT && !isUser：MarkdownContent.kt:601。
- rememberPilotStreamingMarkdownState(markdown) 取回 PilotStreamingState(state, heldTail)；key(pilotState.state) 强制实例重建消非前缀失配帧（#432 同族崩溃修复）：MarkdownContent.kt:606-612。
- stableReveal 开启时挂载 HeldTailReveal 消费扣留尾：MarkdownContent.kt:624-630。

### 3.2 SafePrefixGate（纯函数闸，零库依赖）

- 设计与不变量 KDoc（纯文字增量直出、单调不回退、扣留去路恒两条=毕业或 EOF、setext 已知理论缺口）：SafePrefixGate.kt:4-30。
- 活动标记集 *_~#>|[]! 加反引号 加 ☐☑✅（tasklist 完结变换字符在列）：SafePrefixGate.kt:39。
- releaseLength(snapshot, alreadyReleased, maxReleaseChars)：**第一级空行毕业**（开放区最后一个空行块之前无条件放行）：SafePrefixGate.kt:54-70；**第二级块级行扫描**（单批预算 MAX_RELEASE_PER_BATCH=400，SafePrefixGate.kt:82-83、:403）——围栏开栏零输出/闭栏整放/超预算按行铺开（:100-113）、表格表头+分隔行齐备后逐行放行（:118-140）、≥4 缩进歧义行扣留（:143）、纯文字行整行放+未完行增量直出（:144-150）、星号列表项/引用块/ATX 标题行级定案（:154-171，#441/#443 扩展）、含活动标记行整行扣留（:172）。
- #438① 总上限（含第一级——原空行毕业不受批预算是「整块出」漏洞之一）：SafePrefixGate.kt:175-180；releaseDelta 包装（pilot 铺开循环用）：SafePrefixGate.kt:364。

### 3.3 StreamingMarkdownPilot（差分接线 + 限速 + 暂缓）

- 开关对象：STREAMING_MD_PILOT / STABLE_REVEAL_PILOT（StreamingMarkdownPilot.kt:32-35）。
- rememberPilotStreamingMarkdownState 主效应（StreamingMarkdownPilot.kt:127-242）：
  - **滚动暂缓**：holding 期间不 append，settle 后整段一次追平（一次重排版）：:143-145（R3 单点 ScrollQuiescence :76-85）。
  - **首跑多帧铺开**：多消息 turn 后续段全量到达时逐帧放行 + 大放行壁钟限速：:150-171。
  - **非前缀风暴抑制**：FlapDetector 限频冻结重建，旧串回来无缝恢复：:182-196。
  - **增量分支**：每批喂 BIG_RELEASE_CH=200，≥阈值触发 ≥200ms 壁钟间隔（与到达解耦）：:197-225；常量 :111-112。
  - **#446 修复**：held 收缩与正文扩张同帧（撤销 withFrameNanos 延迟，帽协议承接净高）：:228-239。
- 观测日志（gate 放行量/扣留量、append stable/tail 规模）：:245-260。

### 3.4 扣留尾呈现（当前为「静默扣留」形态）

- HeldTailAgingState：超龄 300ms（REVEAL_AFTER_MS，HeldTailAging.kt:78）；**2026-09-25 用户裁决「未闭合构造零输出」后超龄揭示整体关闭——visible 恒 false**（HeldTailAging.kt:54-57），锁高/步进参数（125ms/400px，#445 平滑化）保留为无害休眠：HeldTailAging.kt:83-94。
- HeldTailReveal 组件：超龄轮询 + 锁高裁剪 layout + 呼吸光标独立叠加层（R2 根修，动画失效只作用光标小方块）：HeldTailReveal.kt:82-188；因 visible 恒 false 实际不渲染（:116 早退）——**文件内 KDoc（:40-48 描述超龄可见）与实际行为不一致，属文档债**。

### 3.5 高度配对侧（gate 的下游契约）

- 流式增长经 StreamingGrowLedger 记账 + PreRenderCoordinator pre-draw flush 单出口派发（ScrollCompensation.kt:126）；一帧缓冲帽 streamingHeightReserve（ScrollCompensation.kt:292）当帧裁增量、flush 只放行净增长——#446 修复注释明确「列表永不见负增量」（StreamingMarkdownPilot.kt:230-237）。
- isStreamingMsg 判据=组内任一 completed==null（#437 放宽，#450 踩坑点）：ChatMessageList.kt:1719；帽挂载 :1765。

### 3.6 开关分布（重要现状）

- dev：STREAMING_MD_PILOT=true、STABLE_REVEAL_PILOT=true（build.gradle.kts:92-94）；beta（:108-110）与 stable（:117-118）**双双关闭**——#437 修复目前仅 dev 生效，注释明示「#265 试点未达标前 beta/stable 不放开」。

### 3.7 测试资产

- SafePrefixGateTest 39 例 + SafePrefixGateBlockGranularityTest 6 例（判定表）；HeldTailAgingTest 5 例；StableTailBoundaryTest 6 例（R2 双容器撞墙后保留的复用件）；全量 3500+ 用例多轮绿（journal …437…:631、:919）。

---

## 4. 根因分析（原根因已修，剩余根因链逐条定罪）

### 4.1 原根因（已解决，置信度：高）

**不稳定尾先字面排版后回溯重释义 → 已显示内容高度回溯 → #435 引擎无法配对负增量 → 视口跳变**。证据链：录屏 A-B 翻转帧定罪（backlog.md:94）+ 修复后真机「库内 unstable tail≈0」+ 220 帧无翻转对 + R7 像素冻结（journal …437…:21-26、:91）+ 二十轮后各构型清零（journal …437…:246-264）。gate 从源头保证放行流单调（SafePrefixGate.kt:23-26 不变量），帽协议保证列表永不见负增量（StreamingMarkdownPilot.kt:236-237）。历史取证全部能对应到现存代码位置：两级放行=SafePrefixGate.kt:54-181；量子化=SafePrefixGate.kt:403 + StreamingMarkdownPilot.kt:111-112；纯文字直出=SafePrefixGate.kt:144-150；超龄关闭=HeldTailAging.kt:54-57；键保持=ScrollCompensation.kt 配对 set 通道。

### 4.2 剩余根因/残差（未解决项）

| # | 残差 | 证据 | 置信度 |
|---|---|---|---|
| R-1 | **#444 fling 下滑跳变复发未定罪**。嫌疑清单：A 巨型 item 预组合超预算 / B SafeFling 限速 1/8 对多视口高巨项给不够时间 / C **流式中 turn 不分段**（isStreamingTurn 抑制）= fling 扫过面 / D 崩溃防御 retries 吞位移；另有 R1-A2 单出口行为变化嫌疑 | journal …437…:860-877（嫌疑清单）、backlog.md:278 | 中（形态未捕获，依赖用户现场；**待验证**：用户报「会话+方向+时刻」→ scripts/frame-jump-analyze.py + DEBUG-flng 探针定罪，journal …437…:906-909） |
| R-2 | **O(总内容) append/测量成本**：每批 append 触发帽全子树重测真高（「用重测换原子性」的协议性代价）；滑动 p90 19-27ms 未达 12 目标；双容器（稳定+活跃尾）被 StreamingMarkdownState append-only 库约束撞墙——「块毕业时尾换头」只能重建实例=首帧空内容=高度塌一帧=闪烁 | journal …437…:399-406（架构定罪）、:798-807（撞墙结论）、:784（p90 未达） | 高（O(内容) 证据链：单/双 turn p50 6→12ms，journal …437…:547-548） |
| R-3 | **#438② 配对 set 保 key 未动**：大额配对 set 路径的键保持强化仍未做（817607b4 已加同帧回写，但卡片明示「②未动（下轮）」） | backlog.md:91 | 高（卡片明示） |
| R-4 | **大块毕业原地重排混沌 1-2 帧**（fence/表格闭合单批 RESIZE +284/+744）：SafePrefixGate 整块放行的固有节奏，归 #443 粒度扩展域 | journal 446:21、:37 | 高（#446 取证中实测） |
| R-5 | **完结切换窗 -67px 步进**：#445 R4 定论=限速铺开节奏（设计行为），但用户观感「会小跳一下」仍登记在案 | backlog.md:274；journal 2026-09-27-v2.md:107-113 | 高（机制定论）/低（是否再修待用户拍板） |
| R-6 | **setext 升格理论缺口**：---/=== 紧随文字行可重释义已放行内容；旧闸同样存在，模型输出以 ATX 为主，接受 | SafePrefixGate.kt:29-30 | 高（已知已接受；若上线后实测出现可补行扫描扣末行规则） |
| R-7 | **beta/stable 未放开**：修复只在 dev 生效 | build.gradle.kts:108-118 | 高（事实） |
| R-8 | **闪烁消失（CONTENT-BLINK）偶发 bug 未定罪**：键换血形态已全面排除（散文+表格 ~5min 窗口零事件）；剩余解释空间=偶发率极低或非键变化形态（绘制层/markdown 块重渲染瞬时空白） | journal …437…:727-745 | 中（**待验证**：用户复现时刻×CONTENT-BLINK 探针对齐；零行则转绘制层取证） |

### 4.3 关联卡片图谱（本调研核实）

- **#437 ↔ #444**：#444 是 #437 系修复后的 fling 回归复发卡（backlog.md:276）；其历史修复链三 commit（817607b4 键保持/b54650c3 巨额 set 量子化/f09eb29b 滚动暂缓）全部诞生于 #437 验收轮（journal …437…:93-116、:286-288）——**同一配对体系的正反面**。
- **#437 ↔ #438**：#438①（gate 壁钟限速+批预算）已落在 #437 同一路径（StreamingMarkdownPilot.kt:106-218、SafePrefixGate.kt:175-180）；②保 key 未动（backlog.md:91）。
- **#437 ↔ #443/#441**：粒度扩展系（表格逐行/列表项/引用块/ATX/缩进保护）全部是对 SafePrefixGate 第二级行扫描的 TDD 扩展（SafePrefixGate.kt:115-171；journal …437…:750-763、:960-966）。
- **#437 ↔ #445**：R2 双容器撞墙结论与 stableTailBoundary 资产直接登记进 #445 深水区（backlog.md:269-270）。
- **#437 ↔ #446/#450**：#446 修复对象是 #437 阶段 B 遗留的 held 收缩延帧（journal 446:25）；#450 是 #437 判据放宽的次生回归（journal v2:78-82）——**改 isStreamingMsg 判据时签名缓存必须对 completed 敏感，此教训应进铁律**。

### 4.4 与既有调研的分歧/缺口（如实标注）

- docs/research/sse-scroll-stability-iron-laws.md（权威文档，最后实质更新 2026-09-25）**未收编 #437 任何内容**（grep SafePrefixGate/StreamingMarkdown/437 零命中，仅 #435 更新至 :40、:46）——铁律文档停在引擎配对时代，gate/帽/静默扣留体系未入律。AGENTS.md SSE 段（AGENTS.md:104-112）同样只提 #265/#435。非结论冲突，但属**文档同步缺口**：新工程师按铁律文档操作不会知道 gate 的存在。
- HeldTailAging.kt KDoc（:6-12 描述超龄降亮区行为）与实际 visible 恒 false（:54-57）自相矛盾；HeldTailReveal.kt KDoc 同病——文档债。
- journal 内两处自我勘误须沿用最新结论：二十七世轮「拒绘语义保持」被三十七世轮推翻（journal …437…:648-655）；四十五世轮「回底跳切」被复核为采样伪影（journal …437…:829-839）。

---

## 5. 修复方案空间

### 方案 A（收口+定向定罪，保守）——把 #437 本体关账，残差按既有分卡推进

- **内容**：①执行 spec §6 验收矩阵的剩余人工项（V6 体感：纯文字流出/块成型/完结窗观感）→ backlog.sh migrate 迁移 #437；②#444 走既定用户现场定罪协议（journal …437…:906-909）后按嫌疑 A-D 修；③#443 按卡片裁决逐类 TDD 扩展行扫描分支；④beta/stable 放开评估（依据=迁移后用户日常使用反馈）。
- **影响面**：零新架构；主要是流程与既有卡片执行。
- **风险**：低。#444 若复现形态在 fling 途中，可能牵出配对 set 通道改造（滑向 #438②）。
- **与 SSE 铁律/既有能力兼容性**：完全兼容——不动铁律五条；gate 单调性不变量（SafePrefixGate.kt:23-26）与「锚即意图」配对（AGENTS.md:111）已验证共存。
- **TDD 切入点**：#443 每类块型先写判定表红例（如 def list 形态、长代码块行级）再实现；#444 定罪后先写 SafeFlingBehavior 帧预算/预组合时限的不变量测试（钉住「穿越任何内容 ≥8 帧」语义，journal …437…:864-866）。

### 方案 B（节奏参数化+流式分段，中度）

- **内容**：①BIG_RELEASE_CH/BIG_RELEASE_MIN_INTERVAL_MS/HEIGHT_REFRESH 参数升为产品可调（远程配置或设置项），消化 #445 用户观感「完结窗小跳」（R-5）；②流式 turn 适度分段或预组合扩窗（嫌疑 C/B：流式中不分段=fling 扫过巨项面）；③gate 预算从字符基改 px 基感知（journal …437…:142 曾议「若仍觉跳感可下调预算或改 px 基」）。
- **影响面**：StreamingMarkdownPilot 常量与分段策略；需重跑 scripts/stream-flicker-test.sh 矩阵。
- **风险**：中——参数化不当会重新引入「突发整块出」（#438① 定罪的形态）或过度限速的「打字机拖沓感」；分段触碰 recentStreamedTurnKeys/RenderSupply 视口门控（journal 2026-09-27-v2.md:105 曾据此排除 chunk plan，语义需重新核实）。
- **兼容性**：不违反铁律；但 cadence/限速参数分散在数据层（MessageEventHandler）与 pilot 两处，距 spec 2026-09-26-437-height-engine-redesign.md:14「节奏收编引擎域」的裁决 3 尚有距离。
- **TDD 切入点**：先写「限速参数与到达速率解耦」的壁钟单测（突发 1000ch 进来，放行节奏仍守 ≥200ms/200ch）与「完结窗步进幅度 ≤ 阈值 px」的帽参数单测。

### 方案 C（激进：自研 append-only+前缀吸收渲染状态 / 库 fork，完全重写双容器）

- **内容**：按四十四世轮结论（journal …437…:800-807），替换或改造 mikepenz StreamingMarkdownState——自研「append-only+前缀吸收」渲染状态：稳定容器只追加、活跃尾容器吸收增量，块毕业时**前缀吸收**（而非实例重建）使过渡帧零空白零重叠；实现 R2 字面目标（稳定块高度缓存+增量 chunk 离屏预测量+多槽 per-item），append 成本 O(总内容)→O(尾块)，滑动 p90 冲 12ms（backlog.md:270）。
- **影响面**：大——markdown 渲染状态层重写或库 fork（0.7.9 钉住，spec …437…design.md:98 开放问题 3）；pilot/帽/配对全链回归复验；#445 深水区主体。
- **风险**：高——#428 Loading 短高入测 / #437 失配帧两历史教训同源（重建首帧空内容=塌一帧，journal …437…:802-803）；自研文本测量与库排版一致性需对齐；工程量 L+。
- **兼容性**：与铁律 1（rememberMarkdownState 语义）需要等价替代论证；与帽协议的关系需重新设计（帽退役为「预留高度表」的消费者，spec 2026-09-26-437-height-engine-redesign.md:37-38 已预留此演进）。
- **TDD 切入点**：先以 StableTailBoundaryTest（6 例已备）为芯，写「毕业迁移帧零高度塌陷」不变量（前缀吸收前后容器总高逐帧单调不减）与「append 成本 O(尾块)」基准测试钉住，再动实现。

---

## 6. 建议

- **推荐：方案 A 为主（预估 S~M），B 中期选做（M），C 作为 #445 深水区长期项（L）维持登记**。
- 理由：
  1. #437 的**本体根因已修复且有二十余轮真机定量证据**（tail≈0 铁证、像素冻结、贴底 5ms、3573 单测），剩余是流程收口（验收迁移）与分卡残差——为已证可行的东西重写（C）不成比例；C 的触发条件应是方案 A/B 后 p90 仍不达标且用户持续投诉（#445 已登记，随时可启）。
  2. #444 是当前唯一「用户可感知且未定罪」的活跃回归，但它**卡在用户现场信息**而非代码认知——先执行定罪协议再修，避免盲修引入新回归（#450 教训：修复族本身会滋生回归）。
  3. 铁律文档同步（iron-laws 收编 gate/帽/静默扣留三条 + #450 判据教训）应随 A 一起做（S 内零风险），否则下一位改滚动的人会踩已排过的坑。
- 工作量：A = S（验收矩阵执行+迁移+文档同步；#444 定罪后修复另计 M）；B = M；C = L（独立设计批，spec 已有架构 2 原案与 stableTailBoundary 资产）。

---

## 7. 引用清单

**backlog 卡片**：#437 backlog.md:93-94 · #438 backlog.md:87-91 · #444 backlog.md:276-278 · #443 backlog.md:280-281 · #445 backlog.md:269-274

**spec**：docs/specs/2026-09-25-437-streaming-md-stable-reveal-design.md:9-22（问题定义/六层定罪表）、:24-36（两级放行）、:38-55（规则/活性）、:69-98（阶段/验收/风险）· docs/specs/2026-09-26-437-height-engine-redesign.md:9-11（I1/I2 契约）、:33-45（帽实现裁决变更记录）

**journal**：docs/journal/2026-09-25-437-streaming-md-stable-reveal.md（主档案，967 行五十二世轮；关键节 :9-30 阶段A-D、:41-54 三bug、:56-97 四~十轮、:99-146 十一/十二轮、:148-231 十三~十五轮、:232-264 引擎三件套、:283-300 二一/二三轮、:302-430 卡顿归因+深审、:431-664 R1-R5+终审、:666-745 闪烁消失排除、:747-810 #441+R2撞墙、:812-955 fling 取证+终收、:957-967 粒度扩展）· docs/journal/2026-09-27-446-tearing-root-fix.md:18-37 · docs/journal/2026-09-27-v2.md:71-113（#450/#445）· docs/journal/2026-09-27-card-intervention-growth-phase.md:85、:113-114（流式静默门控+勘误）

**research**：docs/research/sse-scroll-stability-iron-laws.md:24-46（铁律 1/3/4 及 #435 更新；无 #437 内容=同步缺口）· 433/436 系列（2026-09-25 前管线/重连测绘，本卡根因在渲染层不在其范围）

**源码**（app/src/main/kotlin/dev/leonardo/ocbeacon/）：ui/screens/chat/markdown/SafePrefixGate.kt:4-30,39,54-181,364,403 · ui/screens/chat/markdown/StreamingMarkdownPilot.kt:32-35,44-47,76-92,106-124,127-242 · ui/screens/chat/markdown/MarkdownContent.kt:565-594,601-633 · ui/screens/chat/markdown/HeldTailAging.kt:54-57,78,83-94 · ui/screens/chat/markdown/HeldTailReveal.kt:65-79,82-188 · ui/screens/chat/components/ScrollCompensation.kt:126,292 · ui/screens/chat/components/ChatMessageList.kt:1719,1765 · app/build.gradle.kts:92-94,108-118

**测试**：app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/markdown/ 下 SafePrefixGateTest.kt（39 例）· SafePrefixGateBlockGranularityTest.kt（6 例）· HeldTailAgingTest.kt（5 例）· StableTailBoundaryTest.kt（6 例）

**AGENTS.md**：SSE 滚动稳定性铁律 AGENTS.md:104-112 · ChatScreen 编辑协议/验证要求（docs/chatscreen-editing-protocol.md、docs/verification.md，V1-V6 框架）

**提交链**（git log 只读取证）：8596515b（量子化+反射 set）→ 8bd5ead1（收缩延帧+透明度）→ a421410a（块级重写+超龄关闭）→ b54650c3（超龄量子化）→ 8c1c3b2a（纯文字直出）→ 817607b4（键保持）→ 5e35a066/79c1000c/9c794590（#441 粒度）→ 20c79a8e（#438① 限速）→ e76aed43（#446）→ 8bc1ba32（#450+#445 平滑化）

---

*调研日期：2026-09-28 · 调研代理：#437 深度调研（只读取证，未改动任何源码/配置）*