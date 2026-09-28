
# #429 大卡渐进展开——系统性深度调研（2026-09-28）

> **一句话结论**：#429 的主诉场景（大卡展开 3~5s 等待 + 白屏揭示）已被 2026-09-24 用户裁决「过程卡片退役、默认全展示」（批次十四，7d5cd5fc）**架构性消解**，其技术残余（分批增长跨 settle 窗）已由 #430 稳态配对根修、滚动付费点已由 #431 拆解；
> 卡片本身只剩**收尾工作**（死代码清理 #426、tableGroupBounds 单测护栏、V6 人工验收归档），不建议在其名下继续开发；
> 若未来用户重新要求「可折叠大卡」，应以现有资产（窗口化+片高账本+v4 分批+稳态配对）另立新卡，且必须先对齐 #437 高度引擎重写后的新契约（I1′/I2′）。

---

## 1. 问题陈述（用户可感知症状 + 触发条件）

### 1.1 原始主诉（2026-09-23，spec 立项时）

- 展开大卡时「等待计算高度」有时卡 3~5 秒 → 高度瞬间展开 → 内容以**白屏形式揭示**，不美观。
- 期望：展开动画（收起的镜像）、边展开边算高度、从上到下顺序计算、点击即动（docs/specs/2026-09-23-progressive-card-expand-design.md:3）。

### 1.2 三档实测时间线（真机 houji，大卡 j7eSbe8T，60 行×4 列表格 H=20352px，spec:6-14）

| 场景 | 点击→内容可见 | 分解 |
|---|---|---|
| 预热完成（空闲≥1.2s 后点击） | 265ms | 重组 35ms + settle 89ms + Phase A 22ms + Phase B 幕布 215ms |
| 暖进程但预热未跑 | ~2.4s | ①整列表重组风暴 0.3~1s（GC 122MB/190ms）②巨表单片组合+测量 ~1.05s ③A/B 相 360ms |
| 真冷（首次/无预热+账本冷） | ~3~5s | 表格文本异步解析 0.5~2s（Loading 空白）+ 单帧 2501ms 表格排版测量 + settle |

### 1.3 白屏机制

- Phase A 布局原子开与 Phase B drawF 起步区间之间，布局已开但内容 alpha≈0/裁剪≈0 → 卡区 3~7 帧纯白，随后内容自上而下洗入（spec:21）。

### 1.4 触发条件

- ① 步组折叠卡含巨型表格：>2048 字符 text part 走异步解析（app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/markdown/MarkdownContent.kt:731）。
- ② 连续操作中点击（PREWARM_IDLE_MS=1200ms 预热未命中）。
- ③ 冷进程/冷账本。
- 反向条件（预热命中 265ms）说明问题不是引擎低效，而是「首测即需全高」的构造性代价（spec:27）。

## 2. 已知历史（卡片 + journal 取证/定罪/部分修复，逐条带引用）

按时间顺序，全部来自 backlog 卡片与 journal 一手记录。

### 2.1 时间线总览

| 日期 | 事件 | 提交 |
|---|---|---|
| 2026-09-23 | spec 立项：三场景实测+三大定罪 | 486599a1 |
| 2026-09-24 | L0 快赢交付（风暴收敛+表格退出选择） | d3462892 |
| 2026-09-24 | L1 行组虚拟化第一次尝试→回退 | （journal 批次七） |
| 2026-09-24 | L1-v2+L2 交付→用户裁决回退+loading 过渡 | fc9ce058 → dccbb46a |
| 2026-09-24 | spinner 三轮定罪与修复（先行帧+共享表） | cb7cbf47 |
| 2026-09-24 | 后台化可行性调研（组合/测量不可后台） | 20da4abc/b7800d94 |
| 2026-09-24 | 时间切片 v4 终案（stagedLimit 分批+首组代表测） | 1e67fa2a |
| 2026-09-24 | #430 稳态配对根修（窗外增量逐帧配对） | （journal 批次十三） |
| 2026-09-24 | 批次十四：过程卡片退役（默认全展示）——主诉场景消解 | 7d5cd5fc |
| 2026-09-24 | #431 滚动穿表优化（块分段+列宽 LRU） | 28b268dc |
| 2026-09-25 | #437 考古勘误：#429 复制菜单从未在真机工作→根修 | 22f3e68d |

### 2.2 逐条明细

1. **spec 立项定罪**（486599a1）：
   - ①MIUIScout 长帧 2501ms，栈= SimpleMarkdownTable final-pass 行高测量 ← SelectableTextAnnotatedStringNode（可选中文本）← CardExpandGeometryNode.measure；60行×4列=240 个可选中文本单元单帧多遍测量（spec:18）。
   - ②toggle 重组风暴：toolExpandedStates 为 StateFlow 整表 Map，每次切换发新 Map → 所有消息卡全量重组+GC 122MB（spec:19；backlog.md:126）。
   - ③切片粒度限制：sliceStepGroupBodies 按 PartGroup 边界切片 → 60 行表格=单 part=单片 20292px，窗口化被击穿（spec:20）。
2. **L0 交付**（d3462892）：
   - ①toggle 风暴收敛：StateFlow 整体下沉+per-key derivedStateOf 逐键读取，CLICK 后全列表重组洪泛→仅 7 行局部重组。
   - ②表格退出逐字选择（DisableSelection）+长按复制菜单补偿（复制此格/整表 TSV）。
   - 实测：冷进程首开 CLICK→Phase A **62ms**（原 2.4~3.5s）（backlog.md:130；docs/journal/2026-09-22-423-prerendercoordinator.md:566-571）。
3. **L1 第一次尝试回退**：三轮修复（双测崩溃/窗口抖动/估高偏置）后终局阻塞于 containerWidth=0 首测列宽坍缩（行高 6188 vs ~20000）+跨遍槽位别名 → 错误高度 17604 污染片高账本（journal:569）。
4. **L1-v2+L2 交付又回退**（fc9ce058 → dccbb46a）：
   - 交付：每组独立 SubcomposeLayout+Column、组高放置回调入账本、首窗=视口+1 屏；真冷首开 ~1.2s/暖 81-94ms；L3 被构造吸收（可见域恒先组合后揭示）（journal:573-579；backlog.md:131）。
   - 回退：用户实测「一旦滑动就巨卡」（组入窗组合落滚动帧）→ 恢复「先算高度」架构 + 新增 loading 过渡（onExpandComputing 引擎信号+折叠行 spinner）（backlog.md:132；journal:581-586）。
5. **loading 过渡三轮定罪与修复**（cb7cbf47）：
   - ①状态机正常但像素验不到；②state=true 与折叠行首次渲染相隔 2.3s（spinner 重组与 ε 内容组合同帧被压在巨帧后）；③跨条目接线缺失（大组 #sgh 外层行与卡体是不同 LazyItem）。
   - 修复=先行帧（invoke(true) 后等一帧再 warmup）+LocalStepGroupComputing 共享表；修复后 state=true→行渲染 10ms，2.4s 窗内圆环像素验证可见（backlog.md:133；journal:588-594）。
6. **后台化可行性调研**（20da4abc/b7800d94，用户指示）：
   - 组合/测量**不可后台**（WindowRecomposer 架构性绑定 UI 线程，1.7-1.12 无 API，官方源码定罪）；可后台=解析（已做）/PrecomputedText 列宽预测；官方推荐原语=PausableComposition 分帧+movableContentOf 保活+Canvas 直绘（backlog.md:134；docs/research/2026-09-24-compose-background-compute-feasibility.md:17-24）。
7. **时间切片三轮定罪 + v4 终案**（1e67fa2a）：
   - 信号方案全数退役：①子树 CompositionLocal 够不到（大表走 async parse，表格组合晚于展开计算窗）；②进程级全局信号帧序竞态；③v3 首组合恒分批仍 Skipped 114 帧，巨帧主源=naturalWidths remember 全表 1116 次 TextMeasurer.measure（×4 表≈950ms）同步执行。
   - **v4**：stagedLimit 以 (content,tableNode) 键记忆、grouped 表起步 1 每帧+1 组、完成后全保留（滚动=单体，v2 教训）；naturalWidths 只测首组代表行（表头+8 行=54 次≈40ms）；与 PREWARM 协同命中即 266ms。
   - **B 方案 movableContentOf 真机否定撤除**：re-expand 连续 Skipped≈400ms×5——组合免了测量没免。
   - 真机：冷点击五段渐进 Skipped 74/53/33/32/31（v1/v3 单帧 114/116）、episode 2165ms（冷）vs 266ms（预热命中）（backlog.md:135-141；journal:596-603）。
   - 注：L0 阶段「冷进程首开 62ms」与 v4 阶段「冷点击 episode 2165ms」场景不同（单表 20352px vs 4 表 36612px 跨条目），数字不可直接比较；两组数据均为当时构建的真机实测。
8. **#429 后续 → 移交 #430**（2026-09-24）：
   - 分批使大表高度增长跨越 settle 窗逐帧落地、窗外增量零配对 =「展开向上顶」主诉（与 #430 两层时序洞叠加）。
   - #430 以稳态账本+pre-draw flush 同帧派发+欠账 rebase 修复，终验 Σd=Σconsumed=36612 完美守恒、topY 2051→2051 精确归位（backlog.md:142-144；journal:605-645）。
9. **批次十四：过程卡片退役（关键转折）**（7d5cd5fc，用户裁决）：
   - 「高度设置引擎不动；过程卡片退役，过程默认全展示」——StepGroupCard 折叠行+CardExpandReveal 包裹撤除、内容直渲染；思考默认展开；切片/账本/重组门全保留（管「接近才付钱」）。
   - 引擎审计列出死代码清单（建议 #426 移除）：pin corrector/phaseADrain/闭环族、宿主侧大组条目裂变路径（expandedStepGroups 恒空）/StepGroupFoldRow/onExpandComputing+spinner 通路。
   - 并发现**滚动穿大表新付费点** → 立 #431（journal:647-673）。
10. **#431 交付**（28b268dc）：
    - 巨型 text part markdown 块边界分段（splitHeavyTextPart ≤2200 字符贪心装段）+ 表格列宽跨回收 LRU（NaturalWidthsLru 容量 24）。
    - 12 次滑动穿表 Skipped 102/58/60/64/69/47（累计≈3.4s）→ 1 次 Skipped 35，改善≈90%（backlog.md:105-117；journal:675-695）。
11. **#437 考古勘误（对 #429 交付物的重大修正）**：
    - 表格长按复制菜单**从未在真机工作过**（#429 只验证了 TSV 单测）；用户此前的复制能力=逐字选择，被 #429 的 DisableSelection 移除——「不能复制」实为能力真空。
    - 根因：clickableMarkdown 的 detectTapGestures 消费 down 使外层长按全失效；#437 以 22f3e68d 根修（真机双实证：长按表格弹菜单 ✓、长按正文出选区 ✓）（docs/journal/2026-09-25-437-streaming-md-stable-reveal.md:34-39）。
12. **V6 遗留**：
    - spinner 帧级旋转直接证据未捕获（点击落点漂移+248ms~2s 窗口），请用户真手指验收「点击大表展开时圈圈是否持续转动」；展开态 fling 专项未跑（backlog.md:141；journal:603）。
    - **注意：批次十四之后该通路已成死代码（见 §3.4/§4-R4），此 V6 项实际已随场景退役而失效。**

## 3. 现状代码走读（当前实现关键路径）

### 3.1 步组大卡：默认全展示 + 切片窗口化（#429 主战场现况）

- **StepGroupCard**（app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/components/MessageCardAssistant.kt:1290-1431）：
  - KDoc 明示「#430(2026-09-24 用户裁决) 过程卡片退役：折叠行与 CardExpandReveal 包裹撤除，过程内容默认全展示」（:1290-1296）。
  - heavyComposed 门控：先等一帧再组合重内容（:1330-1336）。
  - 未组合时桩帧高度=账本 Σ+片间距逐像素对齐（:1362-1384，#437 根修二）。
  - 小组直渲染 ChunkAssistantItems（:1385-1397）；大组走切片+窗口化（:1398-1428）。
  - 账本挂进程级 LRU 店 StepGroupLedgerStore 跨分支互换存活（:1436-1439，#437 根修三；StepGroupHeightLedger.kt:27-46/108）。
- **StepGroupWindowedBody**（app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/components/StepGroupWindowedBody.kt:85-172）：
  - #427 P3 窗口化宿主：只组合与「视口±1 屏」相交的片（:121-130 放置回调重算窗口、量化失效）。
  - 冷账本降级整体组合（:137-138）；rememberUpdatedState 固定槽位 lambda 身份防每遍 dispose 重建（:97-102，真机 #427 定案）。
  - 单 measurable Column 包装防 first() 只测放首个（:103-118，60 行表 0px 定罪）。
- **流式/非流式分支**：流式 turn 恒平铺 PartContent（asyncParse=false），非流式走 StepGroupCard（MessageCardAssistant.kt:398-448）。

### 3.2 大表：v4 时间切片 + #431 列宽缓存（现状生效中）

- **MarkdownTable**（app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/markdown/MarkdownTable.kt）：
  - grouped 阈值 TABLE_GROUPED_MIN_ROWS=20（:101）；stagedLimit 以 (content,tableNode) 键记忆、grouped 起步 1（:212-232）。
  - DisableSelection 退出逐字选择+长按复制菜单（:234-243）。
- **GroupedTableBody**（:694-797）：
  - naturalWidths 只测首组代表行（表头+8 行）且经 NaturalWidthsLru 跨回收缓存（:722-743，#429 v4 + #431 B）。
  - cap/fill 列宽语义与原整测路径一致（:745-761）。
  - 帧步进器 LaunchedEffect 每帧 +1 组、withFrameNanos 让帧给动画（:763-770）。
  - 组装 Column 完成后全保留=滚动单体（:772-796）。

### 3.3 高度引擎：CardExpandReveal（#429/#430 资产，现役消费者=PartContent 折叠族）

- KDoc（app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/components/CardExpandReveal.kt:45-81）：
  - #420 原地揭示+同帧配对；#430 稳态配对——episode 外迟到增长（分批表格逐组落地/asyncParse）由稳态账本（measure 记账 Δreport）+ pre-draw flush 派发 +Δ 接管，否则 reverseLayout 锚定把窗外增量全额转译为上方内容上顶。
- 展开主状态机（:725-847）：
  - withEpisode 租约 → warmup（:763）→ settleUntilContentStable（:764；SETTLE_STABLE_FRAMES=2 :228、MAX_SETTLE_MS=600 :234）。
  - Phase A withMutableSnapshot driveTo(1f) 布局终态同步落地（:774-777）。
  - 配对派发 applyPairedPreRenderShift(+H)；贴底 fii==0∧fiso==0 免派发（:786-808）。
  - 欠账 rebase（:816-824）→ onExpandComputing(false)（:832）→ 200ms 纯绘制幕布（:834-847）。
  - 幕布空白拍由 PHASE_B_JUMP_START=0.3 缓解（:118-127）；prewarm：折叠卡可见且静止 PREWARM_IDLE_MS=1200ms 后 ε 预热（:133-136、:1027-1047）。
- 引擎测绘与 steady pairing 机制细节见 docs/research/433-height-engine-map.md:39-91（measure 记账 noteSteadyReport → pre-draw flush 决策链：steadyHold→isScrollInProgress 弃配→lastReportedH≤0 等落地→takeSteadyPending 派发→2s 重试窗残量回账）。

### 3.4 死代码坐实（grep/git 核验，2026-09-28 当前 HEAD）

- **onExpandComputing** 在引擎外**零调用者**（全仓 grep 仅 CardExpandReveal.kt:538/738/832/960 自身）——#429 loading 过渡信号通路已死。
- **expandedStepGroups** 恒为 emptyMap()（ChatMessageList.kt:479）——大组条目裂变路径（ChatEntry.StepGroupHead/Body 分支 ChatMessageList.kt:1612-1666，含 #429 spinner 占位 :1655-1666）不再有发射源。
- **StepGroupFoldRow**（MessageCardAssistant.kt:1220-1238）唯一使用点在死路径内（ChatMessageList.kt:1634）；其 expanding 参数无人传 true、computingShared 共享表（LocalStepGroupComputing，util/ChatCompositionLocals.kt:72）无写入者。
- **TableGroupBoundsTest** 已不存在：fc9ce058 添加、dccbb46a（回退）删除（git log --diff-filter=AD 核验）。
  - **journal 引用瑕疵**：批次十一与 v4 提交信息（1e67fa2a）均声称「TableGroupBoundsTest+全量单测绿」，但 git show 1e67fa2a --stat 显示该提交未触碰任何测试文件，且该测试当时已被删除——此声明为引用错误（实际绿的应是 TableTsvTest 等既有套件）。
  - tableGroupBounds 纯函数（MarkdownTable.kt:114）现**无单测护栏**。
- 上述死代码清单与 #426 卡片（backlog.md:283-284）及批次十四审计（journal:670-672）一致。
- 现役单测：CardExpandClockTest（含 #430 的 5 个新用例，journal:641）、StepGroupSlicingTest、SliceWindowRangeTest、StepGroupHeightLedgerTest、TableTsvTest 等（app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/components/ 清单核验）。

## 4. 根因分析（尚未解决的根因链；已部分修复的明确剩余部分）

### R1 主诉——已架构性消解（非根修）

- 3~5s 等待的三成分：(a) toggle 风暴 0.3-1s；(b) 表格解析 0.5~2s；(c) 单帧 2501ms 排版测量——任何调度技巧都救不了一帧内 240 个文本排版，必须拆小测量或让首屏子集先行（spec:35）。
- 现状：(a) L0 拆除（journal:568）；(b) #428 解析缓存+async parse 覆盖；(c) v4 分批+首组代表测（journal:599）。
- **更根本的是批次十四把「展开大卡」这个动作本身退役了**——步组默认全展示，展开等待的场景不再存在，展开交互仅剩 PartContent 折叠族小卡（引擎审计：episode 快，journal:665）。
- 置信度：**高**（用户裁决+代码坐实）。
- 剩余部分：无主诉级残余。若用户某天要求恢复「可折叠大卡」，R1 的构造性代价（首测即需全高 vs 渐进揭示的矛盾）会原样回归——这是 #429 知识资产的核心价值所在。

### R2 白屏揭示——已缓解，机制仍在

- Phase A 落地与 drawF 起步区间的 3~7 帧白屏（spec:21）经 200ms 幕布 + PHASE_B_JUMP_START=0.3（CardExpandReveal.kt:118-127）缓解为「首批内容即满透明度」。
- 小卡族体验已验收过多轮（批次二/三，backlog.md:164-165）。
- 置信度：**高**（机制定位）/**中**（大内容场景的白屏残余观感——原大卡展开场景已退役，无法再实测）。
- 待验证项的验证方法：临时恢复折叠交互（或以 #431 残余 hitch 场景）录屏观测幕布行为。

### R3 分批增长跨 settle 窗 → 展开向上顶——已由 #430 根修

- 三层洞：episode 单时刻配对对窗外增量无主 + dispatch 瞬态 0 消费 + 连锁断粮（journal:619-624）。
- #430 稳态账本+欠账协议修复，真机 Σ 守恒/topY 精确归位（journal:635-641）。置信度：**高**。
- 剩余部分：勘误明确「冷路径瞬态 0 消费自 #420 起就存在」（journal:643-645）——引擎竞态清单 R1 多卡同帧容量竞争、R4 窗口体生长无配对等为**已登记观察项**（journal:665-669），未观察到触发但未根修。

### R4 技术债——未解决，本卡片唯一真实剩余

- 死代码族（onExpandComputing/spinner 通路、expandedStepGroups 裂变路径、StepGroupFoldRow）误导后来者以为展开过渡路径仍在服役；journal 批次十一的 TableGroupBoundsTest 引用瑕疵会误导「分组函数有测试覆盖」的判断。置信度：**高**（本调研 grep/git 双核验）。
- tableGroupBounds（MarkdownTable.kt:114）作为 v4 分批的承重纯函数**无单测**——分组边界回归（如阈值、首组代表行选取）无护栏。置信度：**高**。
- 死代码清理归属 #426 卡片（backlog.md:283-284），#429 与 #426 存在交付物交叠：#429 引入的通路由 #426 收尸。**关联注明**。

### R5 残余性能点——已移交，非本卡

- #431 修复后滚动穿表仍残余单次首组合 hitch（~300ms 级，journal:692-693）。
- 流式期重组隔离是 #439（backlog.md:182-184）；高度引擎分片增量化是 #442（backlog.md:177-180）。
- 这些是「滚动/流式」线，不是「展开」线。置信度：**高**（归属清晰）。

### R6 复制能力——已由 #437 根修

- #429 的 DisableSelection 拆选择风暴是正确取舍，但其补偿菜单从未真正工作（#437 定罪+根修 22f3e68d，journal 437:34-39）。
- 现状复制能力已恢复且更强（单元格/整表 TSV/正文选区）。置信度：**高**。

### 与其他卡片的关联网络（任务提示要求注明）

- **#437 ↔ #444**：#444「fling 下滑跳变复发」是 #437 系修复后的回归对（backlog.md:276）。
- 更重要的是 **#437 的高度引擎重写**（底原点预留契约 I1′ 零可见位移/I2′ 预测量-only，docs/specs/2026-09-26-437-height-engine-redesign.md:8-11）一旦落地，#429/#430 依赖的配对机器（steady ledger/episode/dispatchRawDelta）将被重构为「预留高度表+同 pass 原子施加」（同 spec:13-18；实现裁决变更记录 :31-45）——**任何 #429 复活方案都必须先对齐该契约，否则在新引擎上重造旧引擎的债**。
- #428（收起跳变+解析缓存，backlog.md:146-154）、#430（迟到增长配对）、#431（滚动穿表）、#435（流式增长并入高度引擎，backlog.md:96）、#439、#442、#426 均为 #429 的直系分支或承接线。

## 5. 修复方案空间

### 方案 A：收尾关闭（保守，推荐）——清理+护栏+验收归档

- **内容**：
  - ①执行 #426 死代码清理中 #429 部分（onExpandComputing+spinner 通路、expandedStepGroups 裂变路径、StepGroupFoldRow、#sgh/#sgt 条目分支）。
  - ②补 tableGroupBounds 单测（钉住：分组组数、首组含表头+8 行代表测语义、组界闭合无重叠）。
  - ③按 AGENTS.md:95-97 验证纪律补 V6 人工验收清单（大表滚动观感+#431 残余 hitch 是否可接受——替代已失效的 spinner 验收项）。
  - ④卡片经 ./scripts/backlog.sh 迁移 journal 归档。
- **影响面**：纯清理+测试，零行为变化；触 MessageCardAssistant/ChatMessageList/CardExpandReveal 三文件。
- **风险**：低——StepGroupFoldRow 无其他引用已核验（唯一使用点在死路径 ChatMessageList.kt:1634）；死代码删除误伤由全量单测+compileDevDebugKotlin 兜底。
- **SSE 铁律/既有能力兼容性**：完全兼容——不触流式管线（AGENTS.md:104-111 五条铁律无一涉及）；CardExpandReveal 引擎本体不动（批次十四「引擎不动」裁决延续）。
- **TDD 切入点**：
  - 先写 TableGroupBoundsTest（纯函数，JVM 可测，模式仿 StepGroupSlicingTest）钉住分组不变量。
  - 再删死代码；全量 testDevDebugUnitTest 绿+compileDevDebugKotlin 通过即不变量成立。
  - CardExpandClockTest 既有 #430 用例继续钉住稳态配对 Σ 守恒。

### 方案 B：渐进展开复活（中庸——仅当用户重新要求「可折叠大卡」交互）

- **内容**：基于现有资产重组折叠路径——
  - 折叠行回归 + 展开不再「settle 等全高」，而是「首窗实测+账本/桩占位落位（stubHeightPx 已与 StepGroupWindowedBody 总高累加式逐像素对齐，MessageCardAssistant.kt:1362-1384）+ 窗外增长交 #430 稳态配对逐帧接管 + v4 分批保证计算期帧间让出」。
  - 即以现架构实现原 spec L2「首窗即时」的等价语义（spec:51）。
- **影响面**：复活 ChatMessageList 条目裂变路径（#426 清理须暂缓该部分）；StepGroupFoldRow/onExpandComputing 通路转正；与 #422 的锚定裂变史（五轮真机迭代均错位已撤，docs/research/433-height-engine-map.md:146）正面相撞。
- **风险**：高交互风险（v2 虚拟化的滚动巨卡教训 journal:583；#422 锚定错位史）；**前置依赖 #437 引擎重写落地**——在旧引擎上复活等于重造 #430 已修过的时序洞集合。
- **SSE 铁律兼容性**：兼容但需逐条复核（展开集不涉流式管线；但若折叠行出现在流式 turn 边界需维持 LocalInStreamingTurn 降级纪律，AGENTS.md:104-111）。
- **TDD 切入点**：先在 CardExpandClockTest 可测缝写两个不变量——
  - ①「账本全暖时展开 settle ≤2 稳定帧且桩高与实测差 ≤1px」。
  - ②「窗外逐组增长的 steady 派发 Σd=Σconsumed」（已有 #430 用例可扩展）。
- **工作量**：M。

### 方案 C：表格渲染模块完全重写（激进——Canvas 直绘+后台预测量）

- **内容**：
  - SimpleMarkdownTable 整体替换为**单个 Canvas 节点直绘**：后台线程用 PrecomputedText/StaticLayout 预测每列宽度与行高（官方明文背书的后台文本布局路径，2026-09-24 调研 §二-3/§七-2），主线程只按缓存值快速 measure/绘制。
  - 展开动画=Canvas clip 逐行揭示（高度=预测表，**零 settle 等待，白屏从构造上消失**）。
  - 滚动=绘制平移零组合（同时根修 #431 残余 hitch 与 #439 表格部分）。
- **影响面**：MarkdownTable.kt 全重写；选择/长按/链接点击/a11y 全部重建（现有语义基线=DisableSelection+复制菜单，#437 修复后的手势透传模式可复用，journal 437:37）；与 #437 pilot 的流式表格渲染需另接。
- **风险**：高——像素级对齐工作量大（ref0 基线裁决先例 journal:576）；后台预测量与主线程实测的误差兜底需要设计（#437 重写 spec Q2 已有「同约束测量确定性、零误差」裁决可援引，spec 2026-09-26:24-26）；TextMeasurer 线程安全官方未承诺（后台须走 PrecomputedTextCompat 而非 TextMeasurer，2026-09-24 调研 §二-1/二-5）。
- **SSE 铁律兼容性**：表格不经 Markdown() 状态机 → 铁律 1 不适用；但流式场景的表格渐进渲染必须接入 #437 SafePrefixGate 的稳定块语义（backlog.md:93-94），否则引入不稳定尾回溯。
- **TDD 切入点**：
  - 先写 TableCanvasModel 纯函数 golden 测试——布局算法（列宽 cap/fill/尺度缩放）与现 SimpleMarkdownTable 输出**逐像素对齐**（用 ref0 列界 395/755/925 作基线锚，journal:576）。
  - 再写行高预测精度测试（预测 vs 实测误差 ≤1px）。
- **工作量**：L。

## 6. 建议

**推荐方案 A（收尾关闭），工作量 S（约 0.5~1 天）**。理由：

1. 主诉已被批次十四用户裁决**架构性消解**——继续在 #429 名下开发等于变相恢复被裁决退役的交互，违背裁决本身。
2. 技术残余各有归属且均已交付或登记：向上顶→#430（已修）、滚动付费→#431（已修+残余观察）、复制能力→#437（已修）、死代码→#426（待做）、引擎演进→#437/#442（进行中）。#429 自己剩下的只有「护栏（tableGroupBounds 单测）+收尸（#429 部死代码经 #426）+验收归档」。
3. 方案 B 的前置依赖（#437 引擎重写）未落地，现在做是在旧引擎上重造已修过的时序洞；方案 C 是 #431/#442 性能线的远期选项，不属于「展开」问题域——若立项应另立卡片并引用本报告 §5-C。

**关闭门槛**（按 docs/verification.md V1-V6 框架）：

- 方案 A 完成后需全量单测绿+真机装机冒烟（大表会话滚动+小卡展开/收起零回归、ANR/crash 0）+V6 人工项一条（大表滚动观感）。

## 7. 引用清单

### 一手卡片与 journal

- backlog.md:125-144（#429 卡片全量 12 条 note）
- backlog.md:105-117（#431）；backlog.md:119-123（#430）；backlog.md:146-154（#428）；backlog.md:283-284（#426）
- backlog.md:93-96（#437/#435）；backlog.md:177-184（#442/#439）；backlog.md:276（#444）
- docs/journal/2026-09-22-423-prerendercoordinator.md:566-571（批次七 L0+L1 回退）
- docs/journal/2026-09-22-423-prerendercoordinator.md:573-579（批次八 L1-v2+L2）
- docs/journal/2026-09-22-423-prerendercoordinator.md:581-586（批次九回退+loading）
- docs/journal/2026-09-22-423-prerendercoordinator.md:588-594（批次十 spinner 三轮定罪）
- docs/journal/2026-09-22-423-prerendercoordinator.md:596-603（批次十一 v4 终案）
- docs/journal/2026-09-22-423-prerendercoordinator.md:605-645（批次十三 #430）
- docs/journal/2026-09-22-423-prerendercoordinator.md:647-673（批次十四退役+审计）
- docs/journal/2026-09-22-423-prerendercoordinator.md:675-695（批次十五 #431）
- docs/journal/2026-09-25-437-streaming-md-stable-reveal.md:34-39（复制菜单考古定罪+根修）

### spec 与调研文档

- docs/specs/2026-09-23-progressive-card-expand-design.md:3-65（#429 立项 spec 全文）
- docs/research/2026-09-24-compose-background-compute-feasibility.md:17-24/30-68/93-106（后台化定罪与官方路径）
- docs/research/433-height-engine-map.md:39-91/142-147（引擎账本/结算/flush 测绘+可疑点）
- docs/research/sse-scroll-stability-iron-laws.md:22-110（SSE 五条铁律+滚动性能铁律）
- docs/specs/2026-09-26-437-height-engine-redesign.md:8-45（引擎重写契约 I1′/I2′+实现裁决变更）

### 源码（2026-09-28 HEAD）

- app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/components/MessageCardAssistant.kt:1290-1431（StepGroupCard 现状）；:1220-1238（StepGroupFoldRow 死路径）；:398-448（流式/非流式分支）
- app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/components/StepGroupWindowedBody.kt:85-172
- app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/markdown/MarkdownTable.kt:101/114/212-232/694-797
- app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/components/CardExpandReveal.kt:45-139/725-847
- app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/components/ChatMessageList.kt:479/1612-1666
- app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/components/StepGroupHeightLedger.kt:27-46/108/128
- app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/markdown/MarkdownContent.kt:731
- app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/util/ChatCompositionLocals.kt:72
- AGENTS.md:95-97（验证纪律）；AGENTS.md:104-111（SSE 滚动稳定性铁律）

### git 提交链

- 486599a1（spec）→ d3462892（L0）→ 7dd57492（L0 journal）→ fc9ce058（L1-v2+L2，含 TableGroupBoundsTest 添加）→ dccbb46a（回退+loading，测试删除）→ cb7cbf47（spinner 修复）→ 20da4abc/b7800d94（后台化调研）→ 1e67fa2a（v4 终案）→ 7d5cd5fc（批次十四退役）→ 28b268dc（#431）→ 22f3e68d（#437 复制根修）
