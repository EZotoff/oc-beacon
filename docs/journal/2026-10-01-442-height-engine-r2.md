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
