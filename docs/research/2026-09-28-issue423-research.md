# #423 PreRenderCoordinator 集中式渲染前计算模块 — 深度调研报告（2026-09-28）

> **一句话结论**：#423 的「集中式渲染前计算」愿景只以「视口租约 + FLUSH 单点」两个原语落地
> （PreRenderCoordinator.kt 全文 151 行，自批次二后未再改动）；真正的配对/钉位/预热机器长在了
> CardExpandReveal 引擎与 StreamingGrowLedger 里，并经 #435 统一化实现了大部分 I1-I5 不变量的
> **实质**——当前残余不是「再建协调器」，而是三件收尾：修 #434 贴底收起 0 消费裸位移（P0）、
> 做 #425 预热队列化、清引擎死代码并把「多模块单写者」从约定升级为强制。

**调研范围与性质**：只读调研，唯一写入=本报告。一手来源：backlog 卡片与历史 note、
docs/journal 批次记录、docs/research 既有调研（含两份必读可行性调研）、AGENTS.md 铁律、
当前源码与测试、git 历史。所有论断附 文件:行号。

---

## 1. 问题陈述

### 1.1 用户可感知症状（历年主诉聚合，均来自真机取证）

- **视口位移族**：展开/收起思考卡、步骤组卡、工具卡时上方内容被顶起——贴底/中位展开
  实测 +644px、收起 −608px，峰值 220px/帧（CardExpandReveal.kt:48-50）；
  收起后「整体对话向上移动一小段」（每展开-收起周期净漂 −268px，
  CardExpandReveal.kt:161-167 注释存档定罪）。
- **闪现族**：展开瞬间「跳动→恢复原位→内容往下展开」（= 基线红环4 的 +198px 落地瞬态，
  docs/research/pre-render-coordinator/07-baseline-freeze.md:16）；展开时「其他元素移动」
  + 整屏白屏窗 ~150ms（批次十二定罪 animateItem 弹簧，journal:229-241）。
- **振荡族**：快速连点方向来回顶（err +80/−80 交替，锚在 1178↔1226 翻转，journal:144）；
  收起尾部「上推 1~3 帧后高度复位」（backlog.md:150，#428 已修的 Loading 短高占位）。
- **卡顿族**：大内容卡（20k px 表格）收起秒级延迟 + 集末 ±507px 闪跳（journal:327-333）；
  展开 3~5s 等待 + 白屏揭示（backlog.md:126，#429）；滚动停止后 ~880ms 空闲停顿
  （多卡同帧预热风暴，journal:310-313）。

### 1.2 触发条件

任意历史（已完结）卡片 toggle，覆盖三个维度矩阵：
视口态（贴底 / 中位 / 读历史）× 卡族（思考卡 / 小步骤组 / 大步骤组）×
打断方式（快速连点 / 滚动中收起 / 流式期并发 toggle）。

### 1.3 根因族（一句话版）

reverseLayout 下 LazyList 把 item 高度变化全额转译为视口位移；而补偿通道
（dispatchRawDelta / 反射定位）与守卫重锚、锚底 effect、流式 flush 等多个视口写者
共享同一视口互搏——「十一轮竞态」的总纲（backlog.md:157；04-internal-inventory.md:191）。

---

## 2. 已知历史（取证/定罪/部分修复，逐条带引用）

### 2.1 前史：两次否决与一次复活（理解 #423 的裁决链前提）

1. **2026-08-25 可行性调研否决过一轮**（docs/research/2026-08-25-card-height-precompute-feasibility.md）：
   - 判定「预先获取高度几乎无用武之地」——三类「出现」场景或已被 COMP-MSG 单遍覆盖、
     或在 key 锚定几何下零位移（:12-16,判定表 :21-28）。
   - toggle 补偿被当时用户终版裁决明确排除：「不要有补偿逻辑！直接用 M3 属性的动画」
     （:145-147，#215 journal 存档）。
   - 同文 §1.5（:81-97）发现反射通道「测量回写竞争」未闭合缝——静态推演找不到
     「SSE 流式注入为何不被回写覆盖」的结构性原因。**后续批次九/十一的
     「同遍 measure 原子写入」模式正是对此缝的实践解答。**
2. **2026-08-30 #262 PreRenderExpand 状态机建成即被弃**：
   447 行渲染前计算状态机（PreRenderExpandMachine：IDLE/MEASURING/REVEALING/EXPANDED/CLOSING
   + tap 预移两路径 + finalH 缓存，journal 2026-08-30-expand-prerender.md:18-22），
   二轮用户裁决「撤销一切展开补偿」全拆 -597 行（:26-30）。
   **该架构 2026-09-22 批次十一被 git 考古复活（git show 722e20d3）并成为现行主干。**
3. **2026-09-20 #420 新用户报告覆盖旧裁决**：闪烁跳动再报，CardExpandReveal
   「单一时钟同帧配对」引擎落地并真机验收（journal 2026-09-20-420-421:14-22；
   历史对照段 :17 明言「旧裁决由本日新诉求覆盖（最新裁决为准）」）。
   #420-#431 十一轮竞态收口即 #423 卡片所称「十一轮竞态根因收拢」的实体
   （docs/research/pre-render-coordinator/04-internal-inventory.md:191）。

### 2.2 #423 立卡与 spec v1（2026-09-21/22）

- 卡片目标：集中式渲染前计算/视口配对模块——意图层并行声明 + 底层单写者串行帧事务
  （backlog.md:157）；用户五项裁决 D1-D5（全量收编/统一动画契约/流式入队/每步人工验收/
  反射炸弹 Phase0 护栏）与五不变量 I1-I5（位置钉死/同帧闭合/视口租约/用户优先/单写者串行）
  落册于 spec（backlog.md:158-159；docs/specs/2026-09-21-pre-render-coordinator-design.md:8-22）。
- 六路调研合成 K1-K12（docs/research/pre-render-coordinator/00-synthesis.md:10-22），要点：
  - K1 单一 flush 点=布局后绘制前（四源一致）；K2 单写者=结构化排斥非 OS 锁；
    K3 MutatorMutex 是公开 API；K4 帧事务 withMutableSnapshot 一帧一 apply；
    K5 快照系统不保证跨协程串行→单写者须上层协议强制（负面结论防走弯路）；
    K8 渲染前改滚动位无公开直通 API；K9 反射活在 value-class unboxed 巧合上=定时炸弹。
- P0 八环红环基线冻结（07-baseline-freeze.md）：红环4 RED（展开落地 +198px 瞬态
  =用户否决的「闪现一下」）留档为 Phase1 主体消灭对象（:12-27）；现场2 冻结七项行为基线
  （快速反向 toggle 重定向/滚动取消 snap/冷组合不重播/流式降级裸 AV/回收重入 settle/
  守卫去抖/GUARD 让位条件，:29-39）。

### 2.3 实施主线：journal 2026-09-22-423-prerendercoordinator.md（14+ 批次，方向两次大转向）

| 批次 | 内容与结果 | 引用 |
|---|---|---|
| 一 | P0 红环固化+Phase0 反射护栏+Phase1a 视口租约（withEpisode+五点让位，单测+8 全绿） | journal:12-25；commit 26a94b3f/fb3478f6/f6a4a314 |
| 评审 | Standards 阻断项（MSGEFFECT 复查漏租约）修复；reanchor 六参 Data Clumps 登记待引擎迁入收编 | journal:41-51；backlog.md:163 |
| 二 | 展开闪现根修：pre-pair 同遍合并+FLUSH 拒绘单点；802ms 未配对窗口→7ms，topY 逸出 1→0 | journal:56-73；commit 634a3e11 |
| 三 | 步骤组顶开根修：FLUSH 实测钉位；预测配对退役（LEAP+回收双雷实证）；折叠行 1100→1100 | journal:78-94；commit 805c0e84 |
| 五~八 | 钉位超时改 hold 起算→结构裂变→REPIN 单发实测重锚→思考卡同法迁移（用户逐帧复检驱动） | journal:96-150 |
| 九 | **用户裁决换架构**：「统一高度控制模块，渲染前计算好+反射逐帧设置」——滚动位与高度分数同一遍 measure 原子生效；轮 3 三卡族三通道全绿 | journal:152-186 |
| 十~十一 | 门控增长被否（滞后系统只可门控）→ git 考古复活 #262「渲染前计算状态机」+四轮死因修复；终验「顶部钉死、下方展开、零补偿环」 | journal:188-208 |
| 十二 | animateItem placementSpec 弹簧定罪（±504px 白屏震荡）→ placementSpec=null 全局退役 | journal:214-275 |
| 十三系 | 结构裂变全量退役→统一引擎+空闲预热；收起帧饥饿双修；收起震荡定罪；**批次十四收起镜像化**（幕布收拢+单步原子闭合，旧逐帧收起整体退役） | journal:277-405 |
| 追加 | #427 切片器+片高账本+窗口化（:407-434）；收起残差重试+锚点恢复（:436-461）；反射定位根修闪烁（:463-480）；三症状竞态修复（:482-508）；#428 同步解析+LRU（:549-564）；#429 分批表格（:566-570） | 同 journal |

关键转折的教训链（对方案空间有直接约束力）：
- **补偿族两次被用户否决**（2026-08-25 #215；批次二 M2 讨论中再确认）——一切「渲染后测量再补」
  方案出局，修复必须落在渲染前相位（journal:169-171,465-471 的架构归位论述）。
- **scrollToItem 独立排布通道=闪烁源**（塌缩先渲染一帧再跳位）→ 反射 requestScrollToItemNoCancel
  待定位与高度塌缩同遍 measure 原子消费（journal:463-480，commit 7785cfbf）。
- **单发配对不够**：跨锚点测量竞态欠消费残差泄漏（−268px/周期）→ 配对派发残差重试至全额
  （CardExpandReveal.kt:161-184；journal:436-461，commit c5ddfbbf）。

### 2.4 #435 高度引擎统一化（spec Phase 3 以另一路径实现）

- 用户裁决「所有高度相关处理统一到高度引擎」（backlog.md:96-97）。
- 落地：StreamingGrowLedger 取代 DeferredRevealCompensator+PreRenderShiftChannel（两文件已删除，
  components/ 目录实证），流式增长配对走 PreRenderCoordinator pre-draw flush 单点，
  「锚即意图」统一规则 pair(Δ)⟺anchor==item∧fiso>0（ScrollCompensation.kt:20-51）。
  commit af428686（2026-09-25/26），终判三构型真机全绿（backlog.md:100）。
- #437 增补：HeightReserve 一帧缓冲帽（ScrollCompensation.kt:264-318）+
  ViewportDispatchGateway 视口写入单点派发网关（观测性收口，ViewportDispatchGateway.kt:7-35）+
  R1-A2 帽/ledger 同帧单出口合并、StreamingAnchorRule 统一谓词（git log ScrollCompensation：
  300db984/d28360d8/d1e99dc7/a7daa1b0 等）。
- #438① gate 壁钟限速落地（append max 2087→200，backlog.md:91）。

### 2.5 最新实证（2026-09-27）：episode × 流式 flush 并发安全裁决

「episode ΔH_card 与 ledger ΔH_stream 同帧双发」实测不成立，三层机制：
①空间分离（组合窗口天然分区）②单通道吸收（流式 item 内 toggle 走 SGR ledger 不产生 episode）
③单点串行（过渡区共享 PreRenderCoordinator flush 单点）——**不需要改生产代码**
（docs/journal/2026-09-27-card-intervention-growth-phase.md:28-63）。

---

## 3. 现状代码走读

### 3.1 PreRenderCoordinator（协调器本体，151 行，自批次二后零改动——git log 仅三笔）

- **视口租约（I3）**：`activeCount`/`hasActiveTransactions`（PreRenderCoordinator.kt:62-67，
  快照 int state 使让位方 snapshotFlow 读它即订阅）；`withEpisode` try/finally 全程持约，
  取消/异常必释放（:74-83）；KDoc 明示 registerSwap/registerStreaming「按 spec Phase 2/3 接入」
  **至今未接线**（:33-34）。
- **FLUSH 相（K1）**：宿主单点 OnPreDrawListener 由 ChatMessageList 组合期挂接
  （ChatMessageList.kt:665-672）；任务表注册序排干、任一任务返回 false 则本帧拒绘并请求重排
  （PreRenderCoordinator.kt:88,116-136）；无宿主/无任务零监听零开销；降级路径=组件回退
  onGloballyPositioned+帧兜底（:41-44）。`FLUSH_PHASE_ENABLED` 观测期开关留存恒 true（:55）。
- 单例=每列表语义（应用同时仅一个会话列表活跃），多列表需组合局部化改造（KDoc :38-39 备案）。
- **测试锚**：PreRenderCoordinatorTest 6 例——租约持有/嵌套计数/异常释放/取消释放/
  注册序拒绘传播/拒绘者注销后放行（PreRenderCoordinatorTest.kt:22,31,42,48,60,82）。

### 3.2 CardExpandReveal（引擎，1715 行——事实上的「渲染前计算」机器）

- **episode 主循环 `:725-1023`** 整体包在 `withEpisode` 内（:729；:1023 留「缩进未重排：
  引擎迁入时整体重构」债务注释）：
  - 展开：warmup → settle（判稳 2 帧/上限 600ms）→ `withMutableSnapshot driveTo(1f)`
    布局终态同步落地（:776）→ `applyPairedPreRenderShift(+H)` 配对到全额 →
    欠账 rebase `steadyRebaseAfterEpisodeDispatch`（:822）→ 纯绘制幕布 200ms。
  - 收起：draw-only 幕布收拢（零布局零测量）→ 反射 `requestScrollToItemNoCancel`
    恢复展开前锚点与快照塌缩**同遍 measure 原子生效**（:886-912）；锚点未知且非用户滚动
    → 镜像位移派发兜底。
- **配对执行器三件**：`applyPreRenderShift`（:148-159；反射 request-position 批次十一校准后
  仅留负向不可消费域，展开正向恒走 dispatchRawDelta）；`PairedDispatch` 残差重试纯函数
  （:173-184，MAX_TRIES=4、亚像素 0.5f 量化、0 消费=物理边缘终止）；
  `applyPairedPreRenderShift` 全额执行器（:190-213）。
- **空闲预热**：`PREWARM_IDLE_MS=1200`（:136）；`:1033-1047` 可见且静止后 ε 高度组合+沉降，
  让位等待（clock.animating ∥ hasActiveTransactions 有界 250ms×8）+资格门 prewarmEligible
  （fac75665：切片且账本暖则禁）。
- **FLUSH 任务两枚**：稳态配对任务（#430 稳态账本：measure 记账 Δreport→pre-draw 派发 +Δ，
  注册点 :1129）与 FLUSH 实测钉位修正器（批次三产物，注册点 :1349）。
- 消费方 11 处卡片 drop-in 替换 AnimatedVisibility
  （docs/research/433-height-engine-map.md:134-135 列全）。

### 3.3 ScrollCompensation（流式侧，#435/#437 产物，665 行）

- `StreamingGrowLedger`（:126，per-entryKey 基线记账）+ `StreamingAnchorRule` 统一配对谓词
  （:80，「锚即意图」决策表）+ `shouldYieldPairing` 显式意图让位（:108，位置神圣）+
  `streamingGrowFlushTask`（:362-417+，含 HeightReserve 帽 plan 与 ledger 配对的
  [R1-A2] 同帧单出口合并）在 ChatMessageList 常驻注册（ChatMessageList.kt:674-683）。
- `HeightReserveState` 一帧缓冲帽（:264-318，增量当帧裁掉不可见、flush 相单事务释放）。
- `LazyListReflection` 反射对象（:620）——requestScrollToItemNoCancel 待定位写+
  measurementScopeInvalidator poke；`STREAM_FLUSH_INTERVAL_MS=100` cadence 收编引擎域（:78）。

### 3.4 让位接线（租约消费方全景）

ChatScrollController 五点：MSGEFFECT 入口/fling 复查（ChatScrollController.kt:176,197）、
GUARD 入口/去抖复查（:230,246）、ForceScroll 有界等待 LEASE_WAIT_MS=3s（:276,366-403）、
PENDING 拉底（:295）；ChatMessageList 横幅锚底入口/复查（:717-727）；
引擎预热让位（CardExpandReveal.kt:1041-1047）。

### 3.5 与既有调研的分歧（如实标注）

- **433-height-engine-map.md §4（:98-105）已过时**：其断言「流式文本 item 增长引擎完全不管……
  由 PreRenderShiftChannel 承担」——该地图快照于 #435 落地前后（其载 CardExpandReveal 1701 行
  vs 现状 1715 行），PreRenderShiftChannel.kt/DeferredRevealCompensator 现已删除
  （components/ 目录现存文件实证），流式增长现走 ledger+flush 单点。**引用该图 §4 须以本报告
  §3.3 为准。**
- 2026-08-25 调研的「toggle 补偿被排除」「预计算无用」结论被 2026-09-20（#420 新裁决）与
  批次九/十一（渲染前计算主干化）**先后覆盖**——同域多项裁决以最新为准（AGENTS.md 纪律）。
- 07-baseline-freeze.md 现场1 红环4 RED（+198px 瞬态）在批次二 pre-pair+FLUSH 后消灭
  （journal:67-68 实测对照）——基线文档描述的是 Phase 0 前状态，读时注意时效。

---

## 4. 根因分析（残余未解链，附证据与置信度）

**R-A（P0，直接引擎缺口）贴底收起 0 消费裸位移**
贴底构型（fii=0,fiso=0）收起镜像 dispatch −H 在新侧无空间，consumed=0（paired-shift 日志实锤），
塌缩无补偿 → 上方旧内容裸下移 H px（542px 录屏帧 94→101 判读）；用户流式场景常处贴底=高频触发
（backlog.md:61-62，#434）。修法候选（PairedDispatch 方向 fallback：consumed==0 且贴底时换向
dispatch +H）已写入卡片待真机验证。**置信度：高**（日志+录屏双证）。

**R-B 预热风暴无队列**
滚动停止后多卡同帧预热，实测 Skipped 53 帧 ~880ms 空闲停顿（journal:310-313）；
#425 卡片明示「方案:PreRenderCoordinator 队列化,一卡一窗串行预热」（backlog.md:194-196）——
**这是唯一仍以 PreRenderCoordinator 为修复主体的在册卡**。既有预热资格门（fac75665）只挡
怪物片重入，不挡多卡并发。**置信度：高**。

**R-C 成本维根因未竟**
组合/测量主线程铁律不可后台（2026-09-24-compose-background-compute-feasibility.md:17,24：
官方无 API，能后台的只有解析/文本预测）。切片/分批/列宽缓存已砍大头（#427/#429/#431），
残余：超大卡闭合单次重测 ~0.5-0.8s（journal:401-405）+ P4 AST/表行级分片未立项
（journal:429；backlog.md:186-192）。**置信度：高（存在性）/中（数值随版本漂移）**。

**R-D 单写者（I5）是约定不是强制**
视口写入族仍有五族在 ViewportDispatchGateway 网关外（引擎配对/episode/跳转族/snapToBottom/
ScrollIsland，ViewportDispatchGateway.kt:22-31 显式登记豁免表，强制化「为后续批次」）；
spec §2 的 MutatorMutex 意图层+帧事务未建（vs PreRenderCoordinator.kt:33-34 未接线）。
三层机制实测安全（journal 2026-09-27:56-63），但该安全性依赖「单列表+主线程+注册序」隐式协议。
**置信度：高（结构事实）/中（协议被未来写入者破坏的风险）**。
**待验证**：双列表同帧（分屏/内嵌列表）场景当前不存在——验证方法：出现第二列表前在
Gateway 增加 DEBUG 断言计数旁路写入，跑全量回归观察是否恒 0。

**R-E 引擎内死代码与注释-状态张力**
drainPhaseA 仅降级路径可达（`!isFlushHostAttached`）；closedLoopCommand/dispatchClosedLoop/
easedFraction/episodeEndCorrection 等退役存量；pin 修正器仍活跃注册而 :741-743 注释称
「钉位武装/FLUSH 修正环退役」；pinPending 置位入口存疑；FLUSH_PHASE_ENABLED 观测期未清
（433-height-engine-map.md:142-152；本仓 grep 证实 :1129/:1349 注册点存活）。
**置信度：高（存在）/中（pin 修正器实际可达性）**。**待验证**：真机 logcat 过滤 pin 修正器
标签跑 toggle 三态矩阵，确认是否仍发射（决定「删除」还是「复活注释」）。

**R-F 反射依赖长尾**
LazyListReflection 活在 value-class unboxed 表示巧合上（K9；06-height-mutation-api-boundaries.md
§3.4 巧合存续/§5 R1-R6 风险清单）。Phase0 已有探测降级+BOM 冒烟单测（journal:16-18；commit
26a94b3f），但每次 BOM 升级仍需 javap 字节码核销（spec §8 红线）。
**置信度：高（护栏在位）/低（近期触发概率，BOM 2026.08.00 已核）**。

**R-G 统一化自身回归**
#444 fling 下滑跳变复发，嫌疑 R1-A2 单出口合并后配对 set 行为变化或键保持通道在新路径退化
（backlog.md:276-278）——#437↔#444 关联（修复链互为因果）。**置信度：低（用户报告属实，
根因未定）**。**待验证**：复跑 2026-09-25-437 journal 验收十轮判据（R9 场景），二分定位
回归 commit（历史修复链 817607b4/b54650c3/f09eb29b 供溯源）。

**小结**：#423 原设想的「根因」（多写者竞态）已被租约+FLUSH+统一 ledger 实质收敛；
残余根因是**边角构型缺口（R-A）、资源调度缺失（R-B）、成本长尾（R-C）与结构债（R-D/E/F）**。

---

## 5. 修复方案空间

### 方案 A「收尾收编」（保守：把 #423 按实况关账）

- **内容**：
  1. #434 修法落地——PairedDispatch 增方向 fallback（consumed==0 ∧ 贴底 ⇒ 换向 +H），
     真机验证方向+防双发（backlog.md:62 候选）。
  2. #425 预热队列化——PreRenderCoordinator 增 prewarm 队列原语：一卡一空闲窗串行，
     复用既有 `hasActiveTransactions` 等待环（CardExpandReveal.kt:1041-1047 同款语义上提）。
  3. R-E 死代码清理（drainPhaseA 降级路径评估去留、退役函数删除、pin 修正器验证后定夺、
     FLUSH_PHASE_ENABLED 摘除）+ #426 裂变残留一并清。
  4. 文档对齐：433 地图 §4 勘误、引擎 KDoc 状态注、spec registerStreaming/registerStreaming
     承诺勘误（由 #435 ledger 承担）。
- **影响面**：PreRenderCoordinator +40~80 行；CardExpandReveal 净删 ~100-200 行；
  零 SSE 域改动。**风险：低**——全部有既有红环/单测锚。
- **与铁律/既有能力兼容性**：完全兼容（不动 SSE 管线任何一环；预热队列只影响空闲窗调度）。
- **TDD 切入**：
  - 先写 `PairedDispatchTest` 贴底 0 消费换向用例，钉死「consumed==0 ∧ 贴底 ⇒ 反向指令、
    其余构型行为逐字节不变」不变量；
  - prewarm 队列假时钟 JVM 测试钉「任意时刻至多 1 卡在预热」+「集进行期队列全让位」；
  - 死代码删除靠全量套件 + 红环 1/4 复绿兜底。

### 方案 B「单写者强制」（中间：把 I5 从约定升为构造）

- **内容**：ViewportDispatchGateway 从观测网关升级为唯一执行器——五族豁免写入全部改经
  网关事务 API（吸收 CardExpandReveal 的 dispatch/反射调用与 ledger flush 为网关内部实现）；
  registerSwap/registerStreaming 正名或显式删除；锚=(itemKey,offset) 标准化入协调器。
- **影响面**：CardExpandReveal/ScrollCompensation/JumpNavigation 接口改造 ~300-500 行；
  **风险：中**——触及跳转族=用户显式意图铁律，须逐族红环。
- **兼容性**：与铁律 4「锚即意图」语义无冲突；Phase 3 域（流式）逐条独立红环（spec §8 纪律）。
- **TDD 切入**：先为网关写「网关外写入检测」测试（封装 LazyListState 派发口，任何旁路在
  DEBUG 构建断言失败），再逐族迁移，每族一个红环。

### 方案 C「PreRenderCoordinator 2.0 完全重写」（激进选项：模块重构/重写）

- **内容**：按卡片标题字面重构为真正的**渲染前计算流水线**：
  1. HeightOracle 统一预计算服务（键=(contentKey,width)）——聚合既有 finalH 缓存/
     StepGroupHeightLedger（#427）/MarkdownParsedStateCache（#428），新增 PrecomputedText
     列宽行高预测（官方背书可后台路径，2026-09-24 调研 §七:104-106）；
  2. 引擎 episode 前查 Oracle，Phase A 由「settle 实测」变「查表纯布局」；
  3. MutatorMutex 意图层/帧事务/锚模型按 spec §2 全量落地，
     CardExpandReveal（1715 行）与 ScrollCompensation（665 行）重写收拢进协调器域。
- **影响面**：~2000+ 行重写、11 消费卡全触、八环红环全量重建；**风险：高**——journal 14 批次
  记录的每个雷区（帧界错位/锚点重推导泄露/测量回写竞争/弹簧劫持/预热风暴/组合期 runBlocking
  死锁禁用族 journal:532）都可能复现。
- **兼容性**：SSE 域必须逐铁律迁移（spec §8「Phase 3 前一行不改」教训）；K5（快照不保证跨协程
  串行）是协议层硬约束；组合/测量不可后台（Q1）限定 Oracle 只能装「解析+文本预测」，
  组合成本仍需 #427 窗口化体系。
- **TDD 切入**：先以 JVM 假帧钟实现 I1-I5 五不变量的纯事务引擎测试（拒绘/配对/让位/取消/串行
  各至少一例），红环 1-8 全绿后才允许替换生产路径；Oracle 以「预测高 vs 实测高偏差 ≤ 阈值」
  的属性测试钉准确性（错补比不补更可见——2026-08-25 调研 §3.1:158 的准绳）。

---

## 6. 建议

**推荐方案 A**（工作量：#434=S，#425 队列=S，清理+文档=S，合计 **M**）。理由：

1. **实质目标已达成**：I1/I2（位置钉死/同帧闭合）由「渲染前计算+同遍原子写入+幕布纯绘制+
   单步闭合」实现（journal 批次九轮 3/批次十一/批次十四终验：视频 dy=0、topY 逐帧恒定、
   锚点精确归零）；I3 租约与 FLUSH 单点已装机并有单测锚；I4 位置神圣贯穿（取消 snap/
   显式意图让位/用户滚动弃配）；唯一欠账 I5 是约定级（R-D），而其破坏场景当前不存在。
2. **方案 C 的边际收益集中在代码形态与未来防御**，但 2026-09-27 实测（journal:56-63）证明
   现协议在真实并发下安全；重写会把已验收的稳定域重新推入验证战争，且 HeightOracle 的
   预测失准本身就是新风险源——违背「宁可少补不可错补」准绳。
3. **升级路径保留**：若 R-D/R-G 恶化（#444 定罪为单出口合并缺陷、或出现第二列表场景），
   再升方案 B——它是 A 的自然延伸而非推翻；#442（R2 分片增量化+flush 深拆）本就是 B 的子集。

**关联卡片注明**（供排期参考）：
- #437↔#444：统一化修复链疑似自回归（R-G，待定罪）。
- #435=#423 Phase 3 的实际载体：registerStreaming 虽未接线，功能已由 ledger+flush 承担，
  spec 承诺应勘误而非补实现。
- #424/#425 是 #423 域内拆票（解析预取/组合预热两层，backlog.md:199-200 自述依赖衔接）。
- #427/#429/#431 为成本根因链（R-C）；#426 与 R-E 同批清理。
- #434 为当前唯一 P0 级引擎缺陷（R-A）；#433（滚动死锁）若复现可能落在引擎域（backlog.md:64-65）。

---

## 7. 引用清单

**backlog**（backlog.md）：
:156-165（#423 卡片与批次 note）· :61-62（#434）· :64-65（#433）· :96-100（#435）·
:102-103（#432）· :105-117（#431）· :119-123（#430）· :125-144（#429）· :146-154（#428）·
:177-180（#442）· :182-184（#439）· :186-192（#427）· :194-196（#425）· :198-200（#424）·
:202-218（#422）· :269-274（#445）· :276-278（#444）· :283-284（#426）· :87-94（#437/#438）

**journal**：
- docs/journal/2026-09-22-423-prerendercoordinator.md（#423 主 journal，批次一~十四+
  #427/#428/#429 追加批次，行号见 §2.3 表）
- docs/journal/2026-09-27-card-intervention-growth-phase.md:28-63（并发裁决三层机制）
- docs/journal/2026-08-30-expand-prerender.md:18-30（#262 建成与弃用）
- docs/journal/2026-09-20-420-421-card-expand-and-row-form.md:11-22（#420 引擎起源）

**spec**：docs/specs/2026-09-21-pre-render-coordinator-design.md（spec v1=#432：
D1-D5 :8-14 · I1-I5 :16-22 · 架构 :26-40 · Phase 表 :67-73 · 风险红线 :75-80）；
docs/specs/2026-09-25-435-height-engine-unification-design.md（统一配对规则）

**research**：
- docs/research/pre-render-coordinator/00-synthesis.md:10-59（K1-K12/架构定案/开放问题 Q1-Q5）
- docs/research/pre-render-coordinator/04-internal-inventory.md:191,269-299,399+（域 C 十一轮/
  域 D 协调器「已建零调用」/汇总接入点总数与改动量粗估）
- docs/research/pre-render-coordinator/06-height-mutation-api-boundaries.md §2.9,§3.4,§5,§7
  （公开面结论表/巧合存续/R1-R6/能力边界矩阵）
- docs/research/pre-render-coordinator/07-baseline-freeze.md:12-45（红环基线与行为冻结）
- docs/research/sse-scroll-stability-iron-laws.md:22-60（五铁律正文）+ AGENTS.md「SSE 滚动稳定性（铁律）」节
- docs/research/2026-08-25-card-height-precompute-feasibility.md:10-28,81-97,145-147,151-159
  （前次否决/反射回写竞争缝/toggle 排除存档/预测量判定）
- docs/research/2026-09-24-compose-background-compute-feasibility.md:15-24,102-106,138-155
  （Q1 不可后台结论/落地建议/本地实证附录/版本核验）
- docs/research/433-height-engine-map.md:13-152（引擎架构地图；**§4 已过时**见本报告 §3.5）
- docs/research/433-compose-viewport-research.md · 433-streaming-pipeline-map.md（底层语义/管线图，
  快照期早于 #435，流式段同样需按 §3.5 勘误读）

**源码**（当前 HEAD）：
- app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/scroll/PreRenderCoordinator.kt:21-150
- app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/components/CardExpandReveal.kt:
  45-217（KDoc/常量/配对执行器）· 725-1047（episode/预热）· 1129,1349（FLUSH 注册点）
- app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/components/ScrollCompensation.kt:
  20-126（KDoc/规则）· 264-417（帽/flush 任务）· 620（LazyListReflection）
- app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/components/ChatMessageList.kt:665-683,713-727
- app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/ChatScrollController.kt:176-295,366-403
- app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/components/ViewportDispatchGateway.kt:7-35
- app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/scroll/PreRenderCoordinatorTest.kt:22-82

**git**：26a94b3f(Phase0) · fb3478f6(P0) · f6a4a314(Phase1a) · b44de010(评审修复) ·
634a3e11(批次二) · 805c0e84(批次三) · fac75665(竞态三修) · c5ddfbbf(残差重试) ·
7785cfbf(反射定位) · 108a9001(收起镜像化) · af428686(#435 统一) · fca689d7/2da3b6d0(#432) ·
722e20d3(批次十一考古源)
