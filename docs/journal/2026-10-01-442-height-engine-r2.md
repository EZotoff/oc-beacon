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
