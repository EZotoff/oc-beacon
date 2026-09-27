# 2026-09-28 · #442 高度引擎根修二期 调研报告（R2 分片增量化 + cadence 收编 + flush 深拆 + 终审待复核项）

> **一句话结论**：#442 的四项注记中「cadence 收编」「StreamingPairingRule 缝退役」「终审待复核四项」已在一期后续 goal（2026-09-26～27，commit 5d061df2 / 12513603 / journal 四十六世轮）实际完成但卡片未销账；**真正剩下的主体是 R2 测量增量化（滑动 p90 19-27ms 未达 ≤12ms 目标，根因=流式 turn 单容器单 LazyItem 使每批 append 的组合/测量成本 O(总内容)）与 flush 八职责深拆（终审 S2）**，且本次走读新发现一个终审级问题：让位防御 shouldYieldPairing 的状态变量被写在 flush 任务 lambda 体内、每次 onPreDraw 重置为 null——**该防御在生产中实际失活**（ScrollCompensation.kt:366-369，与 #444 fling 跳变复发嫌疑同域，待真机验证）。

---

## 1. 问题陈述

### 1.1 用户可感知症状

- **流式输出期间非贴底滑动卡顿**（主诉）：assistant 输出中上滑/下滑阅读时掉帧、"一顿一顿"。仪器画像：滑动 p50=6ms 已达标，**p90=19-27ms 未达 ≤12ms 目标**（基线 24-46ms，docs/journal/2026-09-25-437-streaming-md-stable-reveal.md:312-317、:486、:562、:586、:662）。
- **贴底跟随帧率**（已达标，作背景）：基线 p50=18ms → 现 5ms（A2 双确认，journal:615-616）。
- 成本随内容量增长：单 turn 会话滑动 p50=6ms，双 turn 12ms，深位流式 42ms——**O(总内容) 特征确凿**（journal:547-548）。

### 1.2 触发条件

- SSE 流式 turn 进行中（SessionStateService streaming 态）+ 用户滑动/惯性滚动；滚动帧撞上 100ms 批的「append→markdown 排版→帽全子树测量→布局→配对滚动」全链成本（journal:352、:400-406）。
- 流式 turn 越长（内容越多），单帧成本越高——这正是 R2 要从 O(内容) 降为 O(尾块) 的部分。

### 1.3 卡片定位

- 卡片原文（backlog.md:177-180，P2）：
  - 终审判定：R1 批次已锁 A2 贴底 5ms / A1 回归 / A4 全项；滑动 p90 19-27 未达 12——R2 分片（稳定块缓存/尾块单独测量）是 O(内容)→O(尾块) 唯一路径。
  - Medium 欠账：cadence 结构收编（spec 裁决 3）、flush 八职责深拆（终审 S2）、StreamingPairingRule 缝退役迁移。
  - 待复核：ScrollQuiescence 单例假设、HeldTail 锁高裁剪视觉等价、diffDisplayItemsInto 边界、SSE 铁律逐条。

---

## 2. 已知历史（逐条带引用）

### 2.1 一期 #435（2026-09-25，已落地）

- StreamingGrowLedger / streamingGrowPairing（ScrollCompensation.kt 重写）+ ChatMessageList 5 挂载点迁移 + steady flush 贴底豁免；PreRenderShiftChannel / DeferredRevealCompensator / stream-instant 退役（净 -326 行）。单测 16 例；真机三构型全绿（贴底 33 条免派发零震荡 / 尾段 14 条 pair 全额 / 读历史零扰动）。docs/journal/2026-09-25-435-height-engine-unification.md:11、:28-34。
- 「锚即意图」统一规则：`pair(Δ) ⟺ anchorIndex == itemIndex ∧ anchorOffset > 0`，贴底原点/读历史免派发（docs/specs/2026-09-25-435-height-engine-unification-design.md:26-41）。

### 2.2 #437 主弧：稳定揭示 → 架构定罪 → R1-R5 路线图

- **稳定揭示四件套**（SafePrefixGate 两级放行 / HeldTailAging 超龄 / HeldTailReveal 锁高降亮 / FlapDetector，journal:11-18），及后续多轮真机定罪修复（量子化 ≤400ch/批、反射 requestPosition 预定位、ChatScrollController 流式静默，journal:58-66）。
- **二十四世轮卡顿归因**（gfxinfo/framestats）：流式 p90=24-46ms、janky 47.3%；绘制/GPU 无辜，input+traversal 暴涨；慢帧锁定 48ms 批节奏（journal:310-332）。
- **cadence 快赢**：48ms→100ms tunable（journal:354-356；spec 裁决落地）。
- **二十五世轮双轴架构深审（#442 的直接出身）**，根因三条定罪（journal:399-406）：
  1. 帽协议把测量堵死在全量档——每批全子树重测真高（贴底 p50=18ms 主源）；
  2. 100ms 周期全链重组未收口（非贴底滑动 p90=24-46ms 主源）；
  3. 每帧常驻微成本长尾。
- **根修路线图**（journal:422-429）：R1 合并配对规则为单一纯函数；**R2 测量增量化=落 spec 原案「预留高度表」（稳定块高度缓存+增量 chunk 离屏预测量+多槽 per-item）——18ms→8ms 唯一结构路径，重写级工程**；R3 ScrollQuiescence 单点；R4 flush 拆职责+探针注入化；R5 网关强制化。
- spec 侧同步留档：帽替代预测量管线属「字面偏离」，**R2 重构将回到架构 2 原案、帽退役为表的消费者**；cadence 常量当时仍住数据层（docs/specs/2026-09-26-437-height-engine-redesign.md:31-45）。

### 2.3 R1 批次成果与终审（三十五～三十七世轮）

- R1 统一谓词 StreamingAnchorRule + 决策表穷举（journal:434-442；StreamingAnchorRuleTest）；R1-A2 同帧双 set 单出口（journal:447-451，commit d28360d8）；R3 ScrollQuiescence 唯一写点（journal:452-454）；R4-B3 步1/步2（entry 身份编码 + displayItems 差量承载，journal:462-468、:576-583）；R2 首项=呼吸光标移出 Text inline（commit 85d437fb，journal:506-520）；R5 最小版=网关外写入登记表（journal:620-621）。
- 指标进程：滑动 p90 46→17（二十九世轮）→24（三十二世轮）→19（三十三世轮）ms；A2 贴底 p50 5ms 双确认、A3 贴底零泄漏、A4 全项（journal:613-624）。
- **三十六世轮 R4 判定**：flush 深拆「不必要」（单出口后结构已收敛）——journal:629-636。
- **三十七世轮终审**（双轴代理）：结论「未达验收标准，不建议以此状态关闭」（journal:645）：
  - [Critical·已修] 拒绘表达式方向反转（a7daa1b0，单出口重构引入；终审而非自验发现）；
  - [Medium·登记] **cadence 结构收编、flush 八职责深拆（终审认为 S2 成立，R4 判定反转获第三方背书）**（journal:657-658）→ 即 #442 的 Medium 欠账行；
  - [待复核转下批] ScrollQuiescence 单例假设 / HeldTail 锁高裁剪等价 / diffDisplayItemsInto 边界 / SSE 铁律逐条（journal:659-660）→ 即 #442 的待复核行。
  - Goal-1 收口如实不标 complete：滑动 p90 未达 12（19-27，R2 分片主体未做）（journal:662-664）。

### 2.4 二期 goal（轮 21-31，2026-09-26 深夜～09-27）：R2 撞墙 + 挂账双清 + 待复核解除

- **四十四世轮 R2 双容器撞库约束墙（关键负结论）**：双容器（稳定 state+活跃尾 state）要求「块毕业时尾换头」；StreamingMarkdownState 为 **append-only（无前缀移除）**，换头只能重建实例；重建首帧空内容=高度塌一帧=闪烁（#428/#437 失配帧两历史教训同源）；过渡帧双活=重叠闪烁。**结论：零闪烁双容器需替换状态管理（自研 append-only+前缀吸收 layout，或库改造）——超出本批预算，另立设计批**（journal:798-807）。已落资产：stableTailBoundary 纯函数 6 例（commit 4da695b6）。
- **转轨双清**：cadence 收编（spec 裁决 3）+ StreamingPairingRule 缝退役（4 挂载点直调统一谓词）——journal:809-810；commit **5d061df2**（终审 S4 cadence 常量收编引擎域）与 **12513603**（终审 S3 缝退役）。Goal 终收列为本期完成项（journal:947-951）。
- **四十六世轮：终审「不能默认无恙」清单四项全部复核无恙**（journal:844-852）：ScrollQuiescence 单例（快照态+私有 setter+唯一写口）；SSE 铁律四条（scheduleFlush 不取消/rememberMarkdownState/autoScroll 双键/isStreamingMsg 门控）；diffDisplayItemsInto 边界（中间插删→长度变化→全量重置，语义安全）；HeldTail 锁高迁移（容器层裁剪含叠加光标，视觉等价）。
- fling 复发取证两轮零异常（S22 双向序列），收束到用户现场模式（journal:812-839、:898-909）。
- Goal-2 终收：如实不标 complete——R2 双容器字面目标不可行（转 #445 深水区），fling/闪烁待用户复现（journal:938-945）。

### 2.5 相邻批次（同窗口，影响 #442 判读）

- **#438① 大放行壁钟限速**（2026-09-27，commit 20c79a8e）：BIG_RELEASE_CH=200/间隔≥200ms，append max 2087→200ch；**②配对 set 突发保 key 未动**（backlog.md:91）。
- **#446 撕裂根修**（2026-09-27，commit e76aed43）：毕业 held 收缩撤销一帧延迟——帽协议落地后延迟的保护对象已消失；R5 对照 b12 族 13→2 帧（docs/journal/2026-09-27-446-tearing-root-fix.md:25-34）。该修复与 #442 的帽/flush 语义强耦合。
- **#449 勘误**：流式期 MSGEFFECT/GUARD 全静默（ChatScrollController !streamingActive() 门控），「跳转不关 autoOn」原告证伪（docs/journal/2026-09-27-card-intervention-growth-phase.md:85、:113-115）。
- **#450/#445 尾段**：turnGroups 签名缓存换生命周期签名；完结窗 -67px 步进定案为限速铺开节奏（设计行为），深水区维持登记（backlog.md:271-274）。

### 2.6 账目差异（本节重要：卡片注记已部分过时）

| #442 注记项 | 现状 | 证据 |
|---|---|---|
| cadence 结构收编（spec 裁决 3） | **已完成**（常量归引擎域，数据层消费） | ScrollCompensation.kt:74-78 + MessageEventHandler.kt:78-81、:339-353；commit 5d061df2 |
| StreamingPairingRule 缝退役迁移 | **已完成**（类已删除，ledger 直调统一谓词 covered=true） | ScrollCompensation.kt:179-184；commit 12513603；全仓无该类定义 |
| flush 八职责深拆（终审 S2） | **未做**（flush 任务仍为单体，见 §3.4） | ScrollCompensation.kt:362-537 |
| 终审待复核四项 | **已全部复核无恙并解除** | journal 四十六世轮（:844-852） |
| R2 分片主体（滑动 p90 ≤12） | **未做**（唯一实质遗留工程） | journal:662、:784、:943-944 |

> 与既有调研的分歧标注：433-height-engine-map.md §4 称「流式文本 item 增长引擎完全不管（PreRenderShiftChannel 承担）」——该地图成图于 2026-09-22（#435 之前），此节已被 #435 统一化**整体取代**，阅读时须按变更日志（sse-scroll-stability-iron-laws.md:286）校正；本报告以当前代码为准。

---

## 3. 现状代码走读

### 3.1 管线全景（当前实现）

```
SSE token 到达
  ↓ MessageEventHandler.scheduleFlush()（不取消进行中定时器，MessageEventHandler.kt:320-328）
  ↓ delay(streamFlushIntervalMs())=100ms（常量 STREAM_FLUSH_INTERVAL_MS 定义于引擎域
    ScrollCompensation.kt:78，数据层别名消费 MessageEventHandler.kt:80-81 —— S4 收编落点）
  ↓ StateFlow 单次发射 → ChatScreen 快照冻结（滚动期 JankHoldGate，ChatScreen.kt:943-947）
  ↓ displayItems 差量承载 diffDisplayItemsInto（DisplayItemsDiff.kt:13-15，ChatScreen.kt:988-1050）
  ↓ LazyColumn 流式 item（单容器）：
      Markdown(streamingMarkdownState)  ← pilot 前缀差分 append + SafePrefixGate
      + HeldTailReveal（锁高裁剪+呼吸光标）（MarkdownContent.kt:601-632）
  ↓ 帽 streamingHeightReserve（增长当帧 clip；ScrollCompensation.kt:292-316）
  ↓ measure 相：StreamingGrowLedger.note 记 Δ（ScrollCompensation.kt:141-158）
  ↓ pre-draw flush 单点（PreRenderCoordinator.registerFlushTask，
    ChatMessageList.kt:677-683；streamingGrowFlushTask ScrollCompensation.kt:362-537）
  ↓ StreamingAnchorRule 统一谓词求值 → requestScrollToItemNoCancel 预定位（+key 回写）
  ↓ 渲染（拒绘语义：ledger 派发帧拒绘，ScrollCompensation.kt:531-534）
```

### 3.2 统一谓词与账本（R1 成果，现状）

- `StreamingAnchorRule.pairedDelta`（ScrollCompensation.kt:80-99）：贴底原点（fii==0∧fiso<8px）免派发；锚==增长源 +Δ 同帧配对；锚<增长源由 coveredByFollowFamily 决定；读历史免；收缩/零增量免。测试 StreamingAnchorRuleTest（决策表穷举，7 个 @Test 方法覆盖全状态空间）。
- `StreamingGrowLedger`（:126-190）：per-entryKey 基线+pending；冷启动静默建基线；收缩不配对只 rebase；takePaired 时 ledger 源族直调统一谓词（covered=true，原 StreamingPairingRule 兼容缝已删，:179-184）。
- `shouldYieldPairing`（:108-117）：读位≠上批 set 目标 ⇒ 外部 pending 未消费 ⇒ 配对让位（三十八世轮防御）。纯函数本身正确（PairingYieldTest:19-32），**但接线有致命问题，见 §3.8**。

### 3.3 一帧缓冲帽（#437 引擎①，R2 的改造对象）

- `HeightReserveState`（ScrollCompensation.kt:276-289）：reserved 单调只增快照态；trueHeight measure 相写（仅物主，所有权主张制 :300-302）；alignBottom 随态（贴底底对齐/阅读顶对齐）。
- `streamingHeightReserve`（:292-316）：**每遍 measure 都对 child 无界全量测量取真高**——这就是「帽把测量堵死在全量档」的代码落点（journal:400-401 定罪 1 的对应物）。挂载于 ChatMessageList.kt:1765-1770（isStreamingMsg‖reserveOwner）。
- `reserveReleasePlan`（:326-350）：纯函数释放决策（未初始化/无增量/手势持帽 → null）。

### 3.4 flush 任务八职责枚举（终审 S2 的拆分对象）

`streamingGrowFlushTask`（ScrollCompensation.kt:362-537）单 lambda 体内并列八项职责：

1. 帽对齐随态翻转（align-flip 刀锋条件，:372-392）；
2. 帽释放计划计算+延迟合并（reserveReleasePlan + pendingReserveRelease，:397-417）；
3. VTRACE 视口轨迹观测（限频，:419-444）；
4. ScrollQuiescence 单点写（R3 唯一写口，:445-447）；
5. 空账早退 + 滚动弃配 rebaseAll（:448-462）；
6. 外部 pending 让位 shouldYieldPairing（:466-475）；
7. ledger takePaired + 帽/账本单出口单事务 set + 键回写（R1-A2，:476-521）；
8. 拒绘返回值语义（ledger 派发帧拒绘，终审 P1 修复处，:531-534）+ DEBUG 日志（:449-458、:522-530）。

### 3.5 pilot：gate / 限速 / 静止

- `ScrollQuiescence`（StreamingMarkdownPilot.kt:76-85）快照态单信号源；`StreamingScrollHold`（:88-92）为 R3 迁移期委托缝（只读）；JankHoldGate（:114-124）默认开。
- `rememberPilotStreamingMarkdownState`（:126-242）：前缀差分 append + SafePrefixGate 两级放行 + 非前缀风暴抑制（FlapDetector）+ **#438① 大放行壁钟限速**（BIG_RELEASE_CH=200/200ms，:106-112、:199-218）+ 首跑多帧铺开（:150-171）；滚动暂缓 append（:140-145）；**#446 同帧收缩**（:228-238，撤销一帧延迟）。

### 3.6 渲染路径：单容器（R2 的物理载体）

- MarkdownContent.kt:601-632：流式分支 = 单个 `Markdown(streamingMarkdownState)` + HeldTailReveal，**整个流式 turn 的全部已放行内容在一个库状态实例、一个 LazyItem 内**。完结后切换 preParsedState 分片路径（:565-594）。**生产代码中无稳定/尾双容器拆分**——StableTailBoundary 仅测试引用（见 §3.7）。

### 3.7 R2 已落资产与未落码部分

| 项 | 状态 | 位置 |
|---|---|---|
| stableTailBoundary 纯函数（块毕业迁移点） | 已落，**仅测试消费**（StableTailBoundaryTest 6 例） | StableTailBoundary.kt:8-23；grep 全 main 无调用 |
| 呼吸光标叠加层（O(动画) 根修） | 已落（生产） | commit 85d437fb；HeldTailReveal.kt |
| 预留高度表（稳定块高度缓存+增量 chunk 离屏预测量+多槽 per-item） | **未落码**（grep 预留高度表 main 零命中；spec 原案） | spec 2026-09-26 架构 2（:15）+变更记录 1（:33-38） |
| 双容器稳定/活跃分离 | 未落码（撞库约束墙，转 #445） | journal:798-807 |

### 3.8 新发现（本次走读）：让位防御在生产中失活 ⚠️

**代码事实（置信度：高，静态可证）**：`lastSetFii`/`lastSetFiso` 声明在 `PreDrawFlushTask { … }` **lambda 体内**（ScrollCompensation.kt:366-369），是 onPreDraw() 的每次调用局部变量——**每次 flush 进入时都重置为 null**。而 `shouldYieldPairing(fii, fiso, lastSetFii, lastSetFiso)`（:467）在 set 之前求值，其语义「读位≠**上一次** set 目标」要求跨调用持久（:108-117 KDoc「null 目标=首批不让位」）。结果：

- 让位分支（:467-475）**永远走不到**（lastSet 恒 null → shouldYieldPairing 恒 false）；
- :518-519 的赋值是**死存储**；
- 三十八世轮「外部 pending 未消费时配对让位」防御（commit cb733d80 引入这两个变量；journal:678-679 记「PairingYieldTest 3 例+回归绿+装机」）**在生产线路上从未生效**——测试只覆盖纯函数，未覆盖接线，与终审 P1（拒绘方向反转）同属「flush 任务布尔语义无接线测试」盲区（journal:653-655 教训原文）。

**影响评估（置信度：中，待验证）**：流式期 MSGEFFECT/GUARD 本就静默（#437 六轮定规、#449 勘误），外部 pending 场景稀少，故未爆发为常显 bug；但在「fling settle 瞬间外部写入 pending + 下一批配对 set 覆盖写」的窗口，该防御缺位会复现 cb733d80 要修的「配对抵消显式意图→视口被推走」形态——**与 #444（fling 下滑跳变复发，backlog.md:276-278 嫌疑「R1-A2 单出口合并后的配对 set 行为变化或键保持通道退化」）同一嫌疑域**。
**验证方法**：① logcat 检索 `SGR-435 yield(external-pending)`（:471）历史真机日志——若在存在外部 pending 的窗口恒零出现即坐实失活；② 修复（变量外提为捕获态）后跑 fling 复现场景对照。

---

## 4. 根因分析

### 4.1 主根因链：流式 turn 单容器 → 每批 append 成本 O(总内容)（置信度：高）

证据链：
1. 帽每遍无界全量子树测量（ScrollCompensation.kt:299-302）+ 单一库状态承载全部内容（MarkdownContent.kt:606-623）——机制上每批 append 触发全 item 重排链；
2. 二十五世轮架构定罪「帽协议把测量堵死在全量档」（journal:400-401）；
3. O(内容) 经验证据：单 turn p50 6ms → 双 turn 12ms → 深位 42ms（journal:547-548）；
4. R 系（重组面收口）修复后 p90 从 46→19-27ms 平台期，剩余成本被定位为「append 批排版/测量（R2 本体）」（journal:497-498、:662）。

> 注：「Compose 失效传播本应只重测变化块，为何全量」的精确机制（库 Markdown 组件对 stable AST 增量的失效粒度、Column 包装层的测量级联）journal 未做 Perfetto 级定罪——机制细节标注**待验证**（方法：Perfetto/Compose track 采一批 append 帧的测量调用树），但「O(总内容)」结论由 1-4 独立支撑，不依赖该细节。

### 4.2 次根因：flush 任务单体八职责（置信度：高）

- 事实：§3.4 枚举，单函数 175 行八职责。
- 终审 S2 判定拆分有价值（journal:657-658，R4「不必要」判定被第三方反转）；终审 P1 Critical 正是发生在这段单体的布尔语义上（journal:648-655）——单体结构与「接线无测试」盲区互为因果。
- §3.8 新发现的失活防御是该论点的又一实证。

### 4.3 防御失活（让位让不上）（代码事实置信度：高；症状影响置信度：中·待验证）

见 §3.8。与 #444 构成「嫌疑同域」而非「已定罪」——#444 真因待用户现场（会话+方向+时刻）按已固化工具链定罪（journal:906-909、scripts/frame-jump-analyze.py）。

### 4.4 突发路径残余（#438②，置信度：中）

- 键回写通道存在且在单出口路径接通（ScrollCompensation.kt:501-517 传 targetKey；:645-653 反射回写 lastKnownFirstItemKey，commit 817607b4）；
- 但突发期「新 item 插入+重排」窗口内目标键不在可见集（while 循环 :503-508 找不到 anchor 即 key=null）→ 字面 index 重锚风险仍在；backlog #438 注记「②配对 set 保 key 未动（下轮）」（backlog.md:91）。**待验证**：构造突发插入窗真机复测 LEAP。

### 4.5 文档漂移（置信度：高，低危）

- AGENTS.md:106 与 iron-laws:13 仍写「48ms token 批处理」，实际 cadence 已 100ms（ScrollCompensation.kt:78）；ScrollCompensation KDoc「48ms 节奏的结构性继承」（:48-51）同款过时。
- 433-height-engine-map.md §4 的引擎边界描述已被 #435 取代（§2.6 分歧标注）。
- #442 卡片注记未销已完成项（§2.6 表）——按 backlog 纪律应经 backlog.sh 更新。

### 4.6 与其他卡片的关联（报告要求项）

| 卡片 | 关系 |
|---|---|
| **#437**（P1 父卡，[ ]） | #442 是其根修路线图 R2/R4 的二期载体；#437 收口依赖 #442 的 p90 达标 |
| **#445**（P3 深水区，[~]） | 同为 R2 的两翼：#442=分片增量化（item 级拆分路径）；#445=双容器状态管理替换（库约束墙后的设计批）。二十七世轮已预判二选一：「分片唤醒 vs 双容器分离」（journal:455-456） |
| **#444**（P3，[ ]） | fling 跳变复发嫌疑直指 R1-A2 单出口/键保持通道——即 #442 将要深拆的 flush 单出口路径；§3.8 失活防御为其新增嫌疑 |
| **#438**（P1，[ ]） | ②突发保 key 与 #442 的 set 通道同体；R2 改造时应一并收口 |
| **#439**（P2，[ ]） | 流式期重组隔离（entries 签名缓存）与 R4-B3 同域，R2 复测时数据会互相影响 |
| **#441**（P1，[ ]） | SSE 断连毒害测试通道——#442 真机复测的前置环境风险（journal:501、:945） |
| #446（已迁移） | 毕业收缩同帧化重塑了帽/flush 语义，R2 设计必须基于其后状态 |

---

## 5. 修复方案空间

### 方案 A：分片唤醒（item 级拆分，引擎契约不变）——渐进主案

**思路**：把流式 turn 的**已毕业稳定块**从「活跃流式 item」中拆出为独立冻结 chunk item（复用完结 turn 已有的 #258 chunk plan / TurnSegmentPlan 基建与键序语义），活跃 item 只保留尾块（gate 已放行的最后一未闭合段 + held）。append/帽/配对只作用于尾块 item → 单帧成本 O(尾块)。毕业迁移点=空行块边界=stableTailBoundary()（资产现成）。

- **影响面**：装配层（buildChatEntries 流式分支发多个 item）、StreamingGrowLedger（per-item 键记账，现成的 entryKey/itemKey 分离设计 ScrollCompensation.kt:122-124 正为此预留）、帽归属（HeightReserveState 本就单物主）、完结切换（EOF flush 时尾块 item 并回/或直接保留 item 化结构=顺带减轻 #440/#445 完结窗换装）、跳转键族、#420 配对语义（多 item 增长源同帧求值——#435 spec §5「多 item 同时增长：同 flush 单帧合并求值」已备案）。
- **风险**：①与「流式恒平铺」(#422/#439 键策略) 的交互——需要 recentStreamedTurnKeys 式保护防毕业拆分引发键换血闪烁（三十八世轮候选①教训，journal:684-698）；②item 边界处的视觉接缝（chunk item 间零间距已有先例 ChatMessageList.kt:1792-1795）；③配对谓词对「锚落在稳定块 item、增长在尾块 item」的格子需决策表扩展（anchor>growth 读历史格现成=免派发，行为天然正确，但需补格）。
- **与 SSE 铁律兼容**：不触碰 scheduleFlush 不取消、rememberMarkdownState、双 key、isStreamingMsg 门控四条；帽/账本/单出口契约不变（铁律第 5 条「流式增长配对走高度引擎统一规则」维持）。
- **TDD 切入点**：①先写「毕业计划」纯函数测试（snapshot+released → 稳定块区间列表+尾块区间；stableTailBoundary 为子件）钉住**单调性不变量**（毕业迁移只前进不回退，等价 gate 放行流单调）；②扩展 StreamingAnchorRuleTest 决策表补「锚在稳定块/增长在尾块」格子，钉住**免派发不漂移**；③StreamingGrowLedgerTest 补多 item 同帧求值用例；④帽所有权测试：毕业瞬间帽物主从整 item 切到尾块 item 时的净高连续性（防 #446 同族撕裂回归）。真机判据：stream-flicker-test.sh + gfxinfo 滑动 p90 ≤12ms；VDRAW 泄漏=0 回归。

### 方案 B：预留高度表 + 预测量管线（spec 架构 2 原案完整落地）——激进案（模块重构/局部重写）

**思路**：按 spec 原案回归「只允许预测量」：增量 chunk 在施加前离屏测出精确高度，引擎持有**预留高度表**（多槽 per-item），增长帧绘制前同一 measure pass 完成「高度生效+offset+Δ」；**帽退役为表的消费者**（spec 变更记录 1 明文：R2 重构将回到原案，2026-09-26-437-height-engine-redesign.md:33-38）。表的前置能力=增量测量，而这被 StreamingMarkdownState append-only 约束卡死 → 必须与 #445 合并实施：自研 append-only+前缀吸收渲染状态，或改造/替换 markdown 库的流式状态层（journal:800-805）。

- **影响面**：重写级——渲染状态层（替换/包装 StreamingMarkdownState）、帽协议退役、flush 任务重构（与 S2 深拆天然合并）、pilot append 管线改写、#445 全部设计域。触及 SSE 铁律第 1 条的实现载体（Markdown 状态管理），需重钉「前缀差分 append」等价语义。
- **风险**：最高。库改造/自研 layout 是多周工程；四十四世轮已实证「库约束下不可零闪烁实现双容器」，自研路径无先例；且预测量「同约束测量确定性、零误差」的假设（spec Q2 裁决）在宽度依赖（表格 containerWidth 两拍收敛、asyncParse 迟到——#422 一轮教训）下需要误差兜底条款。
- **收益上限**：单帧成本与视口内容量解耦（O(尾块)且免帽全量重测），贴底 p90/p99 批帧尾（38/57ms，journal:616）与滑动 p90 同时收口；架构回到 spec 原案的单一真相。
- **TDD 切入点**：①先写预留高度表协议单测（预测量值施加前后 t₀+Σ增量≡任意时刻高度——I2′ 契约的数值化）；②前缀吸收状态机的性质测试（吸收块高度冻结、尾块 append 只失效尾块）；③帽退役等价性测试（表驱动的释放计划与 reserveReleasePlan 输出逐格一致后才切轨）。

### 方案 C：账面收口 + flush 深拆（保守案，不含 R2 主体）

**思路**：不动渲染结构。①按 §2.6 销账（backlog.sh note 更新 #442：cadence/缝退役/待复核四项已完成）；②执行终审 S2：把 §3.4 八职责拆为独立小函数/对象（对齐随态器、释放计划器、观测器、静止写点、让位判定器、单出口执行器、拒绘策略），每个职责配接线级单测；③顺手修 §3.8 失活防御（变量外提+补接线测试）；④文档漂移清理（AGENTS.md/iron-laws 48ms→100ms）。
- **影响面**：ScrollCompensation.kt 内部重构+测试，零行为变化（除失活防御复活）。
- **风险**：低；但 p90 目标不达成——#442 主体继续挂账。
- **TDD 切入点**：先为八职责各写「拆分前后输出等价」表征测试（characterization），特别是拒绘返回值与 lastSet 状态机（终审 P1 教训的直接补课）。

### 对比

| 维度 | A 分片唤醒 | B 预留高度表 | C 收口+深拆 |
|---|---|---|---|
| p90≤12 概率 | 高（直击 O(内容)） | 最高（结构性解耦） | 无 |
| 工程量 | M~L | L（含 #445） | S |
| 风险 | 中（键策略/接缝） | 高（库替换/零误差假设） | 低 |
| 铁律兼容 | 契约不变 | 需重钉铁律 1 载体 | 完全兼容 |
| 依赖 | 现有基建+#439 协同 | #445 设计定稿 | 无 |

---

## 6. 建议

1. **先做 C（S，1 批次）立即收口**：销账 + S2 深拆 + 修复 §3.8 失活防御 + 文档漂移。理由：深拆是 A/B 的安全网（单体拆开后再动 R2，布尔语义接线测试已在位）；失活防御修复可能直接缓解 #444（同一批真机验证顺带定罪）。
2. **主体走 A（M~L，分 3-4 批）**：与二十七世轮数据驱动的既定次序一致（journal:455-456「R4 后真机复测再定 R2 深度——分片唤醒 vs 双容器分离」）；复用 #258/#431/#422 切片基建与 stableTailBoundary 资产，不触发库替换；每批真机复测 p90（判据 gfxinfo 滑动 p90≤12ms、VDRAW 泄漏=0、CONTENT-BLINK=0）。
3. **B 保留为 A 复测不达标后的升级路径**，以 #445 设计批为载体先行论证（自研 append-only+前缀吸收的最小可测原型先于生产接线）；spec 变更记录 1 已为其预留合法性（帽退役为表消费者）。
4. **环境前置**：#441 断连与 #452/#447 通道问题解决前，真机复测窗口不可靠——A 批次开工前先确认流式通道稳定（journal 多轮「取证被通道阻断」教训）。
5. 预估工作量：C=S（1 批）；A=M~L（3-4 批：毕业计划纯函数→装配层多 item→帽/配对适配→真机调优）；B=L（设计 1 批+实施 2-3 批，含 #445）。

---

## 7. 引用清单

**backlog**：#442 backlog.md:177-180 · #438 :87-91 · #437 :93-94 · #435 :96-100 · #441 :83-85 · #439 :182-184 · #445 :269-274 · #444 :276-278

**journal**：docs/journal/2026-09-25-437-streaming-md-stable-reveal.md（主弧：:310-341 二十四世轮 / :343-387 cadence / :389-430 二十五世轮定罪+路线图 / :431-457 R1+A2+R3 / :462-498 B3 与中期数据 / :503-548 贴底定罪+O(内容) / :550-589 三十二/三十三世轮 / :610-638 A2-A4+R4 判定 / :640-664 终审 / :765-793 Goal-1 终章 / :795-810 R2 撞墙 / :841-852 待复核解除 / :855-878 fling 图景 / :923-955 Goal-2 终收）· docs/journal/2026-09-25-435-height-engine-unification.md:11,:28-34 · docs/journal/2026-09-27-446-tearing-root-fix.md:25-48 · docs/journal/2026-09-27-card-intervention-growth-phase.md:56-63,:85,:113-115

**spec**：docs/specs/2026-09-26-437-height-engine-redesign.md:8-18（契约+架构）,:31-45（实现裁决变更记录）· docs/specs/2026-09-25-435-height-engine-unification-design.md:26-59 · docs/specs/2026-08-30-streaming-markdown-state-pilot-design.md（pilot 出处）

**research**：docs/research/433-height-engine-map.md（引擎地图，§4 已过时）· docs/research/sse-scroll-stability-iron-laws.md:22-45,:286（铁律+变更日志）

**源码**：ScrollCompensation.kt:74-99（cadence+谓词）,:108-117（让位纯函数）,:126-190（账本）,:276-316（帽）,:326-350（释放计划）,:362-537（flush 八职责）,:366-369（⚠失活变量）,:556-665（反射+键回写）· ChatMessageList.kt:668-683（flush 挂接）,:1765-1770（帽挂载）,:1850/:2189/:2419/:2498（配对挂载）· MessageEventHandler.kt:320-353 · StreamingMarkdownPilot.kt:76-124,:126-242 · MarkdownContent.kt:565-632 · HeldTailReveal.kt · StableTailBoundary.kt · DisplayItemsDiff.kt · ChatScreen.kt:943-1050

**测试**：StreamingAnchorRuleTest · PairingYieldTest:19-32（纯函数覆盖、接线无覆盖）· StableTailBoundaryTest（6 例）· StreamingGrowLedgerTest · ScrollQuiescenceTest · HeldTailCursorTest

**commits**：5d061df2（S4 cadence 收编）· 12513603（S3 缝退役）· a7daa1b0（终审 P1 拒绘反转）· d28360d8（R1-A2 单出口）· cb733d80（让位防御引入，⚠接线缺陷同源）· 4da695b6（stableTailBoundary）· 85d437fb（光标叠加层）· e76aed43（#446 同帧收缩）· 20c79a8e（#438① 限速）
