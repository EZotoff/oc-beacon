# #438 调研报告：流式突发路径收尾——gate 时间限速与配对 set 保 key

> 日期：2026-09-28 · 调研代理产出（只读调研，未改任何源码）
> 关联卡片：#437（父弧）/ #444（fling 跳变复发，疑同族）/ #445（完结切换窗，①的直接下游观感）/ #443（gate 粒度扩展）/ #446（撕裂根修，残余族与本卡交界）

## 一句话结论

**#438①（gate 大放行壁钟限速 + releaseLength 批预算）已完整落地并有真机证据；②（配对 set 保 key）的机制层在 817607b4 已实现且在 R1-A2 单出口合并后仍然存活，但存在三个未闭合缺口——(a) 键回写仅在「落点位于可见布局内」时生效，落点不可见时静默退化为字面 index 重锚（R9 LEAP -7562 的残余通道）；(b) 同路径的 shouldYieldPairing「外部 pending 让位」防御是死接线（记忆变量在每帧重置的 lambda 体内声明，永不触发）；(c) 键解析/回写逻辑内联在 flush 任务闭包中无任何单测覆盖——建议以「提取纯函数缝 + TDD 钉死不变量 + 真机复验」收尾（工作量 S-M），并与 #444 联合定罪。**

---

## 1. 问题陈述

### 1.1 用户可感知症状

R9（2026-09-25 验收十轮）真机实证的两个突发路径残差（docs/journal/2026-09-25-437-streaming-md-stable-reveal.md:95）：

- **残差①（gate 时间维度）**：catch-up 期 gate 按 400ch/48ms 释放而 measure 滞后聚合——观测 442ms 内聚合 7 批 = 单 note d=6236px，配合中继缓冲突发（POST 后 22s 零 delta、随后 12s 内 900+ delta ≈ 500-800ch/s，2026-09-27-v2.md:40），用户观感为「大 markdown 块（表格/有序无序列表）又整体一次性输出」——之前「视觉换行就输出」的逐行感消失（用户原话见 2026-09-27-v2.md:33-34）。
- **残差②（配对 set 键维度）**：大额配对 set 走 requestPositionAndForgetLastKnownKey 会核销（遗忘）LazyList 的 lastKnownFirstItemKey；突发期新 item 插入 + 重排后 LazyList 按字面 index 重锚，产生 LEAP -7562px 级视觉大跳（f17→f26 混沌帧窗，backlog.md:88）。

### 1.2 触发条件

- ①的三个触发源（2026-09-27-v2.md:39-42 三层定罪）：中继/服务器缓冲后突发推送（catch-up，主因，上游）；多消息 turn 的后续段全量到达（无 delta 流的首跑，单帧 append 1136-2087ch）；空行毕业段不受批预算（SafePrefixGate 第一级 cand 直接放行）。
- ②的触发复合条件：用户视口锚在流式增长源内部（锚==增长源，配对派发路径活跃）**且** 单帧配对 total 足够大或同帧发生 item 插入/重排（新 turn 条目、横幅、换装）——此时目标落点换算走出可见布局窗口，或下一遍 measure 前数据层插入了新 item。
- 贴底跟随（fii==0 ∧ fiso<8px）走物理跟随免派发分支，不触发配对 set（ScrollCompensation.kt:92）——②只影响「读流式中段」构型，与 R9 复现场景一致。

## 2. 已知历史（卡片 + journal 取证沉淀，逐条带引用）

### 2.0 时间线速查

| 时刻 | 事件 | 证据 |
|---|---|---|
| 09-25 20:03 | 8596515b gate 放行量子化 ≤400ch/批（防单帧 1830px 暴涨）| journal 四~六轮 |
| 09-25 深夜 | R9 立卡：双残差（gate 442ms 聚 7 批 + LEAP -7562）| 2026-09-25-437...md:87-97 |
| 09-26 01:00 | 817607b4 键回写落地（落点可见时同帧回写 key）| 2026-09-25-437...md:114-118 |
| 09-26 01:00 | a421410a gate 块级重写（表格逐行等，#441 前身）| git log |
| 09-26 16:56 | f09eb29b 帽 child 底对齐 + 流式滚动暂缓 | git log |
| 09-26 20:40 | d28360d8 R1-A2 单出口合并（applyReserveRelease 退役）| 2026-09-25-437...md:447-451 |
| 09-26 21:26 | a7daa1b0 终审 P1：拒绘表达式方向反转修复 | 2026-09-25-437...md:643-655 |
| 09-26 21:45 | cb733d80 shouldYieldPairing 让位防御 | 2026-09-25-437...md:666-679 |
| 09-26/27 | 四十五世轮 fling 复发取证：未复现（零 LEAP/零巨额 set）| 2026-09-25-437...md:815-839 |
| 09-27 16:16 | 20c79a8e #438① 壁钟限速落地（append max 2087→200）| 2026-09-27-v2.md:44-52 |
| 09-27 | #444 立卡：fling 下滑跳变复发，嫌疑指向 A2 后配对 set 变化 | backlog.md:276-278 |
| 09-27 | #445 R4 终：完结窗步进=①限速铺开节奏，设计行为 | backlog.md:273-274 |

### 2.1 逐条取证史

1. **R9 定位双残差并立卡**（2026-09-25）：验收十轮在量子化验证 + 人为停顿场景下捕获——阶梯 pair d=1600（步进上限精确出现）证明超龄量子化生效；同时登记两残差，「两处为下一卡片：流式突发路径收尾——gate 时间限速与配对 set 保 key」（2026-09-25-437-streaming-md-stable-reveal.md:91-97）。卡片修法方向：gate 释放按壁钟限速（与到达解耦）；配对 set 后在同帧重建 lastKnownKey 或改用保 key 的定位通道（backlog.md:89）。
2. **超龄量子化前置修复（b54650c3，2026-09-25）**：HeldTailAging 首亮 ≤800px + 步进 ≤1600px/500ms——根因是中继停顿冲刷使扣留区瞬时积压（chars=1091→单帧 7378px）一次性落地，打穿 gate 量子化并迫使配对单帧巨额 set（git show b54650c3 commit message；2026-09-25-437...md:93）。注意现行参数已平滑化为 400px/400px（HeldTailAging.kt:91-94；#445 取证补充「帽步进平滑化 125ms/400px」2026-09-27-v2.md:97-99）。
3. **②机制层已修（817607b4，2026-09-26 01:00）**：验收十一轮「②震荡残留」——R9 LEAP -7562 根因落锤：requestPositionAndForgetLastKnownKey 丢 lastKnownFirstItemKey（javap 钉死实名），插入/重排按字面 index 重锚。修法：反射探针增可选字段，flush 相溢出换算**落点可见时**同帧回写 key；缺失自动降级。LazyListReflectionTest 冒烟钉死。真机冒烟：贴底流式全部走 drop（免派发物理跟随）分支，零 LEAP、零大额 set（2026-09-25-437...md:114-118；commit 817607b4 改动 ChatMessageList/ScrollCompensation/LazyListReflectionTest 三文件）。
4. **R1-A2 单出口合并（d28360d8，2026-09-26 20:40）**：flush task 同帧双滚动 set（帽先落、ledger 覆盖——requestPosition 覆盖写非叠加，双补偿只活一笔）合并为单事务单出口，applyReserveRelease 退役（2026-09-25-437...md:447-451；diff 核实：退役的 applyReserveRelease 与合并后的单出口**均保留** targetKey 计算 + 回写调用，见 §3.3）。
5. **终审 Critical 即修（a7daa1b0，2026-09-26 21:26）**：R1-A2 引入拒绘表达式方向反转（ledger 配对帧不拒绘 = I1′ 违约通道敞开），终审发现并修复为 ledger 派发帧拒绘；同时勘误二十七世轮「拒绘语义保持」记录失实——教训：单出口重构的布尔语义翻转应配对显式单测（2026-09-25-437...md:643-655）。**这条教训直接适用于本卡：flush task 的返回值/目标解析缝至今无测试覆盖。**
6. **让位防御加入（cb733d80，2026-09-26 21:45）**：shouldYieldPairing——外部 pending（GUARD/ForceScroll 显式意图）未消费时配对让位（位置神圣），PairingYieldTest 3 例锁纯函数（2026-09-25-437...md:666-679）。三十八世轮将其定性为「非彼 bug 根因但属正确防御」保留——**注意：真机从未证实 yield 分支实际触发过**（见 §4.2 R-2 死接线发现）。
7. **①完整落地（20c79a8e，2026-09-27 16:16）**：大块整出三层定罪（中继缓冲突发主因/多消息全量首跑/空行毕业段无预算）→ 三修：首跑多帧铺开 + #438① 壁钟限速（BIG_RELEASE_CH=200 / BIG_RELEASE_MIN_INTERVAL_MS=200）+ gate 单次放行上限 maxReleaseChars；真机 gran-run4 实测 append max 1023-2087 → 200（2026-09-27-v2.md:44-52；卡片 note backlog.md:91）。app 放行链路本身无回归（run1/2/3 共 240+ 条 release，p50=9-11ch、p90=41-44ch，表格区逐行放行正常，2026-09-27-v2.md:38）。
8. **#445 结论修正（2026-09-27 R4 终）**：完结切换窗 -67px uniform 步进实为流式尾段限速铺开节奏（66-68px/300-400ms），与 RESIZE 序列 7↔7 一一对应（时间轴对齐偏移 ~3s）——设计行为非缺陷；旧「完结瞬间单帧大跳」已被 #438① 消除。用户观感验收记录「完结窗会小跳一下」，后续视情况调参（backlog.md:273-274；2026-09-27-v2.md:101-113）。
9. **#444 登记同窗（2026-09-27）**：用户报告 fling 下滑跳变复发（原 #437 系修复后回归）；嫌疑明确写为「R1-A2 单出口合并后的配对 set 行为变化或键保持通道在新路径的退化」，历史修复链 817607b4/b54650c3/f09eb29b 供溯源（backlog.md:276-278）。此前四十五世轮两轮取证（S22 双向 fling + VTRACE/SGR 121 行）**未复现**任何 LEAP/中途跳变（2026-09-25-437...md:815-839）——症状依赖用户现场（「会话+方向+时刻」协议，工具链已固化入库 scripts/frame-jump-analyze.py 等）。
10. **#446 残余族划界（2026-09-27）**：完结切换窗混沌（EOF 全量重挂 3930ch + SKIP 78 + RESflush 10826→231）标注为「#438/#445 已知家族，不属本卡范围」（2026-09-27-446-tearing-root-fix.md:22,37）。

### 2.2 记录分歧（如实标注）

**卡片 note 与 journal 存在表述张力**：卡片 note「②配对 set 保 key 未动（下轮）」（backlog.md:91）字面上与验收十一轮「②已修（817607b4）」（2026-09-25-437...md:114）冲突。代码核查（§3.3）支持如下调和：**机制已在**（键回写代码在当前 HEAD 存活），note 的「未动」应读作「②的收尾工作（可见性洞补全 / #444 关联复验 / 测试覆盖）未做」——即本卡②的真实剩余范围。另一种可能读法是 note 作者笔误未察觉 817607b4 的存在；两种读法对行动项的影响一致（都要做 §5 的方案 A），如实留档。

## 3. 现状代码走读

### 3.1 ①的实现（已落地，证据充分）

- 常量：BIG_RELEASE_CH=200 / BIG_RELEASE_MIN_INTERVAL_MS=200，KDoc 注明「把中继缓冲突发下的 catch-up 观感从单帧砸出变为快速但分块出现；正常流式批次（p50=9ch、p90=41ch）直通」（StreamingMarkdownPilot.kt:106-112）。
- 首跑多帧铺开：p==null 分支改 while 循环，每批喂 BIG_RELEASE_CH，批 ≥ 阈值即 delay 到壁钟间隔（lastBigReleaseAt 壁钟，:137-138/:160-165），帧间 withFrameNanos 让帧（StreamingMarkdownPilot.kt:148-171）。
- catch-up/增量分支同语义限速（StreamingMarkdownPilot.kt:197-219）。
- gate 侧：releaseLength/releaseDelta 增 maxReleaseChars 参数（默认无限兼容既有调用/测试），空行毕业段纳入批预算，截断点可落行中由 lineStartReal 防御覆盖（SafePrefixGate.kt:44-54，capped 计算 :179）；溢出坑（floor+Int.MAX_VALUE wrap）以先比较后相加排除（2026-09-27-v2.md:47）。

### 3.2 配对派发管线（②的宿主路径）

- **统一配对谓词** StreamingAnchorRule：贴底原点（fii==0 ∧ fiso<8px）物理跟随免派发；锚==增长源 +Δ 同帧配对；锚>增长源（读历史）免；锚<增长源按源类型 coveredByFollowFamily（ScrollCompensation.kt:60-99）。
- **账本** StreamingGrowLedger：per-entryKey 记账（entryKey 与 itemKey 分离——同条目内多增长源互斥挂载防双计），flush 相 takePaired 按 visibleIndex(itemKey) 反查求值，-1=不在可见布局丢弃（ScrollCompensation.kt:120-190）。
- **帽** HeightReserveState（快照态 reserved/trueHeight + 对齐随态）+ reserveReleasePlan 纯函数（ScrollCompensation.kt:264-350）。
- **flush 任务** streamingGrowFlushTask 挂 PreRenderCoordinator 单点（remember 注册于 ChatMessageList.kt:677-683）；PreDrawFlushTask 为 fun interface（onPreDraw(): Boolean），**每帧 pre-draw 由 runFlush 逐任务调用**，任一返回 false 则本帧拒绘（PreRenderCoordinator.kt:16-18,126-136）。

### 3.3 ②的现状：机制在、覆盖有洞、无测试

flush 任务单出口核心（ScrollCompensation.kt:492-534）逐步：

1. total = reserveShift + ledgerTotal（R1-A2 合并：帽 plan 延后与 ledger 配对同帧叠加，:476-480）。
2. 目标位换算（关键代码摘录，:499-508）：

```kotlin
var targetFii = fii
var targetFiso = fiso + total.toInt()
var targetKey: Any? = null
var guard = 0
while (guard++ < 64) {
    val anchor = infos.firstOrNull { it.index == targetFii } ?: break  // ← 落点不在可见布局 → break
    if (targetFiso < anchor.size) { targetKey = anchor.key; break }    // ← 仅此时拿到 key
    targetFiso -= anchor.size
    targetFii++
}
```

   **targetKey 仅在落点 item 位于当前 layoutInfo.visibleItemsInfo 内时才能解析到；越窗则 break 且 targetKey 保持 null，但仍会以裸 index 执行 set（:516-517）**——这正是 R9 LEAP 的残余通道（详见 §4.2 R-1）。
3. 单事务 {帽释放 + requestScrollToItemNoCancel(targetFii, targetFiso, targetKey)}（:510-521）。
4. 反射执行器 requestScrollToItemNoCancel：invoke requestPositionAndForgetLastKnownKey 后，**key 非空时同帧回写 lastKnownFirstItemKey 字段**；字段缺失（版本漂移）或 invoke 异常时降级官方 requestScrollToItem（取消 fling 但功能等价不崩溃）（:637-664，回写在 :645-653）；探针解析 :576-591，字段 KDoc 明确「set 同帧回写与目标位一致的 key 即消除」重锚跳变（:561-568）。
5. 拒绘语义（终审 P1 修复后）：ledger 派发帧拒绘（return ledgerTotal == 0f 为放行条件，:531-534）。
6. R1-A2 diff 逐行核实：退役的 applyReserveRelease（内含同样的 tKey walk + 回写）与合并后单出口**两条路径都携带** targetKey 计算 + 回写——「A2 丢了键保持」的怀疑（#444 嫌疑表述）在代码层面**不成立**；A2 改变的是时序（帽 set 不再先落、合并单事务）与 total 的合成方式（叠加而非覆盖）。

**测试覆盖现状**：LazyListReflectionTest 只钉**字段可解析**（BOM 在场冒烟 + 类消失/签名漂移降级路径，LazyListReflectionTest.kt:32-56）；PairingYieldTest 只测**纯函数** shouldYieldPairing（PairingYieldTest.kt 全文 3 例）；StreamingAnchorRuleTest/ReserveReleasePlanTest 覆盖谓词与释放决策。**目标位换算（含 null-key 分支）、键回写调用行为、yield 跨帧记忆——三者零单测**——逻辑内联在 flush 任务闭包里，无提取缝。

### 3.4 相邻路径（划界）

- JumpNavigationController 跳转族走官方 scrollToItem（内部同为 requestPositionAndForgetLastKnownKey 但经 scroll{} 互斥锁有序执行，「锚 key 语义不变」，反射 NoCancel 仅保留给 SSE 高度补偿——ChatMessageList 两处调用点，JumpNavigationController.kt:294-303）。
- ViewportDispatchGateway R5 登记表：引擎配对/帽释放 = 网关外显式豁免（单出口事务本体）；卡片展开 episode/跳转族/snapToBottom/ScrollIsland 各有豁免条目（ViewportDispatchGateway.kt:14-27）——②的任何修复不得新增网关外写入族。
- 433 三篇管线图（433-streaming-pipeline-map/433-height-engine-map/433-compose-viewport-research）描述的是 #435 之前的 COMP-MSG/PreRenderShiftChannel/GUARD 架构，DeferredRevealCompensator/PreRenderShiftChannel 已退役（ScrollCompensation.kt:628-630 注释明示）——**该组文档对本卡的配对细节参考价值有限且部分过时**，以 sse-scroll-stability-iron-laws + 当前代码为准。

## 4. 根因分析（论断均标注证据与置信度）

### 4.1 已解决部分（①）：置信度 高

证据链闭环：三层定罪（2026-09-27-v2.md:39-42）→ 代码在位（§3.1）→ 真机 gran-run4 append max 200 钳制生效（2026-09-27-v2.md:51）→ 下游观感闭环（#445 R4 终结论：EOF 一次性大跳已消除，backlog.md:273）。无遗留根因；BIG_RELEASE_MIN_INTERVAL_MS=200 为产品可调参数（用户若需更强逐行感可调大，2026-09-27-v2.md:52）。中继突发本身（上游）不在 app 修复范围。

### 4.2 ②的未闭合根因链

**R-1（主根因·键解析可见性洞）**：targetKey 解析依赖「落点 item 在 layoutInfo.visibleItemsInfo 内」（ScrollCompensation.kt:501-508）。落点走出可见窗口（大 total 换算越过流式巨项顶部、或可见窗内 item 高度快照滞后于数据层）时 targetKey=null，requestScrollToItemNoCancel 仍按字面 index 写入待定位（:516-517，且 key=null 时跳过回写 :647）——下一遍 measure 若发生 item 插入/重排（新 turn 条目、横幅插入、换装），LazyList 无 key 可依即按 index 重锚 = R9 LEAP 机制。817607b4 的修复语义本身就是条件式的（「落点可见时同帧回写」，2026-09-25-437...md:115-116）。**置信度：高**（代码直读）。触发频率评估：①落地后单批 total 显著变小（200ch ≈ 数百 px），**但** EOF flush 剩余铺开、超龄步进 400px、reserveShift+ledgerTotal 同帧叠加、以及插入/重排窗（infos 快照陈旧）仍可命中，通道未关死。**待验证**：真机复现「锚内阅读 + 突发」构型（发送→10s→上滑入流式 item 阅读位→人为停顿冲刷/二次消息触发突发），logcat 观察是否出现 set(...) 且 key 未回写的帧——建议先在 :517 前加 DEBUG 打点 targetKey==null 计数再复现。

**R-2（新发现·让位防御死接线）**：shouldYieldPairing 的记忆变量 lastSetFii/lastSetFiso 声明在 PreDrawFlushTask lambda **体内**（ScrollCompensation.kt:366-369），而该 lambda 是每帧调用的持久实例（fun interface + runFlush 逐帧执行，PreRenderCoordinator.kt:130-136；remember 注册一次 ChatMessageList.kt:677-683）——局部变量**每次调用重新初始化为 null**，shouldYieldPairing 的 null 分支恒返回 false（:108-117：「null 目标=首批不让位」），「外部 pending 未消费→配对让位」**永不触发**（:466-475 的 yield 分支不可达）。cb733d80 的 diff 证实自引入即如此（变量声明在 lambda 体内，git show cb733d80）；PairingYieldTest 只测纯函数未测接线——终审 E3 同款「缝无测试覆盖」盲区。后果：GUARD/ForceScroll 显式意图的 pending 与引擎配对 set（requestPosition 覆盖写）互搏通道重新敞开——正是 cb733d80 定罪的「触底被抵消 + 每批 +Δ 推走视口」形态（『上方内容闪烁消失』家族）。**置信度：高**（Kotlin lambda 局部变量语义明确；cb733d80 作者意图「读位≠上批 set 目标」需要跨帧记忆，现实现给不出）。**待验证**：历史/新取证 logcat grep "yield(external-pending)" 预期 0 行即实锤死接线；修复后应在 GUARD pending 场景能观察到 yield 行。

**R-3（结构性·反射通道的版本脆弱性）**：requestPositionAndForgetLastKnownKey 双 Int 签名是 value-class 未装箱巧合（K9 调研结论，LazyListReflectionTest.kt:10-13），lastKnownFirstItemKey 私有字段同样靠反射。已有 LazyListReflectionTest 冒烟钉死（BOM 升级先红于发版），降级路径可用——**风险已被自动化护栏控制**；但「保 key」本质上是对框架私有状态的补丁式矫正，升级后字段**语义**变化（如 LazyList 内部 key 匹配规则变化）不会被签名冒烟捕获。**置信度：高**（护栏在）；**影响概率：低**（BOM 稳定期）。

**R-4（关联·#444 因果未定）**：#444 的 fling 下滑跳变复发与②是否同族**未定罪**——四十五世轮自动取证未复现（零 LEAP/零巨额 set，2026-09-25-437...md:815-839），#444 嫌疑清单指向「A2 后配对 set 行为变化或键保持退化」，但代码核查显示 A2 未丢键回写（§3.3-6）。候选解释排序：(a) R-2 死接线使 fling settle 窗的 GUARD/配对互搏复活——机制上最直接对应；(b) R-1 可见性洞在 fling 后新 item 插入窗命中；(c) 独立成因（SafeFling/预取族，四十七世轮嫌疑 A-D 清单 2026-09-25-437...md:871-878）。**置信度：低（因果未定）**；**验证方法**：用户现场「会话+方向+时刻」→ scripts/frame-jump-analyze.py（录屏抽帧互相关突跳检测）+ DEBUG-flng/VTRACE 对齐（定罪协议已固化，2026-09-25-437...md:905-909）。

### 4.3 已证伪/排除的假设（负结果留档）

1. **「A2 单出口合并丢了键保持」——不成立**（本次 diff 核实，§3.3-6）：退役路径与合并路径都携带 key walk + 回写。#444 卡片的嫌疑表述据此可收窄。
2. **「app 放行链路回归导致整块出」——不成立**（2026-09-27-v2.md:38）：240+ 条 release p50/p90 正常，表格逐行放行在岗；真因是上游中继突发 + 首跑全量 + 空行毕业段无预算。
3. **「贴底冒烟零 LEAP = ②已死」——不能外推**：贴底构型走物理跟随免派发（StreamingAnchorRule 贴底原点，ScrollCompensation.kt:92），根本不进配对 set 路径；R9 复现②的构型是「锚在增长源内部」（R7: fii=7 fiso=222 锁步配对段之后的停顿冲刷，2026-09-25-437...md:91）。**验证②必须构造锚内阅读 + 突发/插入场景**。
4. **「fling 途中视口异常」——四十五世轮两轮未复现**（含一次「回底跳切」勘误为采样伪影，2026-09-25-437...md:829-839）；结论是复现依赖用户现场，非机制排除。

## 5. 修复方案空间

### 方案 A（保守·补洞 + 提取可测缝）——「把 817607b4 的条件式修复补成无条件式」

- **内容**：
  1. 提取目标位换算为纯函数 resolvePairedTarget(fii, fiso, total, keyAt: (Int) -> Any?)（从 flush 任务闭包抽出，ScrollCompensation.kt:499-508）。
  2. **null-key 熔断**：落点不可见（keyAt 返回 null）时禁止无 key 的大额 set——默认语义取「本帧放弃 set + ledger.rebaseAll() 交下帧重新起账」（与读位偏差让位、手势让位同族的「正确性优先于完整性」），可选 clamp 到最后一个可解析 item 边界 + 该 item key；杜绝 index 重锚通道。
  3. 修复 R-2：lastSet 记忆提升为 flush 任务构造期捕获（streamingGrowFlushTask 函数体内、PreDrawFlushTask lambda 外声明，闭包捕获即跨帧持久）——最小 diff；或文件级变量（对齐 vtraceLastFii 先例，ScrollCompensation.kt:56-58）。
- **影响面**：单文件（ScrollCompensation.kt）+ 新测试文件；不改任何挂载点/网关/数据层。
- **风险**：低。null-key 熔断让极端帧少一次配对（视觉上少一次补偿），换取消灭重锚大跳；语义与既有让位分支一致。
- **与铁律/既有能力兼容性**：不触碰 SSE 四条铁律（Markdown state / scheduleFlush 不取消 / 贴底门控 / autoScroll 双键全在别处）；不新增网关外写入（ViewportDispatchGateway 登记表不变）；与帽协议/单出口事务正交；反射依赖面不变（R-3 风险水平保持，LazyListReflectionTest 护栏仍在）。
- **TDD 切入点**（先红后绿）：
  1. ResolvePairedTargetTest：落点在可见窗内 → 精确 (fii, fiso, key)；落点越窗 → 返回「放弃」哨兵而**不是**裸 index 目标（钉死 R-1 不变量：**无 key 不 set**）。
  2. 跨帧记忆缝测试：模拟同一 flush 任务实例两次 onPreDraw——第一次 set(fii,fiso)，外部改读位后第二次应 yield + rebase（钉死 R-2 不变量：**显式意图 > 引擎配对**可触发）。
  3. 既有 PairingYieldTest/StreamingAnchorRuleTest/ReserveReleasePlanTest/LazyListReflectionTest 全量回归。

### 方案 B（中庸·key 源升级）——「落点 key 不依赖可见性」

- **内容**：在 A 的基础上，keyAt 的实现从 layoutInfo.visibleItemsInfo 升级为数据侧权威——LazyList item key 均由 turnKey 前缀派生（#440 槽位锚键先例 + 十三轮 part id 契约 PartIdContract），可在 ChatMessageList 层维护「index → key」投影（chatEntries 构建期已知全部 key），使任意 index（含可见窗外，如 EOF 铺开末段）都能解析 key 后回写。
- **影响面**：ChatMessageList（key 投影供给）+ ScrollCompensation（keyAt 注入）；比 A 多一条数据链。
- **风险**：中。需处理投影与 LazyColumn 实际 item 序的瞬时错位（banner/分页插入窗）——错位时 key 错配比无 key 更糟，故必须保留 A 的熔断兜底 + key 校验（回写前用 visibleItemsInfo 交叉验证一次：目标 key ∈ 可见窗 key 集或目标 item 经 key 反查 index 与换算一致）。
- **兼容性**：同 A；额外依赖 chatEntries 键稳定性（#452 修复后 remember 键恒等问题已修，条目集发射时机有保证；turnKey 槽位键是 #440 已验证资产）。
- **TDD 切入点**：key 投影纯函数测试（index→key 全量解析、越界返回 null、插入平移正确性）；交叉验证守卫用例（投影 key 与可见窗矛盾时放弃回写）。

### 方案 C（激进·配对定位子系统重写）——「退役 requestPosition 反射族，配对改为 item 内自消化」

- **内容**：视口配对的物理实现从「LazyList scrollPosition 待定位」整体迁出：流式项增长全部留在 item 内部消化（帽 clip 已保证增长当帧不可见），锚内配对改为**流式 item 自身的 place 偏移**（StreamingGrowNode/帽层内偏移补偿，视口 scrollPosition 不动），列表级滚动 set 仅保留给显式意图族（GUARD/跳转/FAB/snapToBottom）。lastKnownKey 问题类别被构造性消灭（引擎不再写 scrollPosition），反射三件套 + 键回写全部退役，LazyListReflectionTest 转型为「反射已退役」断言。
- **影响面**：ScrollCompensation 核心重写、ChatMessageList 挂载点、PreRenderCoordinator 契约扩展（item 内偏移也需拒绘/原子语义）；等价于把 #435 引擎的「锚即意图」从列表滚动域搬进 item 布局域。
- **风险**：高。贴底物理跟随、读历史免派发、锚内 +Δ 三构型需全部重验证（十八~二十世轮三姿态清零基线会全部重置）；与 #445 深水区（双容器固化解 / R2 测量增量化 / plan 就绪节流）耦合度高，宜合流设计而非单独开工；reverseLayout 语义下 item 内偏移对「视口钉死公式 scrollPos−ΔH=S₀」（用户数学模型定案，2026-09-25-437...md:58）的等价性需数学论证 + 像素级验证协议。
- **兼容性**：长期看消除 R-3（反射退役）并简化五机制交互矩阵（二十五世轮架构审查 A5「五机制交互矩阵不可穷举」指控的根治方向）；短期违背「小步迁移」节奏，且 iron-laws 中「引擎配对走 PreRenderCoordinator flush 单点」的现行契约文本需重写。
- **TDD 切入点**：先写「视口钉死公式」决策表（三构型 × 增长方向 × item 内/外增长的期望像素轨迹，对齐 StreamingAnchorRuleTest 486 格决策表风格），红→实现→绿；真机三姿态协议（浅/中/深滑 + 回合末）复刻 2026-09-26 十八~二十世轮验收矩阵。

### 方案对比速查

| 维度 | A 补洞+可测化 | B key 源升级 | C 子系统重写 |
|---|---|---|---|
| 根因覆盖 | R-1 熔断 + R-2 修复 + 测试债 | A + 落点窗外场景 | R-1/R-2/R-3 构造性消灭 |
| 影响面 | 单文件 + 测试 | +ChatMessageList 数据链 | 引擎核心 + 契约重写 |
| 风险 | 低 | 中（key 错配需守卫） | 高（三构型基线全重置） |
| 工作量 | M | M+（+2~3 天） | L（独立设计批） |
| 铁律兼容 | 零冲突 | 零冲突（多一条依赖） | 需重写铁律文本 |
| 前置条件 | 无 | #452/A 方案先行 | #444 定罪 + 与 #445 合流裁决 |

### 方案间关系

A 是 B 的子集、C 的过渡态；A 独立可交付且直接服务 #438②收尾；B 解决「EOF 铺开末段落点必在窗外」的场景；C 是 #445 深水区级别的重工程，只有当 #444 定罪结果显示反射族不可救或五机制矩阵继续恶化时才值得启动。

## 6. 建议

**推荐：方案 A 先行（独立交付），视 #444 定罪结果决定是否追加 B；C 登记为与 #445 深水区合流的长期选项不动。**

理由：
1. ①已消除「单帧巨额 set」的主流量（append max→200），②的残余是低频边角（锚内阅读 + 插入/突发复合条件）——补洞 + 可测化的性价比远高于重写；
2. A 同时修复 R-2（死接线）——这是本轮调研的新增发现，它使 cb733d80 的「位置神圣」防御失效，是 #444 嫌疑 (a) 的直接对应物，修复成本近乎为零（变量声明位置移动 + 一条测试）；
3. ②与 #444 必须联合定罪（同窗口取证），A 落地后 DEBUG 打点（key=null 计数 + yield 触发计数）恰好构成定罪仪器——修复即取证；
4. 铁律兼容零冲突、影响面单文件、有清晰 TDD 钉子——符合「先写测试钉住不变量」的仓库纪律与 verification.md 的证据链要求。

**预估工作量**：A = **M**（纯函数提取 + 熔断语义 + 记忆缝修复 + 新测试 3-4 例 + 全量回归 + 真机锚内突发构型复验一轮）；B = M+（A 之上 +2~3 天，含 key 投影供给与交叉验证）；C = L（重工程，单独设计批）。

**验证要求**（按 docs/verification.md 框架）：单测（新钉不变量 + 全量回归）→ 真机锚内突发构型（发送→10s→上滑入流式 item 阅读位→停顿冲刷/二次消息触发突发）观察 VTRACE/SGR-435 release 行 key 回写与零 LEAP → V6 用户观感（与 #444 复验合并采集：fling 下滑方向 + 时刻）。

## 7. 引用清单

**backlog**：#438 卡片 backlog.md:87-91 · #437 backlog.md:93-94 · #445 backlog.md:269-274 · #444 backlog.md:276-278 · #443 backlog.md:280-281

**journal**：2026-09-25-437-streaming-md-stable-reveal.md:87-97（R9 双残差）· :114-118（817607b4）· :431-451（R1/R1-A2）· :643-660（终审 Critical）· :666-679（yield 防御）· :815-839（fling 未复现+勘误）· 2026-09-27-v2.md:31-52（①落地）· :96-113（#445 结论修正）· 2026-09-27-446-tearing-root-fix.md:22,37（残余族划界）

**research**：sse-scroll-stability-iron-laws.md（铁律权威，AGENTS.md 引用）；433-pipeline-map 描述的 COMP/PreRenderShiftChannel 架构已退役（参考需谨慎）

**代码**（当前 HEAD）：ScrollCompensation.kt:60-99（配对谓词）· :108-117（yield 纯函数）· :120-190（账本）· :264-350（帽）· :362-368（flush 任务 + 局部变量声明）· :466-475（yield 接线）· :492-534（目标换算 + 单出口 set + 拒绘）· :561-591（探针）· :637-664（反射执行器 + 键回写 :645-653）· ChatMessageList.kt:677-683（注册）· PreRenderCoordinator.kt:16-18,126-136（每帧调用模型）· StreamingMarkdownPilot.kt:106-112,137-171,197-219（①）· SafePrefixGate.kt:44-54,179（①）· HeldTailAging.kt:91-94（现行帽参数）· LazyListReflectionTest.kt:32-56 · PairingYieldTest.kt · ViewportDispatchGateway.kt:14-27 · JumpNavigationController.kt:294-303

**commits**：8596515b（量子化+全域配对）· b54650c3（超龄量子化）· 817607b4（键保持）· d28360d8（R1-A2 单出口）· a7daa1b0（终审 P1 拒绘修复）· cb733d80（yield 防御）· 20c79a8e（#438① 壁钟限速）

**关联卡片注记**：#438② ↔ #444（联合定罪窗口，本报告 §4.2 R-4；A 方案落地后共用取证仪器）；#438① ↔ #445（①的下游观感=完结窗步进节奏，参数可调，用户已验收「小跳一下」记录在案）；#443（gate 粒度扩展会改变 release 尺寸分布，间接影响②触发面，实施时对齐）；#446 残余族②③与本卡交界但已划界不扩大；#440（槽位锚键 turnKey 是 B 方案 key 投影的先例资产）；#452（chatEntries remember 键修复是 B 方案数据链前提）。
