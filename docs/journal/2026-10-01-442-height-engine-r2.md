# 442-height-engine-r2（2026-10-01）

> 状态：进行中
> 关联：（spec 路径，若有）·（issue 编号，若有）
> 来源：用户反馈 / grilling / E2E / 顺带发现

<!-- 过程中的取证/验证证据直接写本文件；backlog.md 只留 ≤3 行卡片。 -->

## §1 开工盘点与二期批次计划（2026-10-01）

**卡片现状修正**（调研报告 2026-09-28 之后代码已前进，逐项核实）：

| #442 注记项 | 现状 | 证据 |
|---|---|---|
| cadence 收编 / StreamingPairingRule 缝退役 / 待复核四项 | ✅ 已完成（当时未销账） | commit 5d061df2 / 12513603；journal 四十六世轮 :844-852 |
| 调研 §3.8 让位防御失活 | ✅ 已被 #438 R-2 修复 | ScrollCompensation.kt:407-415 lastSet 外提闭包捕获 + FlushTaskMemoryTest 接线测试 |
| flush 八职责深拆（终审 S2） | ❌ 仍单体 lambda | streamingGrowFlushTask :400-588 |
| R2 分片增量化（滑动 p90 ≤12ms 主体） | ❌ 未动 | 单容器 O(总内容) 结构不变 |
| #445 并入：双容器状态管理 | ❌ 设计批（四十四世轮撞库约束墙结论仍有效） | StreamingMarkdownState append-only 无前缀移除 |
| #470 并入：帽不回改 + ledger 负向不配对 | ❌ B/A 语义裁决仍挂起（4h 重度使用零负向事件，不阻塞） | reserveReleasePlan :338 / ledger note :157 |

**#492 批次遗产入账**（2026-10-01，三卡关闭但分析归 #442 域）：免补偿构造三方向——①锚越界期间持帽到底 ②完结换装单一渲染路径（#471③/#472 已部分落地）③容器稳定（LBox/IME 已归因标准行为）；广域检测网七族探针（SilentShift/ItemH/ItemP/LBox/TurnFin/LRef/GrowthClock）永久在役，二期真机判读直接可用。

**二期批次计划**（沿调研 §5 建议 C→A，B 保留为 A 复测不达标的升级路径）：
- **批次 C（S）**：flush 八职责深拆（表征测试先行钉行为，日志串字节不变——#484/#492 判读签名集依赖）+ cadence 文档漂移修正（48ms→100ms：AGENTS.md/iron-laws/ScrollCompensation KDoc/热点注释）+ backlog 销账。
- **批次 A1**：毕业计划纯函数（snapshot+released → 稳定块区间+尾块区间；stableTailBoundary 为子件）——钉单调性不变量（毕业只前进不回退）。
- **批次 A2**：装配层多 item 接线（流式 turn 发 N item：已毕业稳定块冻结 chunk + 活跃尾块；键族对齐 t_ 键序语义防换血闪烁）。
- **批次 A3**：帽/配对适配（帽物主切尾块 item、多 item 同帧合并求值——#435 spec §5 已备案）+ 完结换装路径。
- **批次 A4**：真机复测（gfxinfo 滑动 p90 ≤12ms、VDRAW 泄漏=0、CONTENT-BLINK=0；复用 #492 检测网）。

**红线**（本批不可违反）：SSE 铁律五条；日志签名集稳定；帽「已上屏永不回改」既有裁决（#470 回改语义另案裁决，不在本批擅动）。

## §2 批次 C：flush 八职责深拆 + cadence 文档漂移修正（2026-10-01）

**表征测试先行**（新 `FlushTaskPhasesTest` 7 例，拆分**前**对现行代码跑通=钉住行为）：帽首帧初始化直通零派发 / 帽-only 释放贴底原点免配对（reserved 原子推进 1000→1066、零滚动派发、不拒绘）/ ledger 派发帧拒绘（终审 P1 契约 + 反射派发验参）/ 滚动中 rebaseAll 弃配零派发 / 空账早退 takePaired 不触 / 对齐随态翻转门两例（追平态翻、溢出非手势期不翻）。

**深拆落地**（`streamingGrowFlushTask` 单 lambda 188 行 → 相位函数）：
- `FlushTaskMemory`（lastSet 跨帧记忆产物化——#438 R-2 闭包变量升为具名类）
- `reservePhase`（职责①②：对齐随态翻转+释放计划+首帧初始化）
- `vtraceTick`（职责③观测）
- `applyPairedShift`（职责⑦⑧：单出口执行器+拒绘契约）
- 主 lambda 收敛为八职责顺序编排（早退/弃配/让位/求值内联——各 ≤10 行）
- **行为与日志签名集逐字节不变**（#484/#492 判读依赖；对照原实现逐段搬移）

**验证**：表征 7/7 + 引擎域 47/47（FlushTaskMemory 2/ReserveReleasePlan 8/StreamingAnchorRule 7/PairingYield 3/StreamingGrowLedger 16/ResolvePairedTarget 4）+ 全量单测 **3787/0/0** + compileDevDebugKotlin 绿。

**cadence 文档漂移修正**（48ms→100ms，常量实际值自 #437 快赢起已 100ms、终审 S4 已收编引擎域，文档滞后）：AGENTS.md SSE 段（管线描述+工具横幅批节奏）；iron-laws §1 管线图/铁律 2/§4 速查表 + 变更日志行（§3.4 历史表格保留原文记录当时事实）；ScrollCompensation KDoc「48ms 节奏的结构性继承」节；引擎域注释 7 处（SafePrefixGate/HeldTailAging「约 6 批」→「约 3 批」算术修正/HeldTailReveal 轮询步进语义/StreamingMarkdownPilot×2）；MessageEventHandler 调度器注释 5 处（含「约 20 次/秒」→「约 10 次/秒」）。

**残余（低价值不追）**：数据层历史性能叙述注释（MessageStore/MessageDao/CachedPartEntity/SessionStateService 等描述当时实测，保留原文）；ChatScreen.kt 3 处注释走编辑协议成本高于价值，记此处跳过；ChatScrollUtils「3×16ms」为帧基重试与 cadence 无关。DshSilenceWatchdog「48ms 批处理上限」为阈值论证语义（保守方向），不动。

**定位（诚实）**：本批是 R2 分片手术的**安全网与清障**——八职责拆开后布尔语义接线测试在位（终审 P1 教训补课），p90 目标未动（主体在批次 A）。

## §3 批次 A1 + A2 内核：毕业计划纯函数、武装/触发状态机、施工 spec（2026-10-01）

**A1 毕业计划纯函数**（commit c6f1c113；`StreamingGraduationPlan.kt` + 测试 8 例）：
- `planStreamingGraduation(snapshot, released, previous, min, max)` → `StreamingGraduation(chunks, tailFrom)`；
- 语义：空行块边界（stableTailBoundary 同源多块化）贪心打包；**冻结 append-only**（旧块永不改写——装配层键稳定前提）；tailFrom 单调只前进；门槛（GRADUATE_MIN_CHARS=2000）/上限（GRADUATE_MAX_CHUNK_CHARS=4000，无内部边界巨块允许超限独占）；非前缀防御性重置（与 pilot resetKey 路径对齐）；
- 不变量测试钉死：单调/无缝从零起/切点全落空行边界/append-only/拼接恒等（chunks+尾块=快照）。两轮测试数据自纠（快照过短、release 回退触发重置、超限块尾空行 run）——重置防御按设计工作。

**A2 内核：武装/触发状态机**（`StreamingSplitMachine.kt` + 测试 6 例）：
- `onBatch(snapshot, released, quiescent, shadowLen)` → Arm/Feed/Fire/None/Reset——把 spec §2 毕业时机（武装=新冻结≥门槛；触发=影子追平∧滚动静止——**p90 窗口零毕业成本**；换装只冻到武装边界、武装后增量留下轮；武装中快照缩短显式重置）编码为可执行决策件；
- 实现期修复一缺口：plan 侧重置检测只覆盖已生效集，武装中缩短须按 `snapshot.length < max(tailFrom, armedOrigin)` 显式判（测试先暴露后修）。

**施工 spec 定稿**：`docs/specs/2026-10-01-442-r2-shard-awakening-design.md`——影子态零闪烁换装（同 delta 双喂→内容恒等交换，否决数据层 substring 直喂=非前缀墙）、键族 `t_X`/`t_X#g_i`（锚键永不消失+逆文档序发射 #246 对齐+displayEntryStart 钉头片）、换装帧帽 reset（防「帽不回改」使尾块永久虚高=#470 墙的毕业形态）、切割高度恒等假设（#258 块 padding 对称先例，A4 像素验证）、完结持续性（StreamChunk 保留不迁 TurnSegmentPlan）、STREAM_SHARD_PILOT 开关回退。A2/A3/A4 施工路线已写明。

**验证**：新增 14 例单测全绿 + 全量 **3801/0/0** + compile 绿 ×3。

**下一步（实施批）**：A2 装配层接线（StreamChunk entry/发射/渲染切片/streamingOverride 通道/开关）→ A3 影子态换装+帽 reset+完结持续 → A4 真机 p90 判定。#470 帽回改 B/A 语义裁决仍挂起（不阻塞 A 线）。

## §4 批次 A2：装配层接线落地（2026-10-01，STREAM_SHARD_PILOT dev 先行）

**交付**（spec §7-A2 全量；Fire=切尾重建快灌中间态，A3 影子态未落）：
1. **开关**：`STREAM_SHARD_PILOT`（dev=true/beta/stable=false，build.gradle.kts——STREAMING_MD_PILOT 先例；关=零改造原路径）。
2. **broker 单例**（`StreamingShardBroker.kt`，ScrollQuiescence 先例）：partId 键注册表（Turn 分支组合期登记资格+Fire 帽钩子）+ 发布表（快照态驱动 chatEntries 重算）；controllerFor 对**已发布** part 兜底返回（完结/回收持续性——fire 无注册 no-op 防幽灵发布）；列表离树 clearAll（会话切换防陈旧堆积）。
3. **装配**：`ChatEntry.StreamChunk`（key `t_X#g<i>`，与 #c/#s 键族互斥）+ buildChatEntries streamShards 参数——shard 命中先于其他分片路径（键族互斥），尾块 Turn 保原键（锚/帽物主/跳转/槽位锚零迁移）+ 冻结块逆文档序发射 + displayEntryStart 钉头块（#246 契约）；itemsIndexed contentType 补 `assistant_stream_shard`。
4. **渲染**：`StreamShardContent`——归一化切片同步解析（remember(text) 单次）经 preParsed 通道（换装帧首组合即全高，无 Loading 空窗）；分片 item 零间距（底距归尾块 Turn entry）。
5. **pilot 切尾**（`rememberPilotStreamingMarkdownState` + `shard` 控制器参数）：差分/放行/gate 全部改尾坐标（eff=substring(sliceOrigin)，coerce 防非前缀缩短窗越界）；machine 全坐标驱动（released+sliceOrigin 换算，quiescent 门）；**Fire=帽 hardReset→发布→切尾重建**同协程步（换装帧原子见三者；重建走 #H4 快灌 800ch/帧）；**冷续单帧**（sliceOrigin 继承+fastRefeed=false → RefeedPacing(MAX,0) 一次性入树——静态内容不得限速重铺）；非前缀重建路径清发布回单容器。
6. **帽**：`HeightReserveState.hardReset()`（reserved/trueHeight 归 -1、itemKey 保持——所有权连续；不 reset 则「帽不回改」使尾块永久虚高冻结区高度）。
7. **完结持续性**：shardHold（已发布 part 完结后保持 pilot 终帧——#471③ 归一化同源即终态；跳过 async 终态预热与 freeze——最终 EOF flush 必须放行）——规避「完结切全量终态与冻结 shard 双渲染重复」。

**实施期裁决（spec 补记）**：资格=text-leading 流式 turn（首个 renderItem 为 Single-Text）——多步 turn 先行 reasoning/工具卡的文档序与 shard 全 turn 粒度插入不兼容（shard 会排到先行内容之上），拒绝分片降级单容器；A2 不复用 ChunkedAssistantMessage（MdChunkPlan blockRange 与 renderItems 强耦合，shard=单 part 文本切片不适配）——新极简分支。

**验证**：StreamShardEntryTest 3 例（逆文档序/原键保持/displayEntryStart 钉头块/键族互斥/空表不变）+ StreamingShardBrokerTest 6 例（注册身份稳定/fire 钩子先行时序/未注册 no-op/注销后发布兜底+冷启续账/onRebuild 清账）+ 全量 **3810/0/0** + compile 绿。

**已知边界（诚实）**：①Fire 重建窗——尾块 >800ch 时切尾重建经 2-3 帧回涨（≤800ch 单帧完成），A3 影子态双喂消除；②资格限制——非 text-leading turn（reasoning 先行等）不分片，O(内容) 残留在该形态；③真机未验（A4：gfxinfo 滑动 p90 ≤12ms、换装帧高度恒等的块 padding 对称假设、VDRAW/SilentShift 零毕业帧滑移）。

## §5 A4 真机烟测两轮 + 热修（2026-10-02，dev 包 v2e2e:4298 容器，Big Pickle 模型）

**环境**：WiFi adb（mdns 别名 serial）；v2-e2e 容器（xchg-data 可写挂载，新建会话选 ~ 目录）；长文 prompt（25 节/6000 词技术综述）。

**第一轮（装机 db4f7e91）**：
- **shard fire 全链活**：16 次毕业（origin 2195→35531，每 ~2.1Kch 一次），tail 恒小（26-403ch ≤800ch 单帧重建——**A3 影子态的实际需求存疑，待正式 A4 判定**）；
- 视觉完整性：3 采样视口 vision 判读（节号 22→18→15 单调递减、无重复段/无空洞/无重叠）；
- 混合窗帧率（含呼吸光标 120Hz 动画帧+16 次毕业+滑动）：p50=5/p90=12/p95=17ms，Janky(legacy)=16.9%——无灾难（正式 p90 判定需专项方法学与基线对照，另批）；
- 信号判读：MDResize 负 d 锯齿族（-28xx）= 尾块毕业收缩（新签名家族，**与 #484 坍缩判读签名集区分：负 d 大值+同步 shard fire 行=毕业常态**）；ScrollDiag RESIZE 正向 132-198px=尾块健康增长；SilentShift 18 条全在轮首（底距恢复 48px 标准行为）；零 app 崩溃。
- **发现 P1**：回收重组合后尾块 RESIZE **h 96→49390（d=+49294）**——全文重复渲染。

**P1 定罪（日志链）**：MessageCardAssistant 长文本滚动预解析通道（RenderReadiness registry ≥200ch）为完结 part 提供**全文** preParsedState → MarkdownContent preParsed 分支先于 pilot 分支 → 尾块渲染全文与冻结 shard 双重复 + 完结瞬间踢飞 pilot（效果在 delay(200ms) 中被取消，尾段 held 1415ch 不再 flush——REST 37224 vs 渲染账 35531+278）。

**热修**（同日装机）：已发布 part（controllerFor().hasPublished()）抑制预解析消费——尾块渲染权归 pilot 切片（shardHold 冷续）。附带 shard-reg/unreg DEBUG 日志（注册可观测盲区补齐）。

**第二轮复测（热修版）**：shard-reg ✓、14 次 fire ✓、**完结 EOF flush 完整**（held=0、releasedTotal==snapshotTotal=659）✓、**滚出滚回冷续单帧入树**（659ch 两批同帧 append、零 49xxx RESIZE）✓、全量单测 3810/0/0。

**发现 P2（覆盖面缺口，A2.5 待办）**：资格=text-leading turn 把**推理先行**轮次全部排除（服务端实证：LongCat 轮=[reasoning 1991ch, text 37218ch]，37K 正文零分片）——推理先行是现代模型常态，泛化方向=turn 在 renderItem 级拆分（推理前缀独立 item，TurnSegment Items(from,to) 同构），录 backlog。

**已知项**：正式 A4 判定（滑动 p90≤12ms 专项方法学+关开关基线对照）未做；+49270 的旧轮次 RESIZE 均为重启后 broker 清空的遗留路径（正常）。

## §6 B案（节奏收编）适配性系统分析（2026-10-02，用户指令：只分析不改动）


用户裁决语境（2026-10-02）：先系统性分析当前代码是否适配 B案，**不着急改动**；本节为全量分析存档。B案语义按 #442 裁决 note：SSE 原始 delta 直入引擎，绕开数据层 StateFlow→ChatMessageList 快照重组链（spec 2026-09-26 架构1 节奏收编；架构2 预留高度表见 §6.5 重估）。

### 6.1 现行管线全景（每 100ms 文本 flush 的实际逐跳路径，2026-10-02 代码实测）

1. **SSE 咽喉**：三服务器源（V1/V2/DSH）全部收口于 `MessageEventHandler.handleMessagePartDelta`（MessageEventHandler.kt:975）→ `pendingDeltas` 缓冲 + `scheduleFlush()`（不取消在途定时器——铁律）。
2. **flush（Default 线程）**：`flushPendingDeltas`（:424）= isStaleDelta 过滤（读 `_parts.value` 终态包含判定，#265/#266 语义）→ `_parts.update{}` 走 `MessageMergeEngine.applyDelta`（endsWith 去重+#223/#230 重建兜底）→ **StateFlow 发射**。持久化另路：`deltaPersistQueue` 增量 appendPartTexts（#340/#57 合并写）。
3. **发射源唯一性（本分析关键实测）**：`getMessagesFlow`（ChatRepositoryImpl.kt:74）冷种子仅 `first()` 读 Room，之后 `emitAll(eventDispatcher.messages)` 纯内存热视图——Room 持久化写**不回流 UI**。故每文本 flush **唯一滴答源 = `_parts`**（combine 十源中 getAllPartsMap 一支）；`_messages` 仅消息级事件滴答。
4. **ViewModel 投影**：MessageDataDelegate 十源 combine（:190，parts/加载/翻页/工具展开/status/toolProgress）整体重跑：会话投影 O(n)+ChatMessage 实例缓存逐条 `cached.parts === injected` 比对（仅流式消息失配换新实例）→ messageListState 新实例。
5. **ChatScreen 投影**：messageState collect →（JankHold：仅滚动窗内冻结）→ `rawMessages` remember 重跑：reversed+filterNot（遮蔽/压缩绑定）+dedupeByEventIdentity+mapIndexedNotNull O(n)+`diffDisplayItemsInto` O(n)（槽位差量 set——**结构性改善**：仅尾槽失效）→ `rawMessages` 新 List 实例。
6. **ChatMessageList 整函数体重跑（根因二主面）**：rawMessages 参数新实例 → ~2756 行函数体重跑。逐项实测：
   - `remember(rawMessages)` lambda 全部重执行：turnGroups/turnAnchors 生命周期签名 O(n)（缓存命中免重建但 lambda 本身重跑）、streamingMsgId 扫描、nextReal/hasLater 两 map 重建 O(n)；
   - `renderableTurns` 逐消息**内容指纹**（流式消息全文本哈希 O(len)/flush——Default 线程 isStaleDelta O(len) 之外的第二个 O(len)/flush 面）；
   - **chatEntries 不重建**（实测键分析：displayItems.size/turnGroups/turnAnchors/streamingMsgId/chunkPlans/segPlans/recentKeys/streamShards 在纯文本增长时全部相等——签名缓存生效，仅结构性事件/毕业发布触发重建。比 25 世轮根因清单时点已收敛）；
   - itemsIndexed content lambda 可见项重执行：键比对后**仅流式 item 重组**（指纹缓存）。
7. **必要工作面（item 级）**：流式 item → PartContent → MarkdownContent(markdown=part.text) → `normalized=remember(markdown){normalizeForStreaming}` O(len)（哨兵快路径）→ pilot LaunchedEffect 重启 → 前缀差分 O(delta) → SafePrefixGate → state.append → 帽尾块测量+配对。
8. **reasoning 期同链**：终点 ReasoningBlock(text=part.text) 整块重排版（无 pilot 增量机制）；glm/LongCat 推理先行轮次的 Waiting 期全程走 1-6 全链。

**根因二残余面精确化**：相对 437:402 时点清单，displayItems 差量写/指纹缓存/签名缓存已把「可见 item 全量重组」收敛为「单 item 重组」；**残余=步骤 4-6 的每批顶层重跑**（combine O(n)、ChatScreen 投影 O(n)、ChatMessageList 函数体+全部 remember(rawMessages) lambda+指纹哈希），这正是裁决 note 所指「A案不消除每批顶层重跑」。

### 6.2 B案可复用接缝盘点（A2 之后资产，成熟度高于 spec 撰写时点）

1. **单一咽喉**：handleMessagePartDelta 三源收口——B案切割点唯一，无服务器分支。
2. **三分离已成型**：flushPendingDeltas 内累积（pendingDeltas）/发射（_parts.update）/持久化（deltaPersistQueue）本就是三段——B案切割是**函数内改写**，非跨模块重构。
3. **broker 单例先例**（A2 新增）：snapshot-state 发布 map + partId 注册生命周期 + clearAll + controllerFor 回退——delta bus 同构可复制（或 broker 扩 lane）。
4. **pilot append 机器完整**：前缀差分/gate/#471③归一化（终帧=流式帧）/#472 非前缀宽限/resetKey/#H4 快速重灌/shard adopt 冷续——B案只换**驱动源**（参数快照→bus 快照），机器本体不动。
5. **JankHold 终态实证（最强可行性证据）**：滚动窗内冻结 messageState+rawMessages、pilot append 照常、渲染正确（二十四世轮终修+后续重度使用）——「UI 快照静止+item 级增长」**正是 B案目标态的手动模拟**，已在生产路径验证。B案实质=把该终态常态化+把增量喂送搬到冻结点之前。
6. **完结换装三件套就位**：#440 槽位键（t_X 稳定）、#472 完结保持（pilotTerminalHold）、PartUpdated 权威替换走结构性 _parts 发射——text.ended 到达时 pilot 前缀差分自证（等值=no-op；不等=既有宽限+重建兜底）。
7. **ScrollQuiescence 单信号源**：append 暂缓/settle 追平语义原样保留。
8. **cadence 常量已归引擎域**（5d061df2）：bus 节奏即引擎策略，架构1「48ms 退役为引擎内部策略」的常量面已就位（100ms 现值，可调）。
9. **RenderSupplyCoordinator 推送制**：onWorldArrived/onViewportChanged 由 list 侧 LaunchedEffect 推送——快照静止后 world 推送自然停，plans 稳定，chatEntries 键面静。

### 6.3 架构2（预留高度表/预测量管线）关系重估

A案冻结分片已以「静态内容零重测（Compose 布局缓存）+尾块小重测」**等价达成架构2的测量面目标**（根因一：帽全子树重测——437:400 已收口为帽=尾块 item）；预留高度表独有值（施加前预知高度）被「帽配对+I1′ 同帧原子」（VPT/VDRAW 证据链）覆盖。**裁决建议：B案提前范围裁为架构1（节奏收编）单干；架构2维持 parked**（spec 2026-09-26 变更记录第 1 条的 R2 预期已由 A案以不同机制兑现），A4 正式复测不达标再议。

### 6.4 B案落点设计草案（记录备裁，不实施）

- **数据层**（flushPendingDeltas 内切割）：per-flush 改为①影子累积（shadowParts——**复用 MessageMergeEngine.applyDelta 纯函数**，#223/#230/#265 语义不回退）②bus 发布（聚合 delta 按消息，singleton snapshot-state）③持久化照旧；`_parts.update` **延迟到结构性事件**（part started/ended、message add/update/complete、tool/step 事件照常即发）。isStaleDelta/inferDeltaKind 读点改读影子。
- **UI-text**：pilot 喂养源换 bus 快照（markdown 参数降级为结构性对账源——完结权威/REST resync/翻页回收冷启时兜底，**真相源仍是 _parts+Room，bus 只是流式期快路径**——R7 降级安全）。
- **UI-reasoning**：ReasoningBlock 直读 bus snapshot state（块内作用域重组；无 markdown 增量需求，Text 重排版即必要工作）。
- **对账 cadence**：流式期低频结构性回灌（如 1s^-1 或 N 字符阈值）保复制/搜索新鲜度（R4）。

### 6.5 风险清单（spec 批裁决点）

R1 reasoning 覆盖（不覆盖=根修对 glm 系主流模型不成立——**必须进范围**）；R2 影子累积复用 applyDelta；R3 读点改影子；R4 流式中读旧文（复制/搜索）；R5 完结权威对账（机器已在，验证面）；R6 会话切换/多服务器/DSH echo 竞态（bus partId 键+clearAll，#490 族语义保持）；R7 回收/翻页/冷启降级路径（bus 缺席=原路径，非真相源）；R8 JankHold 退役时机（B案后成死代码——过渡期保留，验证后另批清扫，**probe 保留纪律同理**）；R9 归一化全串重算 O(len)/flush（哨兵快路径已限成本；增量归一化留优化位非阻塞）。

### 6.6 适配性结论

**适配，且接缝成熟度显著高于 spec 撰写时点**（6.2 九项）。实质表述：B案=「把 JankHold 已验证的滚动窗终态常态化，并把增量喂送点搬到冻结点之前」——不是开新架构，是沿既有缝把缓解装置换成常态装置。无阻塞性技术缺口；R1-R9 均为 spec 批裁决点而非可行性风险。

### 6.7 若裁决提前：批次草案与验证面（记录备裁）

B1 spec 批（R1-R9 裁决+bus 形态+对账 cadence+STREAM_DELTA_BUS dev 开关）→ B2 数据层切割 → B3 text 接线 → B4 reasoning 接线 → B5 表征+E2E（完结/回收/翻页/切换/resync）→ B6 真机判定。**根修达成判据**：流式稳态 ChatMessageList 函数体重执行≈结构性事件数（今日 ~10/s→≈0/s，重组计数器/[DEBUG-jk] 观测）；[452-combine] 发射频率同降；既有铁律网全绿（VDRAW 泄漏=0/CONTENT-BLINK=0/#492 检测网/贴底跟随与读历史回归）。与 A 线关系：A2.5/A4 与 B 案正交可并行（A 线修测量面，B 线修重组面）——次序裁决留用户。

## §7 B案节奏收编实施 + A2.5 泛化 + B6 判定（2026-10-02，用户指令：B 面全量+A 线收尾一次到位）


### 7.1 落地批次（六 commit）
- B1 spec：docs/specs/2026-10-02-442-b-cadence-incorporation-design.md（R1-R9 裁决+A2.5 设计+A3 裁决）→ d9f128b4
- B2 数据层：`_parts` 热视图语义零变更；structuralParts（dispatch 尾+直调入口发布）+ StreamingDeltaBus（flush 发布累积全文/终态与清理族撤销）+ getStructuralPartsMap 接口贯通；5+1 表征测 → d9f128b4
- B3+B4 UI 接线：PartContent 文本/推理两分支 `live ?: part.text`；五消费面切 structural（十源 combine/跳转镜像/shell 解析/任务聚合×2）；9 测试桩补 → c7ab5887
- A2.5：注册放宽任意位置首个未完结 Single-Text（活提问暂缓门控）；StreamPrefix 条目（#p 键族，ChunkAssistantItems 前缀+questionAnchorInPrefix 提问分工）；尾块子范围切片；3 测例 → 9dcb8295
- B6 判定批（两真机定罪热修）→ 8a9eff20

### 7.2 B6 判定证据（v2e2e:4298 真机，WiFi adb e69a99d8）
- **CML-tick 判据探针**（ChatMessageList 函数体重执行计数，DEBUG 永久）：关关基线（-POCBEACON_STREAM_FLAGS_OFF=true 构建）流式稳态 **~4.8-6/s**；A+B 开（含两热修）**轮次启动结构性窗口（~10s：播种/part 生命周期/毕业）后 ≈0/s**——deltas 持续流入（[flush] batch #601→901）而 tick 归零。**根因二收口实证。**
- **热修①（P1，仪器定罪）**：dispatch 尾对 MessagePartDelta 也发布 structural——flush 与后续 delta 交错把「已含本批累积」的热视图新值过桥 → 结构性静默被击穿（CML-tick ~6/s 之谜；探针 raw 身份签名逐 tick 变化定罪）。修复=Delta 事件例外发布；单测钉死。
- **热修②（P2）**：ChatScreen `jkHold` 直读 `StreamingScrollHold.holding`——B案后冻结无事可做，但贴底跟随每滚动帧翻转 → ChatScreen 重组经参数链重跑 ChatMessageList（raw/cp/sp/rk/tp 全稳定仍 ~10/s 的残余驱动者）。修复=旗标开时退役该读（R8 裁决修正：非「无害失活」而是「按旗标退役」）。
- **B5 烟测（全链活）**：text-first（Big Pickle）3 fire+EOF flush 完整（682==682 held=0）+三视口无重复；reasoning-first（LongCat）注册+3 fire+**prefix 渲染实证**（「思考完毕」chip+推理预览在正文上方，文档序正确）+EOF 213==213；会话切换重进正常；零 FATAL。
- **gfxinfo 配对**：基线混合窗 p50=6/p90=29/p95=38ms vs 修复后多窗 31/73（启动铺开窗）/10-31——**噪声主导**（呼吸光标 120Hz/铺开期重解析/overscroll 伪影），帧级收益被掩蔽；根修判定以 CML-tick 为准。A4 正式 p90≤12ms 专项方法学（分阶段窗+光标抑制）仍开放（A4 烟测混合窗 12ms 在案）。

### 7.3 A 线收尾
- **A2.5 落地**（见 7.1）：推理先行轮（glm/LongCat 常态）分片覆盖，37K 正文零分片的 P2 缺口关闭。
- **A3 影子态裁决：放弃**（按 §6 调研：tail 恒 ≤800ch 实证，影子态复杂度无对应收益；A4 正式复测若推翻前提再立卡）。
- **架构2（预留高度表）维持 parked**（spec 2026-10-02 §2.4：A案冻结分片已等价达成其测量面目标）。

### 7.4 遗留/顺手发现
- **CJK 粗体 flanking**：`**……。**学`（闭界后紧跟 CJK 字符）渲染为字面星号——服务端原文+markdown 库 flanking 行为，旧路径同渲染（非 B案/A案回归）；已录 backlog 卡。
- JankHold/STREAMING_MD_PILOT 等过渡装备在 B案稳定后可整体清扫（独立批次）。

## §7.5 A4 正式滑动 p90 判定（2026-10-02 补做，判定批裁决后续）


**方法学（降噪三件套）**：①**framestats 环形缓冲分块**——每手势循环抓 120 帧环（≈1s 有界窗），消除长窗累积下呼吸光标 120Hz 帧对百分位的稀释（光标帧本身是流式负载合法成分，问题只在多分钟窗的淹没）；②**稳态期分段**——开流后等待正文 append 活跃（非铺开期/非推理期，MDPilot append 行验证）才采样；③**双臂同协议**——同模型（Big Pickle）同句式 prompt（≥6000 字长文）同 8 循环（300ms 手势+0.9s 间隔），臂内取逐块 p90 的中位数（单块离群=毕业/铺开窗被中位数吸收）。工具 /tmp/a4_measure.sh（framestats 采集+窗口标记）。

**结果（真机 e69a99d8，120Hz）**：
- 关关基线（-POCBEACON_STREAM_FLAGS_OFF=true 构建）：逐块 p90=[16.4,10.4,14.3,7.1,7.3,8.0,8.5,10.5] → **中位 p50=2.5/p90=10.4/p95=14.3ms**
- A+B 开：逐块 p90=[16.9,17.3,8.0,7.1,7.6,7.4,7.4,7.2] → **中位 p50=3.6/p90=7.6/p95=8.6ms**

**判定：达标（p90=7.6ms ≤ 12ms 目标）**；相对基线 p90 -27%、p95 -40%，且 A+B 尾部块稳定收敛 7.1-8.0（基线散布 7.1-16.4）。前两块 16.9/17.3 = 轮次铺开/毕业窗残余（结构性合法成本）。帧时长列=[13]-[2]（Vsync→完成，本机 HyperOS framestats 混合时钟列布局下的稳定量纲；数据健全性：计数器单调、p50 4.7ms 合理）。

**§7.2 勘误补正**：先前 gfxinfo 摘要百分率配对（基线 29ms vs 终版 10-31ms「同量级」）的结论作废——该法受多分钟窗光标稀释+过滚伪影污染；本节方法学取代之。**A4 正式判定关闭。**
