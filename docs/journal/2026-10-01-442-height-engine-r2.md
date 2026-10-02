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

## §7.6 全面覆盖批 + 重进场景族真机实证（2026-10-02，用户指令：最全面测试+全路径动机埋点）


### 7.6.1 分支补全与动机埋点（f7ca309d）
- **分支补测**（全量 3848/0/0，+24 测例）：PartRemoved/TimePatch 终态化撤销、**非终态 PartUpdated 保留覆盖**（流式连续性——元数据补齐不得误清 bus）、**REST 合并后下一 flush 以合并基线重发布**（连续性闭环）、clearForSession、no-op 补丁值相等去重零发射、pruneReverted 发布；资格纯函数 shardRegistrationPartId 提取（7 分支：text-leading 平权/推理先行 k>0/未完结优先/全完结回退/无 text/活提问暂缓/旗标关）+ 锚定函数 10 分支（含 callId 只认 Tool part、指向非 Tool 不命中走回退两个真实语义修正——测试期望先行错误被运行揭穿）。
- **动机埋点体系**：每测首行 `[MOTIVE]`（测试报告 stdout 可追溯「为何测此路径」）；生产探针全部带动机语义——[B2-struct] cause=<事件> msgs=<n>（哪个结构事件触发了 combine 源滴答）、[B2-bus] publish/clear/clearParts/clearAll（通道事实+让位原因）、[A2.5] prefix compose/tail slice（k 边界）、[B3] text live override/fallback + jkHold retired、[B4] reasoning live。

### 7.6.2 重进场景族真机实证（v2e2e:4298，探针验证路径）
- **①流式中途退出→重进（推理期，最对抗路径）**：退出会话 15s（SSE 持续、bus 后台持续 publish）→重进触发 REST 刷新连发（upsert 4 次/160ms，历史行为）：每次 clearParts 撤销覆盖→**同帧 structural 已发布合并态兜底**→下一 flush（≤100ms）bus 重建→`[B4] reasoning live override` 探针于重进帧重新接管（fallback→override 翻转完整闭环可见）→batch#401/#501 持续发布、deltas 持续到达→轮次正常完结（completed 实证）→终态重进渲染完整。**结论：输出消息重进问题已解决**（内容零丢失、流零中断、换装无缝）。
- **②完结会话重进**：丝路会话（完结数分钟）重进——结尾结语段落完整连续、无重复、无空白。
- **③完结长文深滚回收**：6+6 双向 fling——内容连续（景教→伊斯兰→翻译节）、无重复/缺失、梵文变音符号渲染正确、零 FATAL（FAB 遮挡为既有 UI 非回归）。

### 7.6.3 顺手发现（未立项，低危）
- 重进时 REST 刷新连发 4 次（多线程并发完成）——历史行为放大 bus clear/publish 抖动一帧（被同帧 structural 兜底吸收，无视觉影响）；如后续观测到重进闪烁可查此（upsert 幂等合并本就防御）。

## §7.7 终审完整性审计（2026-10-02，用户指令：再审测试完整性/需求实现/可用性/遗留项）


### 7.7.1 审计揭出的真实缺陷（已修）
- **beta/stable 编译失败（P0 级）**：STREAM_SHARD_PILOT/STREAM_DELTA_BUS 仅 dev flavor 声明而代码无条件引用——beta/stable 自 A2 批（db4f7e91，2026-10-01）起编译不过，下次发版必炸。修复=两 flavor 补声明（false=试点未提升，沿 STREAMING_MD_PILOT 全 flavor 先例）；compileBetaDebugKotlin/compileStableDebugKotlin/compileDevDebugAndroidTestKotlin 三任务验证通过；BuildConfig 引用↔声明交叉核查无其他缺口。
- **双臂单测纪律补全**：旗标关臂全量跑出 23 败——逐类甄别全部圈定在三支旗标开语义测试类（StructuralParts 13/Bus 5/Broker 5，断言的就是开臂行为）；补 Assume 守卫显式声明语义后：**开臂 3848/0/0，关臂 3848/24 跳过/0**——「关=回退」与「开=根修」两臂均机器可判全绿（关臂真机行为另由 A4 基线臂实证）。

### 7.7.2 需求→证据终审表（#442 卡项逐条）
| 卡项 | 状态 | 证据 |
|------|------|------|
| R2 分片增量化+滑动 p90≤12ms | ✅ | A1/A2/A2.5 落地；A4 双臂 p90=7.6ms（§7.5） |
| cadence 收编 | ✅ | 常量归位(5d061df2)+B案结构收编（delta 直入） |
| flush 深拆 | ✅ | 批次 C 表征 7 例（§2） |
| 终审待复核四项 | ✅ | 批前四十六世轮销账（§1） |
| #445 吸收（双容器/固化解迁移帧） | ✅ | A2 冻结 append-only+#H4 快灌+#472 完结保持 |
| #445 观感项「完结窗小跳」 | ⚠️ 机制性已修待复验 | #472 pilotTerminalHold（09-28 根修 09-27 观感）；用户验收时复验确认 |
| #470 帽回改 B/A 裁决 | ⏸ 挂起 | 用户裁决类（4h 重度使用零负向事件在案，不阻塞） |
| A3 影子态 | ✅ 裁决放弃 | tail 恒≤800ch 实证 |
| 架构2 预留高度表 | ✅ 裁决 parked | A案等价达成测量面（§6.3） |
| B 案全链（重组根修） | ✅ | B1-B6+CML-tick 4.8-6/s→≈0（§7.2） |
| 重进场景 | ✅ | 三场景实证（§7.6.2） |
| 分支+动机埋点 | ✅ | +24 例 3848 双臂绿（§7.6.1/§7.7.1） |

### 7.7.3 「功能完全可用」的诚实边界
- **dev 渠道**：完全可用（本轮全部真机验证即 dev 旗标开）。
- **beta/stable**：修复**未激活**（旗标 false）——试点提升是发版裁决（release-workflow 流程+用户拍板），非代码缺口。提升前 beta/stable 行为=今日优化前基线（编译已恢复可用）。
- 测试弱项如实：插桩 androidTest 族仅编译验证未执行；提问卡×分片共存、多消息 turn 二段正文为单测覆盖未真机专项（场景难造）；模型侧行为（繁体输出/慢推理/排队）非 app 缺陷。
- UX 观察项：#498 CJK 粗体闭界（P3 在册）；轮次铺开期 16-17ms 块（限速铺开设计内，稳态 7-8ms）；FAB 遮挡行尾（既有 UI）；完结小跳待复验（上表）。
- 清扫独立卡已登记（B案验收+提升后执行）。

## §7.8 中断 E2E 补测 + #470 裁决落章（2026-10-02）


### 7.8.1 流式中点「停止」真机 E2E（补缺口，v2-e2e:4298）
- 路径：真实输入（keyevent 打字）→ 真实 SSE 流（deltas batch#601 活跃验证）→ 清空输入框（DEL 键序列）→ 停止键现身（#326 单键裁决：忙+输入空白=停止键）→ 点击。
- **中断生效链全绿**：Ktor abort 请求 → 服务器 `session.execution.interrupted`+`session.step.failed` → FSM `Idle --TextDelta/TextStopped` 转移 → part 终态化（[B2-struct] cause=MessagePartUpdated 过桥）→ 后续 REST 合并 clearParts n=9 撤销全部 bus 覆盖 → UI 呈现「已中断：Step interrupted」状态行+被中断部分正文保留+统计栏正常 → 零 FATAL。
- 两次预备尝试的教训（如实）：①重复发送造成双轮排队（重试发送前的「无流证据」是 grep 窗口太窄误判——首个 prompt 实际已发出）；②停止点击曾落空（两轮已在点击前正常完结）——最终以 deltas 活跃验证后才执行。
- 顺手观察（低优先）：`session.execution.interrupted` 事件在 SessionNextEventHandler 未映射（Unhandled 日志）——step.failed 已驱动 FSM/UI 正确收敛，无 UX 影响；事件映射补全可随 #459 漂移域顺带。

### 7.8.2 #470 帽回改 B/A 裁决（用户 2026-10-02：「470 先 A 吧」）
- **选 A：维持空白**（帽不回改+视口不跟随——回缩后空白保留）。依据：4h 重度使用零负向高度事件在案；如日后实际遇到「回缩后大片空白」不适再立卡升 B。#442 吸收域内唯一挂起项就此闭合。

## §7.9 旗标提升 beta 与 v0.4.0-beta 发版（2026-10-02，用户裁决「提升吧」）


- **裁决执行**：STREAM_SHARD_PILOT+STREAM_DELTA_BUS 提升 beta=true（ad9cb70a）；stable 维持 false（再晚一批观察；回退=翻 false 一行）。
- **发版实战**：v0.3.0 正式版后首个 beta=**v0.4.0-beta**（code 41）——版本线勘误：v0.3.1-beta/0.3.2-dev 系 v0.3.0 正式版之前的历史线，脚本自 0.3.0 推 patch 撞旧 tag，按 §3.3 用 `--force-bump=minor` 开 0.4.0 线（自上正式版起 feat 累积，MINOR 合规）。
- **顺手修复两件**：①release.sh `python` 裸调在本机（仅 python3）中断——补 PY 派发器（4bded7f9）；②#484 关卡迁移 journal 补提交（当时编辑未随批入库）。
- **Release Notes**：FIFO 控制脚本 stdin 在润色暂停点注入用户视角稿（范围 v0.3.1-beta→HEAD 全役主题：流式引擎根修/高亮/数学标注/卡内滚动/数据层稳健/V2-DSH 适配）。
- CI Build Release APK 已触发（in_progress）；§6 验证清单（Release/APK/签名）随后执行。

## §7.9 续——v0.4.0-beta 发布落地与 §6 验证（2026-10-02）


- **首次 CI 失败→lint 门禁四错清零（aae6b576）**：间距令牌绕过×3（HighlightedCode vertical 8.dp×2+bottom 4.dp、MarkdownContent 12.dp → SpacingTokens SM/XS/MD）+remember 返回 Unit（A2 shard 注册块 true 收尾）+组合期 StateFlow.value（#477 A11yDiag 探针 stateType 字段移除，探针保留 src/len）。教训：**本地漏跑 lintDevDebug 预检**（AGENTS 在案条款，后续批次必跑）。tag v0.4.0-beta 重指修复提交（原 tag 无出货 Release，重指不触 §7 红线）；全量 3848/0/0 复核后推。
- **§6 验证全绿**：Release=OC Beacon 0.4.0-beta（prerelease ✓）恰好 1 APK=oc-beacon-0.4.0-beta.apk；aapt2=dev.leonardo.ocbeacon.beta/vc41/vn0.4.0-beta；apksigner DN=CN=OC Beacon（release keystore ✓ 非 debug）；说明=润色稿（版本摘要+用户视角）。
- **beta 渠道自此带上**：R2 分片（A案）+ 节奏收编（B案重组根修）+ A2.5 推理先行泛化。stable 待下一批观察后随发。

## 已完结卡片迁入（2026-10-02）

### **#470 流式高度配对收缩缺口:帽不回改空白残留+ledger收缩不配对视口落** `scroll,chat`
  - 2026-09-30 调研 P3 定罪:①帽轨 reserveReleasePlan 对 trueHeight<=reserved 恒 null(帽单调只增,ScrollCompensation.kt:338)——流式内容回缩(表格列放宽/setext 前重排)时 item 保持旧高=空白残留,直到换流式项 reset;②ledger 轨 note 对 d<0 只 rebase 不配对(:157)——压缩卡/工具横幅回缩时上方内容下坠无补偿。修复需高度引擎域专项设计(帽回改与『已上屏永不回改』既有裁决冲突,需用户裁断语义:回缩时同步缩帽+视口跟随 vs 维持空白)。
  - 2026-09-30 用户裁决（听agent建议）：先不急，并入 #442 高度引擎二期系统性解决——帽回改语义与 R2 分片增量化同域设计，B/A 裁决推迟到二期设计时定
  - 迁入依据：用户裁决 2026-10-02 选 A（维持空白——帽不回改+视口不跟随）：缺口两路径（帽轨恒 null/ledger 负 d 不配对）经裁断定为接受现状；4h 重度使用零负向高度事件实证为据；如日后遇到「回缩后大片空白」不适可凭本卡重开升 B（journal §7.8.2）（backlog.sh migrate 2026-10-02）

## §8 #501 DSH 验收根修——流式正文结构性不可见（part 出生过桥）


**用户定罪（2026-10-02 真机验收）**：DSH 会话流式输出「一次性跳出来」；Web 端同期思考/正文均流式。修正初判（「服务器思考期不发数据」不成立——那 20s 是 turn1 被中断后用户打字的间隙）。

**地面真相方法学**：自建原始 WS 客户端（纯标准库，握手 /api/remote.mux + Cookie + `{"type":"open","streamId":"f:<sid>","endpoint":"session/follow","payload":{"args":{"request":{"address":…,"assistantStream":true}}}}`）订阅同一会话抓 940 帧：线面健康——`block-start`(空种子,chunk.index=块序号) → reasoning-delta×145(4.4-6.5s) → text block-start → text-delta×778(6.5-19.7s) → block-end×2 → usage → finish → assistant/message(全量终态)。transcript(session/page) 只记终态事件，流式 chunk 不落档。附：/api/events.mux 是 0.1.1 双流端点（0.2.x 404/空回），0.1.2+ 单 WS = /api/remote.mux。

**根因链（全链证据闭环）**：① #230（2026-08-26，正确防线）零信息 part 一律不注册——DSH block-start 空种子照章丢弃；② part 只能由 flush 的 applyDelta idx<0 兜底在**热视图**出生（bus 一直携带累积全文，live=4 实证）；③ B案 UI 读 structuralParts=热视图快照、仅在**结构事件**过桥——DSH 流式期零结构事件（纯 delta 线面）→ 出生永不过桥 → PartContent 组合件从未存在（B3 探针全程静默）→ 完结 assistant/message 才带来首个含文本结构事件 → +5675px 整段砸出。SSE 不受影响：part.updated 携累积文本=流式期不断过桥。reasoning 可见属巧合：text block-start 自身是结构事件，其 dispatch 尾捎带把已出生的 reasoning part 过桥（8.18s [B2-struct] publish cause=MessagePartUpdated 但 msgs 列表无 text part）。

**修复（根因级，一处）**：flushPendingDeltas 增 part 出生检测（update 前触及消息 part id 基线 → update 后出现新 id）→ `publishStructural("part-birth")`。出生是结构事实（列表条目新增），每 part 一次低频；纯文本增长仍只走 bus——「delta 批结构性静默」不变量不破（B6 回归锚测试保持绿）。

**真机验证（13:14 E2E，TLS 1.3 prompt）**：`part-birth` 过桥 ×2（reasoning 0.15s / text 5.3s）；**shard-reg 流式期注册**（A2.5 首次在 DSH 线真实生效——此前文本 part 不在 renderItems，资格判定恒空）；B3 5.4s 即燃；MDResize 421→1586+px ~66px 步长持续增长；CML-tick=0；完结换装干净（无重复/脏行，视觉查重通过）。单测 3850/0/0，OFF 臂 15 skip（Assume 纪律）。

**遗留认知**：思考期 app 呈折叠单行预览（live 更新）与 Web 展开形态不同——既有 ReasoningBlock 设计，非本缺陷范畴。beta 旗标已提升，下一 beta 版本携带本修复。

## §9 #502 配对让位永久化死锁——高度帽冻结致流式消息裁剪在视口小块


**用户定罪（2026-10-02 #501 验收中有机复现，难复现）**：「最后一条消息被限制在视口的一小块区域内，输出完毕之后就展示全消息了」。受控复现三轮（纯贴底/慢拖上翻回底/快甩）均未踩中——配方是时序敏感的。

**帧级定罪链（现有埋点，turn 10 17:08:31-49）**：上翻阅读（idx 0→7，align-flip bottom=false 阅读态）→ 引擎在阅读位配对 `set(7,393)`（lastSet 写入）→ 用户甩回底 (0,0)（align-flip bottom=true）→ 回底后首个增长 flush `yield(external-pending) read(0,0) last(7,393)` → 此后 **yield×1448（每帧）**，`reserved` 冻结 685 而真高 1128→4303 → 完结换装 reset 直通真高（症状自愈）。

**根因**：`shouldYieldPairing`（ScrollCompensation）的假设「读位≠上批 set 目标 ⇒ 存在待消费外部 pending」只对引擎自己写的 pending 成立（set 请求时即写 lastSet，measure 消费后读位=目标，让位自愈）。用户手势是**已完成**的外部滚动：读位永 ≠ lastSet，而 lastSet 仅由 applyPairedShift 写——引擎因让位不 apply ⇒ 永不让位条件解除。鸡生蛋死锁。每帧 `pendingReserveRelease = null` 作废帽释放计划 → 帽协议 `min(child.height, reserved)` + `clipToBounds`（底对齐 place）= 消息裁剪在冻结帽高的小框。**暴露条件**：#501 后 DSH 流式期有真实增量可卡（此前正文不增长无增量），用户滚动测试凑齐「流式中+上翻阅读（引擎 set）+甩回底」配方。

**修复（静止采纳 + 陈旧度守卫）**：`shouldAdoptExternalPosition(read, prev, framesSinceLastSet)`——读位连续两帧静止 **且** lastSet 陈旧（≥2 帧引擎无 set 写入；守卫防误毁引擎 pending 未消费窗口的保护——set 请求时即写 lastSet、消费前读位=旧位静止，裸静止判定会把旧位错立为基线=「上方内容闪烁消失」根修回归口）⇒ 采纳为配对基线，落回正常求值（帽释放当帧生效）。FlushTaskMemory 增 prev 读位 + 帧序 + lastSetFrame 戳记（applyPairedShift 写 set 与采纳两处）。

**验证**：相位级测试（mockk LazyListState 确定性驱动三帧死锁转换）——帧1 阅读位 set → 帧2 yield 观察（帽不动）→ 帧3 **采纳+帽 685→1128 当帧释放**（旧行为此处永久 yield）；pending 近帧保护回归锚。纯函数矩阵 4 用例（静止采纳/移动不采纳/近帧 set 不采纳/无观测不采纳）。全量 3856/0/0。真机三轮 E2E（长文流式+手势）：帽全程健康推进、无回归、无 adopt 误触发；有机配方（用户手指的精确甩量）待用户自然使用复测。

## §10 #503 流式尾段重建循环——A2 冻结分片毕业 fire 回卷（旗标稳定化）


**用户定罪（2026-10-02 验收）**：「输出到快结束的时候一直在重建循环」。定罪证据（turn 18, 18:45:46-55 尾段）：9 次 `MDPilot shard fire`（origin 回滚重冻结：2313×2/2629×2/2911）+ turn 条目回收重组对（shard-unreg 46.689→shard-reg 47.769）。回卷循环链：fire → broker 发布 → 条目 churn（StreamChunk 插入+A2.5 prefix 翻转+尾块重切）→ LazyColumn 条目回收 → pilot 冷启（coldStartPlan 重播）→ 再 fire。每循环一次全量条目重建=用户所见。

**诊断旁支（记录备查）**：同轮另有视口 0↔7 弹跳 24 LEAP（全部 inProgress=true）；ChatScrollController 侧 MSGEFFECT fire 仅出现于轮前（autoOn=true 时），27.693 autoOn 正确解除后 **GUARD reanchor 与 MSGEFFECT 全静默（零开火）**——弹跳非二者所为；引擎配对 set 全部指向阅读锚 (7,x+Δ)（#502 采纳后的正常阅读补偿）。疑弹跳为条目 churn 的副作用（LazyColumn 重锚），pilot 关闭后待复测，若仍在另立卡片。

**稳定化（本提交落地）**：STREAM_SHARD_PILOT=false（dev + beta 下一构建）。冻结分片是性能优化（#442 A2），关闭=回退单容器流式；#501 part-birth（DELTA_BUS 旗标）与 #502 静止采纳均独立不受影响。

**验证（18:55 E2E，2000 字长答 37s）**：shard fire/shard-reg/prefix compose 全零；part-birth=2（两 part 正常过桥）；B3 文本 override 2.3s 即燃；MDResize 152→9552px 全程 ~66px 步长连续增长无跳变；完结换装干净；结构事件 13 个（正常量级）。全量单测 3856/0 失败/6 skip（分片旗标语义测试臂——Assume 纪律，预期行为）。

**根修方向（#503 卡片承载）**：毕业发布与条目生命周期解耦——fire 幂等去重（回收期 graduation 冻结/冷启不重播已发布区间）。

## §11 #504 DSH 完结换装闪塌根修——换装指纹桥（五轮取证迭代）


**症状（用户定罪 19:02/19:24/19:31/19:44/19:52/20:52 六轮日志取证）**：DSH 长答完结换装瞬间流式卡（8754~13820px）塌成 200px 占位、102~260ms 后回弹全高。根因：合成 part.id（dsh-tXs1_text_ord_1）→权威 seq id 换代经 `key(item.group.part.id)` 销毁 pilot 子树，#472 完结保持的本地 pilotEverRendered 记忆随节点丢失 → 新组合走 rememberAsyncMarkdownState 的 State.Loading 占位。

**五轮取证迭代（每轮真机 miss 证据 → 设计修正）**：
1. dispose-stash 交接 → 恒 miss：**Compose 派发次序**——旧节点 onDispose 在 apply 后、新节点 remember 组合中内联，stash 恒慢一拍。→ 改**活跃指纹登记**（pilot 渲染期直写）。
2. 状态实例 seed + #472 hold → hold 渲染空态 200px：库 **StreamingMarkdownState 对新收集器零重放**（freeze 后无发射）。→ 弃实例交接，改**指纹门 + 换装帧同步解析**（rememberSyncMarkdownState——normalizeForRender 与终态同源视觉恒等，一次性 ~10ms 主线程，非异步冷滑场景）。
3. 严格相等门 → miss（pilot 终帧落后终态 2 字符：末批 delta/扣留尾）。→ 尾差容错 512。
4. prefix 容错 → miss（forensic：`stash=3742 inc=3758 gap=16 prefix=false head-eq200=true`——完结内容对**尾部区域**改写 ~16 字符（围栏闭合族）非纯追加）。→ **公共前缀 + 尾部重写松弛 256**：分叉点须落两串末 256 字符内=同文档尾部改写；中段分叉恒 miss。
5. 单槽指纹 → miss（forensic：`stash=1165 inc=3988`——**推理块终态抢占槽位**，reasoning/text 多部件并行流式 last-writer-wins 互踩）。→ **多槽 LRU×4** 全槽遍历。

**验证**：全量单测 3863/0/0（含 +7 交接门用例：尾部改写命中/中段分叉 miss/缺口上限/空槽/归一化等价/尾差容错）。[504-forensic] 取证探针 DEBUG-only 永久保留（keep-probes 裁决）——miss 自动吐各槽长度/前缀关系。最终换装 E2E 被当夜无线 adb 频繁闪断阻断（装机都失败×2），留用户自然使用验收；探针自证任何残余 miss。

**架构注记**：SSE 路径 part.id 恒定无此症（换装零换代）——指纹桥是 DSH 合成 id 体系的专有补偿；若未来 DSH 改为 part id 稳定（或映射层吸收换代），本桥可整体退役。

## §11.1 #504 真机终验（2026-10-02 21:33）


K8s 2500 字长答换装帧取证：流式卡 h=11437（d=66 步长持续增长）→ 换装新卡 `b21bbd95-263 h=11461 d=11461` **首测即全高**——修复前形态（h=200 d=200 桩 → d=10330+ 回弹，六轮取证）完全消失。换装时刻零 forensic miss（多槽指纹桥命中；21:32 两条 miss 为进会话历史消息渲染、槽空，正常）。至此 **#501（正文流式）/#502（滚动死锁）/#503（重建循环）/#504（换装闪塌）四连修全部真机闭环**，DSH 真机验收暴露的回归链收口。

## ## 12. #505 流式重复短语误杀——applyDelta endsWith 去重撤销 + 换装门头尾锚（2026-10-02 深夜）

### 症状与定罪链

用户报告 #504 修复后完结仍「闪一下」。真机 turn 30（21:35:42）日志铁证：

```
504-forensic: miss: inc=3734 slot=3704 pfx=false;slot=3709…;slot=3724 pfx=false
MDResize: card=b21bbd95-272 h=200 d=200      ← 200px 占位（闪）
MDResize: card=b21bbd95-272 h=9597 d=9397    ← 100ms 回弹
```

长度差仅 10 字（≤512 容忍）但全槽 pfx=false=中段分叉。t30 原文无 $$/表格/任务标记（归一化恒等），段落均 <3000 → 分叉不在归一化层，在原始文本层。

**WS 抓包对照实验**（/tmp/ws_capture.py，t31/t32 两轮）：DSH text-delta 流与权威转写**逐字节相等**、帧序号零缺口 → 分叉在客户端累积侧。定位 `MessageMergeEngine.applyDelta` 的 `if (part.text.endsWith(delta)) part // 去重`（:682）：模型输出的合法重复短语恰等于累积尾部时被误判重复投递丢弃 → 中段缺口 → #504 换装门前缀断裂 → async 占位闪塌。

**journal 考古**：该去重是 SSE 时代防御遗产——#266 真机案例它自己没接住（「尾段非全文后缀→盲拼接」，靠终态守卫收口）；OpenCode 实测 delta **丢失**而非重复投递。两族服务器已文档化场景零保护价值，纯误杀面。

### 修复（9d88a616 之后，本节 commit）

1. **applyDelta 撤 fuzzy endsWith 去重**（Text/Reasoning 双分支，终态守卫保留）：真重复与真重投本地不可区分，两类误判都由完结权威替换自愈，而误杀额外击穿换装门——两害相权取原样追加。
2. **换装门头尾锚容错**（completionHandoffMatches）：中段小分叉（|gap|≤512 且前缀/后缀各留 ≥256 干净区）命中——门只选解析策略不选内容，误命中代价≈10ms 同步解析，无正确性风险。防未知传输层残余形态。
3. **forensic 探针升级**：miss 时打最优槽 pfxLen/sfxLen/gap + 分叉点上下文采样——miss 形态当场可判。

### 验证

- 单测：MessageMergeEngineTest 两条去重钉子改写为新语义（重复照常追加）；CompletionHandoffGateTest +3（中段洞命中/中段多余命中/首部脏不命中），原「中段分叉不命中」语义收窄为「全文异构不命中」（分叉带宽破坏后缀锚）。全量绿。
- 真机 E2E（turn 32，22:12）：①流式累积==终态 **4658 逐字节相等**，本轮含 **11 个「delta==尾部」真重复**（连续空格/数字重复位——旧代码会把 100 吃成 10，潜伏数字/空格腐蚀实锤）全部保留；②换装帧 `src=asyncInline`（=asyncTerminal 未创建=桥命中）+`h=11866 d=11866` 首测全高，零 forensic miss（22:10:27 的 4 条 miss 为冷启历史渲染槽空，正常）。

### 教训

- 「宁缺勿错配」的门设计前提（内容恒取 markdown 参数）使容错方向安全化——先问「错配后果是什么」再定门的松紧。
- fuzzy 去重（endsWith）对纯追加流是纯害：每次「恰好重复」都是误杀。判定重复投递需要序号/身份，内容形状判定必错。


## ## 13. #506 思考卡计时拖满全程 + 展开内容困在 240dp 隐形滚动窗（2026-10-02 深夜二段）

### 双症状定罪（用户报告，真机 t33/t34 复现）

**① 计时不随思考结束停下**：WS 抓包（t32）铁证——DSH 把 reasoning 的 block-end **压到整流结束才发**（思考内容 22:10:56 完，block-end 22:12:03.9 才到，与 text block-end 同毫秒），#453 的 TimePatch 机制没错但补丁迟到 67s → time.end 缺席 → `isReasoningStreaming(partEnded=false ∧ hasValidAnchor=true)` 恒真 → 计时拖满正文流式全程。

**② 展开内容展示不全**：内容数据完整可达（t34 卡滚到底「结语」与末行俱在——首查被 dump 正则长度过滤误导以为截断），但锁在 240dp 内滚窗：无滚动条提示、流式不跟随、末行切半贴文章标题——用户感知「展示不全」。

### 修复

1. **前驱终态补丁**（DshEventMapper.mapChunk）：block-start(N) 顺手发 TimePatch(ordinal=N-1, endMs=块启动时刻)。依据：DSH 块严格顺序（t31/t32 抓包零交错实证）⇒ 后继启动即前驱完成；TimePatch 端 end==null first-write-wins，晚到的真实 block-end 自然让位；首块（N=0）无前缀不发。链式归纳覆盖全部块（每块由其后继终态化，末块由自身 block-end）。
2. **展开全高**（ReasoningBlock）：撤 240dp heightIn + verticalScroll + clipToBounds——展开动作=「看全部」显式意图，直接给全内容高度；supersede 2026-08-16 240dp 裁决（同域最新用户投诉）。副产品：消灭卡内嵌套滚动容器（fling 泄漏面）与流式跟随问题。

### 验证（真机 t35）

- 单测：DshEventMapperTest +2（block-start(N) 发前驱补丁/首块不发）；全量绿。
- 计时：logcat 铁证——TimePatch 于 22:40:52.343 **流中**（正文刚起步）dispatch + B2-struct publish（changed=true 语义）= reasoning part 即时终态化 → partEnded → tick 静态化。
- 展示：t35 卡展开 dump——768 字 reasoning 自首段（User wants a plan first…）至末行（Brief plan then article…）一整块连续呈现，末行可见零滚动。

### 残余（登记未实施）

完结换代（删 dsh-t{N}s1 合成消息 + 上权威 seq 消息，不同 message id 不走 mergePart）后思考卡时长消失：转写块无 time 字段（实测 message.content 仅 type/text），跨消息时间迁移需 temporal join，用户未报、留待裁决。


## ## 14. 三卡根修批次：#503 分片回卷根修 + #504 深层身份连续 + #506 时长残余（2026-10-02 深夜三段，用户裁决「三者都根修+回归」）

### #503 根修：回退宽限 + fire 事务性（StreamingSplitMachine/Broker/Pilot）

回卷闭环（Explore agent 机制地图定罪）：Fire → 帽 hardReset+shards 写+resetKey++/sliceOrigin 推进 → chatEntries 重算插 #g 键 → LazyColumn 重排驱逐 Turn item → unreg→reg → pilot 冷启（coldStartOrigin+coldStartPlan adopt）→ 尾段再攒 2000ch → Arm→Fire 循环；**回滚环**（真机 9 fire/9s 的直接形态）：冷启重组窗的瞬时陈旧快照（总线让位回退）短于 plan.tailFrom → Machine.Reset 单批即判死（无宽限）→ onRebuild 销毁已发布集 + origin 归零 → 重播已发布区间 → 再毕业再 fire。

两处根修（探查判定的最薄弱环节）：
1. **R1 回退宽限窗**（SHARD_RESET_GRACE_MS=300，与 pilot 非前缀宽限同语义）：瞬时回退 None 等待恢复；持续超宽限才 Reset（真重生成）。nowMs 注入保持纯函数可测。
2. **R2 fire 事务性**：旧实现 Machine 在发布**前**推进 plan（fire 对未注册 no-op 时账本与 broker 脱钩=内容缺段）；现 Fire 携带 pendingFire 返回、发布成功（Broker.fire 回 Boolean）后接线层 confirmFire() 落账，失败下批重试同计划。附带消除旧「空 publish 重发旧计划」的无效 churn（fired≠armedOrigin 时解除武装而非 Fire）。

真机 t36（6000 字长文，dev 旗标重开）：**三次毕业 origin 严格递增（2050→4177→6185）chunks append-only 递增、零 Reset、零 dropped、shard-reg=1/unreg=0**——对比修复前同 origin 回卷 9 fire/9s。回卷消灭。

### #504 深层根修：part 组合键归一化（PartIdContract.swapStableKey）

架构事实：消息级 t_ 键已有 #440 槽位锚（锚 user 消息 id，换装稳定）——断层只在 part 级 key(part.id)（dsh-tXs1_text_ord_1 → seq-…_text_ord_1 前缀换代，kind/ordinal 编号域两侧同一=mapper 契约）。根修=派生 id 取 kind+ordinal 后缀为消息卡内组合键（非派生 id 原样），子树跨换代存活——#472 holdPilotTerminal 机制在 DSH 上首次可用（pilotEverRendered 不再死），#504 指纹桥降为第二道防线。红线：仅限消息卡内兄弟作用域（跨消息 Map 键不得用——会碰撞）；数据层（merge/Room/bus/registry）全 id 不变。

### #506 残余根修：mapper 块时刻记账（真块时长）

定罪修正：权威 part 其实带 time 但 start=end=完结事件时刻（时长恒 0）——非「转写无 time」。DSH block-end 帧时间戳=流尾投递时刻非真实完成（不能用作块结束）。根修：blockStartTimes/blockEndTimes 记账（block-start(N) 记 N 起始 + 推 N-1 结束——与 #506 前驱终态补丁同推断），整装结算读后删、历史重放回退事件时刻（现状语义）。

### 真机 E2E（t36，7440 字 + 分片开启 + 全链根修包）

- 换装帧 `src=asyncInline`（桥命中同步解析）+ `MDResize h=17554 d=14602 首测即全高`——零 200px 桩零回弹零 forensic miss。
- 思考卡完结后显示 **5.2s 真时长**（与 text 块启动时刻吻合；修复前恒空）。
- 分片完结形态记录：h 2952→17554 单帧增长=冻结条目被权威全文吸收（shardPartIdx 按 partId 查询 miss → 条目退场），贴底配对吸收——观测无异常。

### 回归

- 全量单测绿×2（旗标改后分片测试臂真正执行）；新增：Machine 宽限/事务性 3 用例、Broker fire 回执 3 处断言、swapStableKey 4 用例、整装块时长 2 用例。
- STREAM_SHARD_PILOT dev=true（根修已验证，交用户自然使用验收）；beta/stable 保持 false。


## ## 15. #507 流式毕业内容消失——turnGroups 结构缓存空 parts 阻断 #g 条目发射（2026-10-03 凌晨，diagnosing-bugs 技能全流程）

### 症状（用户报告）

流式输出过程中所有内容突然没掉、然后又突然正常输出，前文流式输出全部消失不见；卡片展开收起不稳定。

### 反馈回路（Phase 1-2）

- /tmp/vanish_loop.sh：长文轮 → logcat → 按 fire 时刻断言条目 churn（MM-DD 日期正则踩坑一轮）；RED 2/2 确定性。
- fire_snap.sh：fire 触发连拍——**消失是持续态**（6 帧 2.5s 流式区文字覆盖仅 8%）。
- 语义树 dump（fire+2.5s）：全树 15 节点，当前轮 2000+ 字内容零存在；上滚/下滚均找不到——排除视口跳变与 0 高渲染。
- mp4 录屏（分辨率/码率/pkill 三轮坑）+ zai 视频工具 400 失败——连拍+像素覆盖+语义树三件套替代定案。

### 根因（Phase 3-4：vg/vg2 两轮探针）

- fire 后尾卡塌至 96px（d=-4970）且 **StreamShardContent/StreamPrefix 从未组合**（507-shard 前身探针 0 次）。
- vg2 探针（含 msg.id 轮）定罪：`turnShards=false msg=dsh-t47s1 grpN=1 groupParts=`（空）——流式宿主在 turnGroups 里的 parts 恒空。
- 机制：turnGroups 是**结构缓存**（id 生命周期签名，ChatMessageList:324-343「内容（parts）变化不重建 Map」）——流式宿主 ChatMessage 捕获于消息创建时刻，parts 尚空（part 出生在后续 delta 批，签名不变）→ #g 条目生成的发布查找走 cm.parts 恒 miss → 冻结条目零发射 → 毕业内容无处渲染。渲染管道（renderableTurns miss 分支修正陈旧引用）看得见 parts——**两管道视野分裂**，B案时期无人读结构管道的流式 parts 故隐形。
- 尾块切片（shardPartIdx，走 renderableTurns）正常发生——切片了却没发射，用户看到「只剩尾巴在输出」。

### 修复（Phase 5）

发布查找改 **turnKey 直查**（PublishedShards.turnKey 与条目键同源，注册期写入），组遍历降兜底——绕开整类 parts 引用陈旧性。回归测试 StreamShardEntryEmissionTest：结构缓存空 parts 场景**红→绿**；全量单测绿×2。

### 真机终验

毕业①`507-shard len=2179 h=5065px`、毕业②`len=2093 h=4796px`——冻结条目全高组合；fire+1.2s 截图视觉裁决：满屏连续正文（3PC 章节多段完整），零空白零缺失。

### 教训

- 结构缓存（按 id 签名）与内容引用新鲜度是两个正交维度——「缓存不重建但引用会被修正」的契约只对走修正分支的管道成立；新消费方（A2 条目生成）读缓存原始引用=踩陈旧地雷。
- 身份直查（turnKey）优于遍历匹配（parts）——发布方自带稳定身份时，永远用身份找。

