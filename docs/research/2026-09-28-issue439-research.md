# #439 调研：流式期重组隔离——entries 签名缓存与子卡 skippability 恢复

> **一句话结论**：#439 的四个修复位在 #437 goal 二期（2026-09-26~27）已被蚕食大半——
> B3 步1（ChatEntry 身份编码）、B3 步2（displayItems 差量承载）、L1 发射隔离、#452/#450
> 两个签名缓存回归修复相继落地，「全量重组风暴」的实测主体（524 次/35s）已消解；
> 卡片剩余的**真实未竟项**是「无修复后收口证据 + 三个残留重组向量未审计」
> （renderableTurns 实例翻新、feedbackFor 裸 lambda、item 渲染体直读 turnGroups），
> 建议先复测取证再决定是否做结构签名或模块重构（推荐 S 级审计收口先行）。

- 调研日期：2026-09-28
- 方法：只读调研（backlog + journal + 研究档案 + 源码核验 + git 考古），零代码改动
- 调研人：深度调研子代理（委派自 #439 预研）

---

## 1. 问题陈述

### 1.1 用户可感知症状（登记时点，2026-09-26）

流式输出期间列表整体重组开销巨大：

- 真机 35 秒内同一 id 气泡重组 **524 次**（≈14-15 次/s，与流式批节奏同构）；
- 流式 turn 与**相邻注入卡条目**全部参与重组（非仅变化条目）；
- 回合结束后 append=0 时重组仍维持 24-34 次/s（后台会话噪音路径）。

证据：backlog.md:182-184（卡片原文）；docs/journal/2026-09-25-437-streaming-md-stable-reveal.md:216（日志指纹定罪）。

### 1.2 关键定性：性能债，非闪烁源

渲染像素幂等（前缀差分 append、stable 单调、无 Loading↔Success 翻转）——
**闪烁不在渲染层**（journal:217），十五轮明确定性「L3 重组隔离卡（P2）——性能债非闪烁源」（journal:229）。

它恶化的是：

- 流式期滑动 p90（当时 24-46ms、janky 36-47%，journal:316-317）；
- GC 分配压力（派生计算全量重算族，铁律 8，sse-scroll-stability-iron-laws.md:134-150）;
- 主线程每 100ms 的固定重组税（ChatMessageList 2850 行组合体重跑，二十五世轮定罪）。

### 1.3 触发条件

- SSE 流式输出期。引擎 cadence 100ms/批（ScrollCompensation.kt:78 `STREAM_FLUSH_INTERVAL_MS = 100L`；
  MessageEventHandler.kt:78-81 常量归引擎域、:325 delay 消费）；单次 flush = 1 次 StateFlow 更新 = 1 次重组
  （sse-scroll-stability-iron-laws.md:13-17）。
- 列表含多个可见条目（相邻注入卡参与重组的前提）。
- 后台会话同时写库时症状叠加放大（十五轮 L1，§2.1）。

## 2. 已知历史

### 2.1 取证与定罪（#437 验收十五轮，2026-09-26；本卡随 42df44ad 登记）

四路子代理并行分析（journal:210-218）定罪链：

1. **UI 失效链（本卡直接来源，journal:215）**：
   `chatEntries` remember 以 **displayItems 实例**为键 → 每批全量重建 entries →
   item lambda 全体重执行；叠加 lambda 内直读每批必变状态 + 未 memo 回调，
   击穿 renderableTurns 实例缓存。「修复位置已登记卡」即 #439。
2. **日志指纹（journal:216）**：SGB ENTRIES 每秒次数 == MDPilot append 每秒次数（逐秒恒等）；
   93% append 为 1-5 字符；同 id 气泡 35s 重组 524 次；回合结束后 append=0 而重组仍 24-34/s。
3. **渲染幂等（journal:217）**：闪烁不在渲染层——本卡从 P1 闪烁族剥离、定性性能债的依据。
4. **L1 发射隔离（同轮根修，42df44ad，journal:221）**：
   `MessageListState.partsByMessageId` 原样携带全局 parts 映射（`getAllPartsMap()`=裸 eventDispatcher.parts，
   无过滤无 distinct）→ 后台会话流式落库 → 状态结构不等 → StateFlow equals 去重被击穿 →
   可见会话以**全局写库速率**整体发射重组。根修=收窄为本会话消息稀疏投影。
   真机验证：20s 空闲窗口 InjCard=0、ENTRIES=0（风暴期 12-17/s → 归零量级，journal:226）。
   **「回合结束后仍 24-34/s」这一半症状已由 L1 关闭**（高置信）。

### 2.2 #437 goal 二期对本卡修复位的蚕食（本卡登记之后、尚未回收核对）

时间线（均为 2026-09-26~27，journal 二十六~五十一世轮）：

| 轮次 | 交付 | 与 #439 的关系 |
|---|---|---|
| 二十四世轮 | 归因取证：证伪 chatEntries 重建成本本身（buildChatEntries 0-1ms, n=77, journal:325）；发现 messageState 传参旁路（journal:330） | 把矛头从「重建贵」转向「重建引发的全 item 失效」 |
| 二十四世轮·终 | messageState 纳入滚动期冻结 + JankHold 默认开（journal:363-388） | 流式滑动卡顿缓解（与本卡互补） |
| 二十五世轮 | 双轴架构深审定罪三条（journal:392-429）：①帽协议全量档测量；②**100ms 周期全链重组未收口**（ChatMessageList ≥6 个每 flush 变化的 collect 点 + displayItems 参数链使 2825 行组合体每 100ms 重跑，journal:399-406）；③每帧常驻微成本长尾 | 产出 R1-R5 路线图；R4=B3 重组链收口 = 本卡修复位②③的正式载体 |
| 二十八世轮 | **B3 步1**（commit b41a0495）：ChatEntry.Turn 身份字段构建时编码；items lambda 消除 displayItems/turnGroups/streamingMsgId 三项捕获（journal:459-474） | **= 本卡修复位②主体 + ③身份层** |
| 三十三世轮 | **B3 步2**：diffDisplayItemsInto 纯函数 + SnapshotStateList 接线（journal:573-583；DisplayItemsDiff.kt:9-19；ChatScreen.kt:994）——get(i) index 级快照依赖，set(i) 只失效读该槽的 item；「重组收敛到流式 item 本体」 | **= 本卡修复位①的机制替代**（实例键→槽级失效） |
| 二十九/三十二/三十三世轮 | 量化：滑动 p50 8→6ms、p90 46→17→19ms、janky 47.3%→22.7%、贴底 p50 18→5-6ms、A3 贴底零泄漏（journal:479-498、:553-589） | 风暴消解的间接实证；残余 p90 归因 R2 测量增量化（journal:566-567、:784） |
| 三十六世轮 | R4 以「步1+步2+探针注入化+单出口」四项实质完成收口，深拆判非必要（journal:626-636） | 本卡修复位的交付声明 |
| 三十七世轮 | 终审：Critical 为拒绘方向反转（已修勘误），未再点名全量重组（journal:640-664） | 无本卡域新问题 |

### 2.3 两个签名缓存前车之鉴（本卡方案①的直接风险 precedent）

**#450（commit 8bc1ba32，2026-09-27）**——turnGroups/turnAnchors 签名缓存原用 **id 序列签名**
（messagesSignature，「内容变化不改变签名」），对 completed 转换不敏感 → turn 完结时复用
stale ChatMessage 引用 → isStreamingTurn 卡滞 → 统计栏常驻「正在流式输出」+ 计时不停
（journal 2026-09-27-v2.md:73-87）。

- 根修：换**生命周期签名**（id 序列 + 每消息 completed 位；MessageFingerprints.kt:34；
  turnGroups/turnAnchors 缓存失效换用，ChatMessageList.kt:288、:301）。
- 真机验证：修复前 BANNER=1（卡滞帧 completed=值+isStreaming=true 并存）；修复后 BANNER=0（journal:91-93）。
- **教训**：签名必须覆盖派生值消费的全部语义依赖——#437 判据放宽（isStreamingMsg 改读组内
  completed 字段，ChatMessageList.kt:1719-1720）后旧签名沉默失效；注释断言过时是漏洞入口（journal:82）。

**#452（commit da0300d7，2026-09-27）**——B3 步2 把 displayItems 变为 SnapshotStateList 后，
chatEntries remember 的 displayItems **实例键自反恒等**（同一实例 equals 自身恒真，key 形同虚设）；
turnGroups/turnAnchors 是 Map 值比较而 user 消息不入 turn 组 → 纯 user 增量不重建条目集 →
列表空白至首个 assistant 事件（免费/远程服务首事件 3-90s = 主诉窗口）（journal:204-216）。

- 根修：实例引用换 `displayItems.size`（快照读建立失效依赖 + 值比较），防回归双测入库（journal:216）。
- 真机双路径复验 PASS（journal:222-227）。
- **教训**：**B3 步2 自己孵出的回归**——结构键必须同时满足「快照可依赖 + 值可比较」。

## 3. 现状代码走读

### 3.0 卡片行号勘误（先校准坐标系）

卡片修复位行号基于登记时点（42df44ad）的 ChatMessageList.kt（2685 行）：

- 「ChatMessageList.kt:741」→ 当时 chatEntries 定义（已 git show 核实旧内容为
  `remember(displayItems, turnGroups, streamingMsgId, chunkPlans, recentStreamedTurnKeys, segmentPlans)`）；
- 「MarkdownChunking.kt:291-313」→ buildChatEntries（**今 MarkdownChunking.kt:296-310，未漂移**）；
- 「:2448-2487 item lambda」与「:1361-1375/:1772 回调」→ **当时就在 ChatMessageList.kt**
  （卡片统标 MarkdownChunking.kt 系笔误；已 git show 逐段核实：2448 起=onCopy 块、2452 起=item lambda、
  1772 起=onForkFromTurn/onDeleteMessage 块）。今对应：item lambda :2595-2649；回调块
  :1474-1488 / :1535-1549 / :1578-1603 / :1910-1934。

### 3.1 流式批 → 重组链路（当前实现，自底向上）

1. **数据层**：SSE delta → 100ms 批处理（MessageEventHandler.kt:325、:339-353，DEBUG 可调
   `debug.ocbeacon.streamflush`）→ 单次 StateFlow 发射（L1 后仅本会话稀疏投影）。
2. **ChatMessageList body 层**（2850 行组合体）：rawMessages/messageState 每 flush 更新 →
   body 重组（二十五世轮「每 100ms 重跑」仍成立，属已知税，见 §4.2 R-4）。派生层三缓存就位：
   - turnGroups / turnAnchors **生命周期签名缓存**（ChatMessageList.kt:285-296、:298-309；
     sig 不变复用 Map 实例，completed 变化必重建拿新引用）；
   - renderableTurns **内容指纹缓存**（:437-471）：指纹命中且非流式消息复用 RenderableTurn 实例；
     **流式消息强制排除缓存**（:444 `msg.message.id != streamingId`）——流式 turn 每 flush 必重算
     （必要成本：内容真的变了）；
   - streamingMsgId 值比较 remember（:422-426，只看消息自身 completed，铁律 5 语义）。
3. **entries 层**（ChatMessageList.kt:768-779，#452 修复后形态）：
   `chatEntries = remember(displayItems.size, turnGroups, turnAnchors, streamingMsgId, chunkPlans,
   recentStreamedTurnKeys, segmentPlans)`。
   **稳态流式中段七个键全部稳定**——streamingMsgId 同 id、size 不变、turnGroups 实例经签名缓存复用、
   chunk/segment plan 与 recentStreamedTurnKeys 对流式 turn 排除（MarkdownChunking.kt:383、:406；
   recentStreamedTurnKeys 由 RenderSupplyCoordinator.kt:127-130 noteStreamTurnEnded 在**流结束时刻**添加，
   非 mid-stream）。**mid-stream 已不重建 entries**——修复位①的字面目标已被 #452+B3 组合替代实现。
   重建只发生在语义必要点：turn 边界（streamingMsgId 翻转 / 生命周期签名变）、分页插拔、plan 提交。
4. **item 层**：itemsIndexed（:2595-2649）——key=entry.key；contentType 按 entry 类型分发（:2598-2607，
   Turn 按 entry.isUser 分流）；`entryStreaming = entry is ChatEntry.Turn && entry.isStreaming`（:2626，
   B3 步1 语义，注释 :2623-2625 即原始根因陈述）。displayItems 为 SnapshotStateList
   （ChatScreen.kt:994 差量写入）——条目渲染体 `displayItems[entry.displayIndex]`
   （:1402/:1505/:1564/:1709）为 **index 级快照读**：流式期长度不变、仅尾槽写 → 只有流式 item 重组。
5. **子卡层**：流式 Turn 走 MessageCard（:1894-1936），收 `renderableTurns[displayItemIndex]`
   （:1899，每 flush 新实例）+ msg 新实例 + 回调族；注入卡 InjectionCard 在 Turn 分支内
   （:2071 system 注入档、:2148 DSH/V2 注入档，参数均为值类型 + 共享 expandedStates 状态对象）。

### 3.2 四个修复位的当前状态对照表

| 卡片修复位（backlog.md:184） | 现状 | 证据（文件:行号） |
|---|---|---|
| ① chatEntries 键改结构签名（仿 turnGroups sig-cache） | **未按字面做，被 #452+B3+生命周期签名组合替代**：值比较键集，稳态流式中段零重建 | ChatMessageList.kt:768-779；journal v2:216 |
| ② ChatEntry 预载 msg/streaming/key | **isUser/isStreaming 已编码（B3 步1，commit b41a0495）**；msg 未预载——改为 item 内槽读（槽级快照依赖，设计上优于预载：条目结构不随消息实例翻新） | MarkdownChunking.kt:179-190、:358-360、:369、:460；ChatEntryIdentityTest.kt:33-64 |
| ③ item lambda 消除 displayItems/turnGroups 直读 | **身份层已消除**（contentType/entryStreaming 读 entry 字段）；内容层仍读 displayItems[i]（槽级快照，有意机制）与 **turnGroups/turnAnchors（:1711、:1719-1720，残留捕获）** | ChatMessageList.kt:2598-2607、:2623-2626、:1709-1720 |
| ④ 回调 lambda remember 化 | **未显式做**：onCopy/onForkFromTurn/onDeleteMessage/onRevert/onCopyText 仍为渲染体内联 lambda；**feedbackFor 每调用产裸 lambda**（:351-357，非 composable 局部函数内创建，逃逸编译器 lambda memoization）。项目 Kotlin 2.4.10 + compose plugin（build.gradle.kts:4）处于 strong skipping 默认时代，**composable 上下文内联 lambda 可能已被自动 memoize——未以编译器 metrics 复核** | ChatMessageList.kt:1474-1488、:1535-1549、:1578-1603、:1910-1934、:351-357 |

## 4. 根因分析

### 4.1 已定罪且已修复的根因链（历史取证 ↔ 现行代码位置）

1. **displayItems 实例键 → entries 每批全量重建 → 全 item 重组**（journal:215）
   → 已由 B3 步2（SnapshotStateList 差量）+ #452（size 键）拆解：
   DisplayItemsDiff.kt:9-19、ChatScreen.kt:994、ChatMessageList.kt:768。
2. **item lambda 捕获每 flush 新实例的 displayItems/turnGroups/streamingMsgId**（journal:464-468）
   → 已由 B3 步1 身份编码消除：MarkdownChunking.kt:185-189、ChatMessageList.kt:2623-2626。
3. **全局 parts 映射击穿 StateFlow equals 去重**（journal:221，「回合结束后仍重组」）
   → 已由 L1 稀疏投影根修（journal:226 真机归零）。
4. **签名缓存 stale 引用**（#450）→ 已换生命周期签名（MessageFingerprints.kt:34、
   ChatMessageList.kt:288/:301）。

以上四条：证据等级**高**（真机探针定罪 + 防回归测试 + 修复后量化数据齐全）。

### 4.2 尚未解决的根因链（本卡残留部分）

**R-1｜流式 item 子树全量重组——「子卡 skippability」未证实恢复**
（机制推断置信度：**中**；现状：**待验证**）

- 机制：流式 turn 的 RenderableTurn 每 flush 重算新实例（ChatMessageList.kt:444 缓存豁免）→
  MessageCard 必然重组（这部分**必要**）；但**其内部未变化的子卡**（同 turn 内工具卡/思考块/注入卡）
  能否跳过，取决于三个前提：(a) strong skipping 实际生效；(b) 子卡参数实例相等——数据层 part 实例
  复用已由铁律 8 安全前提保证（sse-scroll-stability-iron-laws.md:146：EventDispatcher 只替换变化消息、
  ToolProgressOutputInjector 无匹配返回原引用）；(c) 不被裸 lambda（feedbackFor :351-357 产物）击穿
  === 相等性。
- 缺口：**登记时点的 524 次计数从未在修复后复测**。「重组收敛到流式 item 本体」（journal:582）
  是机制论证 + 滑动指标间接佐证，**无逐 item 重组计数证据**。
- 验证方法：复刻十五轮「同 id 气泡 35s 重组计数」探针（扩展到对非流式相邻条目 key 亦计数），
  流式窗口 35s 对比；或 Compose 编译器 metrics（reportsDestination + stability/optimization 报告）
  核对 ChatMessageList 内 lambda memoization 与 MessageCard 子树 skippability。

**R-2｜renderableTurns 每 flush 新 List 实例——潜在全 item 失效向量**（置信度：**低-中，待验证**）

- 机制：renderableTurns 的 remember 键含 rawMessages（:438），每 flush 重算并**返回新 List 引用**
  （:467-469 注释明言「不能复用上一轮列表引用」——37d9a6ac 崩溃教训，结构性安全优先）。
  该 List 被 renderTranscriptEntry（:1397 起）→ itemsIndexed 内容块捕获。若内容块/item content
  lambda 因捕获实例翻新而每 flush 换实例，则「新 lambda 实例 → 可见 item 失效」的老向量理论上仍在。
- 反证：B3 步2 后滑动指标大幅改善（p50 6ms）说明**实际影响有限或已被某种 memoization 短路**；
  但 journal 从未正面回答「renderableTurns 实例翻新为何不重新点燃全 item 失效」。
- 验证方法：同 R-1 探针（非流式条目重组计数若 ≈flush 速率即坐实）；或编译器 metrics 查
  itemsIndexed 内容块的 memoization 键构成。

**R-3｜item 渲染体直读 turnGroups/turnAnchors**（:1711 chatEntryKey、:1719-1720 isStreamingMsg）

- turnGroups 实例经签名缓存 mid-stream 稳定，故**稳态无害**；turn 边界实例更替时是全 item 捕获
  翻新点（与 R-2 同族）。修复位③的字面残留。
- 置信度：机制**高**、实际影响**低-中（待验证）**。

**R-4｜结构性背景税：ChatMessageList body 级重跑**

- 2850 行组合体每 100ms 整体重跑（二十五世轮 :399-406 定罪，**未根修**）——B3 只收口了 item 级失效，
  body 级重跑仍在（每 flush 全部 remember 键比较、derivedStateOf 重建、transcriptCardPlan 键评估等）。
- 这是本卡「性能债」的另一半本体，也是终审挂账「flush 深拆」（journal:657-658）与 #445 深水区的邻域。
  置信度：**高**（终审双轴审查背书）。

### 4.3 与其他卡片的关联（含用户提示核实）

- **#437**（backlog.md:93-94）：父弧——本卡修复位②③主体由其 goal 批次 B3 步1/步2 落地；
  本卡即十五轮「L3 重组隔离」挂账（journal:229）的正式载体。**收口前先复测**，避免与 #437
  尚未用户验收的堆叠改动混淆归因。
- **#445**（backlog.md:269-271）：R2 测量增量化深水区（双容器/append 成本 O(总内容)）——
  与 #439 同属流式期性能债的**互补半边**（#439=重组面，#445=测量面）；journal:566-567 明确
  残余 p90 属 R2 域。若 #439 复测显示重组面已净，流式性能主战场整体移交 #445。
- **#438**（backlog.md:87-91）：流式突发路径（gate 限速/配对 set 保 key）——同管线不同层
  （滚动配对面），互不冲突。
- **#444**（backlog.md:276-278）：fling 下滑跳变复发——滚动域回归，非重组域；共用 VTRACE/探针资产。
- **#450/#452**：已结案的签名缓存/remember 键回归——本卡方案①的**强制设计约束来源**（§2.3）。
- **#426**（backlog.md:283-284）：buildChatEntries 内 StepGroupHead/Body 发射机死代码
  （MarkdownChunking.kt:378-379）——若走 §5 方案 C 应顺带清理。
  **提示核实**：用户背景提示中「四阶段（P1切片→P2账本→P3窗口→P4可选表级）；收编 #424/#425 动机；
  #426 死代码随切片器转正清理」经核实属 **#427** 卡片备注（backlog.md:186-192），与 #439 无从属关系，
  仅在重构时共享清理窗口（#424 后台解析预取池 backlog.md:198-200 亦属 #427 族）。
- **#441**（backlog.md:83-85）：SSE 长连接随机断连——流式测试通道毒害源，复测窗口需规避。
- **理论底座**：docs/research/2026-09-24-compose-background-compute-feasibility.md——Compose 组合/测量
  架构性绑定主线程、无后台通道（:17、:24）→ 流式期主线程减负唯一路径=虚拟化/缓存/跳过（:102-106），
  #439 的「skippability 恢复」正是该结论在重组维度的落地。

## 5. 修复方案空间

### 方案 A｜审计收口型：先复测定界，再点射修复（保守）

**内容**：

1. 扩一个「逐 item 重组计数器」探针（[DEBUG-jk] 已在 :769-794 常驻，加 per-key 重组计数；
   亦可用 `.onGloballyPositioned`/RecomposeScope 计数方案），复刻十五轮 35s 窗口方法学，
   取修复后基线（流式 item / 相邻非流式 item / 注入卡 item 三类分别计数）；
2. 按数据分界：非流式条目计数 ≈0 → 剩余工作收缩为 R-1 的 metrics 复核 → 关卡或缩卡为
   「子卡 skippability 证据补全」；计数 ≈flush 速率 → 坐实 R-2/R-3，进点射修复；
3. 点射项（按坐实顺序）：
   - feedbackFor 返回值 remember 化（body 级 `remember(messageFeedbackMap) { ... }` 包裹，
     或收敛进稳定 holder 对象）——消灭逃逸 memoization 的裸 lambda；
   - turnGroups 直读改经构建时编码（延续 B3 步1 思路：把 chatEntryKey 判定或 isStreaming
     预判入 ChatEntry / remember 键），消除 :1711/:1719 捕获。

**影响面**：小——探针 DEBUG-only；点射改动局部（两个函数级）。
**风险**：低；唯一语义坑=feedbackFor remember 化不得冻结 messageFeedbackMap 变化（map 实例变须产新 lambda）。
**与 SSE 铁律兼容性**：零触碰滚动/补偿路径（铁律 1-5 不涉）；不违反铁律 6/8（指纹/签名语义不变）。
**TDD 切入点**：
- 先写探针断言脚本「流式 35s 窗口内非流式条目重组计数 == 0」（复用 scripts/stream-flicker-test.sh
  骨架 + logcat 判读）——这是本卡最核心的不变量；
- feedbackFor 等价性单测：同 map 实例 → 同 lambda 实例；map 变 → 新实例。

### 方案 B｜结构签名型：chatEntries 签名缓存（卡片原始诉求的加固版）

**内容**：仿 turnGroupsSigRef 双槽模式（ChatMessageList.kt:285-296）为 chatEntries 建
`chatEntriesStructureSignature`——对 entries 结构做指纹（每 displayItem：消息 id + isUser +
isStreaming + 生效 plan id + chunk/segment 计数），签名不变复用上次 ChatEntries 实例。

**影响面**：ChatMessageList.kt:768 一点 + 新签名函数（宜落 MessageFingerprints.kt 侧）。
**风险**：**中-高**——两枚前车之鉴直接命中：
- #450 教训 → 签名漏字段=沉默 stale（isStreaming 必须入签名；plan id/计数防分片结构漂移）；
- #452 教训 → 键必须「快照读 + 值比较」（签名函数须实际读 SnapshotStateList 建立依赖）。
收益不确定：§3.1 已论证 mid-stream 键集稳定、entries 不重建，**本方案只优化 turn 边界/分页时刻的
重建**（0-1ms 级，journal:325 曾证伪其成本）——性价比存疑，且新增一层签名=新增一处 #450 式漂移风险面。
**与铁律兼容性**：与铁律 8 三层缓存同构（兼容），但属第四层缓存，维护面 +1。
**TDD 切入点**：
- 签名完备性性质测试（仿 #450 lifecycle signature 测试模式，journal:88-89）：对「任何使
  buildChatEntries 输出变化的输入突变」穷举矩阵（消息增删/id 换/isStreaming 翻转/plan 提交/
  recentKeys 增删/分页），断言签名必变；
- 「签名不变 ⇒ ChatEntries 实例复用且键序不变」（防 #452 复发）。

### 方案 C｜激进：消息区模块重构——renderTranscriptEntry 拆出 + 窄接口化（重写级）

**内容**：把 ChatMessageList 的消息区（renderTranscriptEntry + itemsIndexed 块 + 全部条目分支，
约 1300 行）拆为独立文件（如 ChatEntriesList.kt），对外只收一个 **@Immutable 的 ChatListUiState**
（entries + 槽访问器 + remember 化稳定回调 holder 对象）；item 内容 lambda 只捕获 holder + entry；
renderableTurns 经 state 对象按需读取（R-2 的构造性消除——item 不再直接捕获翻新 List）；
回调全部收敛进 holder 类（实例跨 flush 稳定，R-1(c)/④ 的构造性消除）。顺带：清理 #426 死代码分支
（MarkdownChunking.kt:378-379）；把 49 处 DEBUG 日志点探针注入化收尾（二十五世轮 R4 遗产，journal:405）。

**影响面**：大——触及列表渲染核心与承重索引体系（entryDisplayIndex/displayEntryStart，
MarkdownChunking.kt:254-264「跳转/锚点/可见项反查零波及——承重索引体系的保守性决策」）。
ChatScreen.kt 编辑协议虽不直接适用（主战场 ChatMessageList.kt），但同量级文件建议比照协议纪律
（read → edit → compileDevDebugKotlin → commit 循环，禁止跨 agent 并行编辑）。
**风险**：高——该文件承载铁律 3/4/5 挂载点（isStreamingMsg 修饰符 :1765、autoScroll 双键语义、
StreamingGrowLedger 桥）、#420 补偿、#378 卡内嵌（extras.before/after）、跳转状态机注入
（CompositionLocalProvider :2632-2636）——任一错位即重引入视口跳动/闪烁；且与终审挂账的
「flush 深拆」「2850 行架构债」天然同窗，**单独立项会重复动同一文件**。
**与铁律兼容性**：目标态与铁律全部同向（更强 skippability=更稳定的高度/滚动语义）；**过程风险**高，
需 V1-V6 全量验证（docs/verification.md）+ 真机三姿态回归（贴底/浅滑/深读）。
**TDD 切入点**：
- 先写**特征化测试**（characterization）：buildChatEntries 输出金样矩阵（扩 ChatEntryIdentityTest /
  ChunkEntryOrderTest / TurnSegmentTest）；「条目键序列在既定输入族下不变」性质测试；
- holder 回调实例稳定性测试（跨 flush 同实例）；
- 拆分期间每步全量回归（3500+ 用例基线）+ 三姿态真机取证。

## 6. 建议

**推荐：方案 A 先行（工作量 S，0.5-1 批次），按复测结果决定 B/C 去留。**

理由：

1. **证据经济学**：登记时点症状（524 次/35s）出自修复前；修复后滑动 janky 减半、贴底 p50 达标
   均指向风暴已消——但按验证铁律（docs/verification.md，无新鲜验证证据不得声称完成），当前最缺的
   就是一份修复后逐 item 重组计数。先复测可能直接把关卡缩到「metrics 复核」级别，避免过度工程。
2. **方案 B 性价比存疑**：mid-stream 键集已稳定（§3.1），签名只优化语义必要重建点（turn 边界——
   结构真变了，重建不可省）；#450/#452 证明该域签名键漂移风险是实弹。**若复测坐实 R-2
   （renderableTurns 实例翻新仍击穿全 item），应优先修 R-2 本体（实例稳定化/捕获下收）而非再加签名层**。
3. **方案 C 不独立立项**：其真实价值在「2850 行架构债 + flush 深拆 + #426 清理」组合窗
   （L，2-4 批次）——建议挂到终审挂账 R4 深拆/架构批次统一裁决，本卡不单独触发重写。
4. 复测窗口注意 #441 断连毒害（journal:84-85）：用本机 v2 systemd 常驻服务（4096）+
   stream-flicker-test.sh 标准协议；构建/装机纪律照 AGENTS.md（单 flavor assemble、真机 adb reverse）。

预估工作量：**A = S**（探针扩展 0.5 天 + 复测分析 0.5 天 + 可选点射 0.5-1 天）；
**B = M**（签名设计 + 完备性测试矩阵 2-3 天）；**C = L**（2-4 批次，需独立 spec）。

## 7. 引用清单

**backlog**：
#439 卡片 backlog.md:182-184 · #437 :93-94 · #438 :87-91 · #441 :83-85 · #444 :276-278 ·
#445 :269-274 · #427（四阶段备注归属核实）:186-192 · #424 :198-200 · #425 :194-196 · #426 :283-284

**journal（docs/journal/2026-09-25-437-streaming-md-stable-reveal.md）**：
十五轮四路定罪 :210-230（UI 失效链 :215 / 524 指纹 :216 / 幂等 :217 / L1 根修 :221 / L1 验证 :226 /
L3 登记 :229）· 二十四世轮 :310-341（重建成本证伪 :325 / messageState 旁路 :330）·
cadence 落地 :343-361 · 二十五世轮架构定罪 :392-429 · B3 步1 :459-474 · 量化 :479-498 ·
B3 步2 :573-589 · R4 判定 :626-636 · 终审 :640-664 · Goal 终章 :765-793

**journal（docs/journal/2026-09-27-v2.md）**：
#450 取证 :54-69 · #450 根修 :71-99 · #452 取证 :125-130 · #452 定罪修复 :204-220 ·
#452 复验 :222-227 · 旁观察 :228

**研究档案**：
docs/research/sse-scroll-stability-iron-laws.md（管道 :13-17 / 铁律 6 指纹缓存 :92-109 /
铁律 8 签名缓存三层 :134-150 / 安全前提 :146）·
docs/research/2026-09-24-compose-background-compute-feasibility.md（Q1 :17 / 总回答 :24 /
落地建议 :102-106）· docs/research/433-streaming-pipeline-map.md（管线全图；COMP-MSG 时代版本，
阅读需对照 #435/#437 后状态）

**源码（现行号）**：
ChatMessageList.kt——turnGroups 签名缓存 :285-296 / turnAnchors :298-309 / feedbackFor :351-357 /
streamingMsgId :422-426 / renderableTurns 指纹缓存 :437-471 / chatEntries :768-779 /
renderTranscriptEntry :1397 起（displayItems 槽读 :1402 / renderableTurns 读 :1459 / 回调块
:1474-1488、:1535-1549、:1578-1603）/ Turn 分支 :1707-1720（isStreamingMsg :1719 / 修饰符挂载 :1765）/
MessageCard 参数 :1892-1936 / InjectionCard :2071、:2148 / itemsIndexed :2595-2649（entryStreaming :2626）。
MarkdownChunking.kt——ChatEntry :173-252（Turn 身份字段 :179-190）/ buildChatEntries :296-310
（isStreamingTurn :358-360 / Turn 发射 :369、:460 / 流式排除 :383、:406）/ ChatEntries 承重索引 :254-264。
DisplayItemsDiff.kt:9-19 · ChatScreen.kt:994 · RenderSupplyCoordinator.kt:127-130 ·
MessageFingerprints.kt:34、:53 · MessageEventHandler.kt:78-81、:325-353 · ScrollCompensation.kt:78

**测试**：ChatEntryIdentityTest.kt:12-64（B3 步1 锁定）· #452 防回归双测（journal v2:216 述及）

**git**：42df44ad（十五轮根修 + 本卡登记）· b41a0495（B3 步1）· 8bc1ba32（#450）· da0300d7（#452）
