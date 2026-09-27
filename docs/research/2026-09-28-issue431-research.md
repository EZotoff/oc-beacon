# #431 调研报告：滚动穿大表单帧巨块——巨型 text part 单片不可分 + 首组代表测/解析成本

> **一句话结论**：#431 的两项修复（A 巨型 text part markdown 块边界分段 + B 表格列宽跨回收 LRU，提交 `28b268dc`，2026-09-24）已消灭主病灶——穿表冻结从 6 次累计 ≈3.4s 降至单次 ≈300ms hitch（改善 ≈90%），但**卡片仍未关**：残余根因是「新片进窗首帧全量组合」的结构性成本（全片 markdown 树组合 + 表格首组代表测 48 次同步测量同帧落地），且存在三类未覆盖的同族缺口（超预算原子块、Reasoning/Tool 巨 part 不可分、synthetic 片被预解析 registry 排除）；建议以「原子块二级降级 + 代表测/解析成本归零化」保守收尾（工作量 S），再走 V6 用户手感验收关卡。

- 调研日期：2026-09-28 · 只读调研，零代码改动
- 一手来源：backlog.md 卡片与 journal 批次记录、仓库源码/测试当前 HEAD、既有调研文档
- 结论分级：每条论断标注置信度（高/中/低）与「待验证」验证方法

---

## 1. 问题陈述

**用户可感知症状**（2026-09-24，小米 houji 真机 120Hz，冷进程）：
- 滚动穿越含多张大表的长回答（步组过程内容）时，每次滑过一屏出现 0.4-0.85s 冻结，实测 6 次滑动 Choreographer Skipped 102/58/60/64/69/47，累计 ≈3.4s（backlog.md:106、docs/journal/2026-09-22-423-prerendercoordinator.md:661、691，用户原话「卡的要死」）。
- 该场景在「过程默认展示」之前不可达——步组内容原本折叠在过程卡内，滚动不付费；退役后滚动穿表成为新付费点（journal:649、661-663）。

**触发条件**：
1. 历史会话中存在权重 ≥4400（≈2 屏，`STEP_GROUP_SLICE_THRESHOLD_WEIGHT`）的步组，进入切片+窗口化路径（StepGroupSlicing.kt:26、33-34）；
2. 步组内含**巨型 text part**（实测 10193 字符，journal:662），旧切片器只在 `PartGroup` 边界切片 → 巨型单 part 独自成片（≈5 屏）（backlog.md:107）；
3. 该片滚动进入「视口 ±1 屏」窗口时**单帧全量组合**：markdown 解析 + 表格脚手架 + naturalWidths 首组代表测（48 次测量/表 ×4 表 ≈150ms 重付）同帧执行（backlog.md:107、journal:662-663、MarkdownTable.kt:714-721）；
4. v4 表内分批（#429 的 stagedLimit）只覆盖**表格内部的行组组合**，不覆盖**片级首帧**（journal:663）——即整片非表格内容的组合仍是单帧。

---

## 2. 已知历史（卡片 + journal 取证/定罪/修复链）

按时间序，全部带引用：

1. **#429 表格巨帧三轮定罪与 v4 终案（2026-09-23/24，先决背景）**：60 行表被 markdown 源拆为 4 个 MarkdownTable；Skipped 114 帧巨帧主源 = naturalWidths `remember` 内全表 1116 次同步 TextMeasurer.measure（×4 表 ≈950ms），帧步进器只分批组合未分批宽度测量 → v4 改为**只测首组代表行**（表头+8 行=54 次≈40ms）+ stagedLimit 首组合恒分批（每帧 +1 组）（backlog.md:136-137）。**这就是卡片标题「首组代表测」的出处**。
2. **#428 解析路径（2026-09-23）**：>2048 字符文本走异步解析（Default 线程），≤2048 同步内联 1-3ms；`MarkdownParsedStateCache` LRU 32 覆盖回收重入（backlog.md:150-154、MarkdownContent.kt:640-648）。#431 切片依赖此分层。
3. **#430 批次十三（2026-09-24）**：展开向上顶根修（稳态配对+欠账协议）（journal:605-645）。
4. **#430 批次十四：过程卡片退役（2026-09-24 用户裁决）**：折叠行与 CardExpandReveal 撤除、思考默认展开、内容直渲染；**heavyComposed 门/切片+窗口化/片高账本全保留**（「接近才付钱」）（journal:647-656）。随即真机发现滚动穿表新付费点 → **立 #431**（journal:661-663）。注意：#431 卡片 note 把「过程默认展示」记在 #430 名下（backlog.md:106），journal 批次十四同冠 #430（journal:647）——退役工作是 #430 尾批执行的用户裁决，编号归属如此，溯源时以 journal 为准。
5. **引擎审计（批次十四）**列出 #431 候选：片内分段 / naturalWidths 跨回收 LRU / 片细化；并留观察项 R4「窗口体生长无配对」（journal:665-673）。
6. **#431 批次十五：修复落地（2026-09-24，提交 `28b268dc`）**：
   - **方案澄清**：用户问是否 AST 并行解析——否，块扫描纯函数落地（无 AST 依赖，JVM 可单测）（journal:677-680）；
   - **A** `splitHeavyTextPart`/`markdownBlocks`：空行分块、表格/围栏代码原子、贪心装段 ≤2200 字符、合成 part id `#sgN` + synthetic=true → 进 packer 前展开（journal:683-685、StepGroupSlicing.kt:44-51）；
   - **B** `NaturalWidthsLru`：模块级 accessOrder LinkedHashMap，容量 24，键=fontSize+全文，回收重入零重测（journal:686-687、MarkdownTable.kt:109-111）；
   - **真机前后对比**：6 次冻结 ≈3.4s → 1 次 Skipped 35（与刷新率切换系统事件同帧），热轮回滚再 +1 次 45；渲染完整性多模态判读通过（行序连续/列对齐/无断表）；单测 +7 全绿（journal:690-695、backlog.md:115-117）。
7. **后续同区域演进（修复验证之后）**：#437 验收十三轮（`fe795a49`，2026-09-26）改动了同一宿主 StepGroupCard——heavyComposed 撤 msgId 键、桩帧高度=账本 Σ、账本改挂进程级 `StepGroupLedgerStore`（commit message + MessageCardAssistant.kt:1325-1339、1436-1443）。**#431 的真机数据采集于这些改动之前**，数值存在漂移可能（见 §4 R7）。
8. **卡片状态**：`- [ ]` 未完结（backlog.md:105）——按 backlog 纪律完结需用户验收；残余 hitch 与候选 C（片粒度细化）明确留在卡片候选行（backlog.md:108）。

---

## 3. 现状代码走读（当前 HEAD）

### 3.1 活跃滚动路径（#430 退役后的真实路径）

```
LazyColumn item = StepGroupCard（MessageCardAssistant.kt:1298）
  ├─ stepSlices = remember(step.groups){ sliceStepGroupBodies(...) }   :1311-1313
  ├─ heavyComposed 门：首帧桩（账本Σ高度），一帧后放真内容          :1330-1336,1362-1384
  ├─ 权重 <4400 → ChunkAssistantItems 直渲染（小组零回归）           :1385-1397
  └─ ≥4400 → StepGroupWindowedBody（视口±1屏子组合窗口）             :1404-1428
        └─ 窗内片：ChunkAssistantItems → PartContent(asyncParse=true)  :1415-1427
              └─ MarkdownContent：>2048 异步解析 / ≤2048 同步内联      MarkdownContent.kt:649-659
                    └─ MarkdownTable：stagedLimit 分批 + NaturalWidthsLru  MarkdownTable.kt:230-232,722-743
```

### 3.2 关键实现与历史取证的对应

| 组件 | 位置 | 职责 / 历史对应 |
|------|------|----------------|
| `STEP_GROUP_BODY_TARGET_WEIGHT=2200` | StepGroupSlicing.kt:19 | 片预算 ≈1 屏（#422 标定沿用） |
| `STEP_GROUP_SLICE_THRESHOLD_WEIGHT=4400` | StepGroupSlicing.kt:26 | ≈2 屏才进切片路径（不足 2 屏窗口内恒全量组合，切片无收益） |
| `sliceStepGroupBodies` | StepGroupSlicing.kt:40-63 | **#431 修复 A 落点**：进 packer 前对 >2200 字符 text part 做 `splitHeavyTextPart` 展开（:44-51），再按 PartGroup 贪心装片（:52-62） |
| `splitHeavyTextPart` | StepGroupSlicing.kt:72-97 | 块边界贪心装段 ≤2200；原子块超预算独段；段 part `id#sgN`+synthetic（账本指纹一次性转冷，注释明示可接受 :70） |
| `markdownBlocks` | StepGroupSlicing.kt:105-130 | 纯函数块扫描：空行分隔；表格（连续竖线行）与围栏代码原子；段落内单换行守恒 |
| `NaturalWidthsLru` | MarkdownTable.kt:109-111 | **#431 修复 B 落点**：容量 24、键=fontSize+NUL+全文、accessOrder；进程存活期有效，重启一次性重付 48 次≈40ms/表（:104-107） |
| naturalWidths remember | MarkdownTable.kt:722-743 | **#429 v4 首组代表测**（表头+首组 8 行）+#431 LRU 接线；miss 时同步 textMeasurer.measure 主线程执行（:732-735） |
| stagedLimit 帧步进器 | MarkdownTable.kt:230-232、765-770 | 大表（>20 行）首组合恒分批，每帧 +1 组；滚动进视口同样分批（~136ms/17 组@120Hz，:225） |
| `StepGroupWindowedBody` | StepGroupWindowedBody.kt:85-172 | 视口 ±1 屏（marginPx=screenHpx :127）；**冷账本降级全片组合**（:136-138）；窗内片单遍 subcompose+measure（:142-150）；窗口判定量化失效（onGloballyPositioned 回调，:121-130） |
| 片高账本 | MessageCardAssistant.kt:1320、1404-1414、1441-1443 | `StepGroupLedgerStore` 进程级 LRU（#437 十三轮），Σ片高对引擎透明 |
| 解析分层 | MarkdownContent.kt:649-667、731 | asyncParse=true（分片路径恒 true，MessageCardAssistant.kt:811）→ >2048 异步（Default 线程，主线程零解析）/ ≤2048 同步内联 1-3ms |
| `MarkdownParsedStateCache` | MarkdownContent.kt:745 | LRU 32，内容为键，Loading 拒入（#428）——回收重入解析零成本 |
| 预解析 registry | MessageCardAssistant.kt:795-813 | **`synthetic != true` 排除条件在 :798**——#sgN 合成段永远拿不到 registry 预热 |
| 死路径（列表层裂变） | ChatMessageList.kt:479、MarkdownChunking.kt:279、371 | `expandedStepGroups` 恒空（硬编码 emptyMap），注释明示随 #426 移除——journal 批次十四审计的「宿主侧大组条目裂变路径」死代码判定与 #431 修复共存不冲突：修复落在**卡内**活跃路径 |

### 3.3 测试钉住的不变量（StepGroupSlicingTest.kt）

- #431 新增 7 例（:186-259）：空行分块、表格原子、围栏原子、小 part 原样返回、预算装段+派生 id、超预算原子表独段、万字符块状文本分布多片+内容守恒。
- **关键豁免用例**：`indivisibleMonsterPartStaysWhole`（:89-95）——40000 字符无空行巨段单片保留；`giantBlockyTextDistributesAcrossSlices` 断言片 ≤2200×2（:255）——packer 允许单段超装，单片实际上限 ≈2× 预算。
- 既有：幂等/确定性/守恒/阈值边界/指纹互斥（:97-184）。

---

## 4. 根因分析（剩余根因链，含置信度）

**主病灶（已修复）**：巨型 text part 在 PartGroup 边界切片下独自成片 → 进窗单帧全量组合。修复 A 拆解之，修复 B 消除回收重入重测。**以下为尚未解决/部分解决的链**：

### R1 片级首帧单帧全量组合（残余 hitch 的结构主源）——置信度：高

新片进入 ±1 屏窗口时，`StepGroupWindowedBody` 在**单个测量遍**内 subcompose+measure 整片（StepGroupWindowedBody.kt:142-150）；片内成本 = 异步解析回填后的全片 markdown 树组合 + 表格脚手架 + naturalWidths 首组代表测（48 次同步测量在 remember 内主线程执行，MarkdownTable.kt:722-743）。解析本身在 Default 线程（主线程零解析，MarkdownContent.kt:638、686-687），但**组合+测量不可后台**（官方架构绑定 UI 线程，docs/research/2026-09-24-compose-background-compute-feasibility.md:17、24——Q1 已源码级确证）。真机残余证据：修复后仍 1 次 Skipped 35（≈300ms hitch，与系统事件同帧）+ 热轮回滚 45（journal:692-693）。stagedLimit 只摊表格行组，不摊片内非表格内容与代表测。
**待验证方法**：真机复跑批次十五同参数脚本（Choreographer Skipped 分段分布，journal:140 判据），分离「代表测 40ms×N 表」与「markdown 树组合」占比（可用 naturalWidths LRU 命中日志 + SliceHost 帧日志对照）。

### R2 巨型原子块不可分的残余缺口——置信度：高（行为）/ 中（实际触发频率）

`markdownBlocks` 把表格与围栏代码整体化、连续非空行为一段（StepGroupSlicing.kt:116-127）：①**无空行巨段**（如连续单换行文本、长 URL 墙）= 单块 → `segs.size<=1` 原样返回（:93）；②**超预算围栏代码块**独段保留（:87-90，测试 :239-245 钉住）——巨代码块**没有** v4 表格那套行组分帧兜底，进窗仍是单帧全量；③超预算巨表独段，但表内有 stagedLimit 承接（设计明示，StepGroupSlicing.kt:69）。LLM 输出大代码块常见，此缺口是 #431 主病灶的近亲。
**待验证方法**：构造含 >2200 字符代码块的会话真机滑动，观察 Skipped 是否回到三位数。

### R3 同族 part 类型缺口：Reasoning / Tool / Shell 不可分——置信度：中

`splitHeavyTextPart` 只处理 `Part.Text`（StepGroupSlicing.kt:46）。批次十四后**思考默认展开**（journal:654-655），巨 reasoning part 全文经 `ReasoningBlock` → `MarkdownContent` 渲染（PartContent.kt:170-214、ReasoningBlock.kt:343），无块级分段；巨 Tool/Shell 输出同理走工具卡路径。若长会话出现万字符思考/工具输出，同族冻结可复现。Context 组（工具调用簇）显式不可分（StepGroupSlicing.kt:12）。
**待验证方法**：真机找含长思考的历史会话滚动穿行，Choreographer 分段对照。

### R4 缓存边界与 synthetic 预热排除——置信度：中

①`NaturalWidthsLru` 容量 24、键=fontSize+全文：>24 张大表的长会话滚动时逐出→重付 40ms/表（MarkdownTable.kt:110）；②`MarkdownParsedStateCache` LRU 32 同理（#428 残余同族风险已有登记，backlog.md:153）；③**synthetic `#sgN` 段被 registry 预解析条件 `synthetic != true` 排除**（MessageCardAssistant.kt:798）——所有拆分段首进窗必走异步解析 Loading 首帧，无法预热（MarkdownContent.kt:648 注释称「≥200 字符有 registry 预解析覆盖」，对合成段不成立）。
**待验证方法**：DEBUG 日志统计 registry miss 率（readinessRegistry.flow 未命中计数）。

### R5 stagedLimit 步进与滚动帧竞争——置信度：低-中（未分离测量）

大表滚动进窗后每帧 +1 组（首组 8 行/后续 12 行，MarkdownTable.kt:114-127、765-770），组块装配 ≈8ms/帧逼近 120Hz 帧预算 8.3ms；与滚动帧叠加可能贡献零星 skip（journal 批次十一实测 136ms/17 组渐进，backlog.md:137）。#431 修复后的 Skipped 35/45 是否含此成分未分离。
**待验证方法**：同会话对比「穿表滚动」与「静止等 staged 完成后再滚动」的 Skipped 分布。

### R6 冷账本降级全组合——置信度：中

任一片无实测高度 → 窗口宿主降级**全片组合**（StepGroupWindowedBody.kt:136-138）；冷进程直接跳转/快速滑到步组深处时首测窗=顶部 3 片或全片。heavyComposed 门+桩帧（账本 Σ，#437 十三轮）已把首帧视觉冲击缓冲为桩帧+一帧延迟（MessageCardAssistant.kt:1330-1336、1362-1384），但组合成本仍单帧（桩帧只是让位一帧，不是分帧）。

### R7 验证数据时效性（流程性风险）——置信度：高（事实）/ 影响未测

#431 真机前后对比采集于 2026-09-24（journal:690-695）；其后 `fe795a49`（#437 十三轮，改 StepGroupCard 桩/门控/账本）、`a7795293`（MarkdownTable 令牌）、`d71cc096`（#455 边距）陆续改动同区域文件（git log 28b268dc..HEAD 实证）。修复结论方向大概率仍成立（切片/缓存逻辑未被触碰），但**残余 hitch 量级需复验**后才可关卡。
**待验证方法**：复跑批次十五 12 次滑动脚本取新基线。

### 与既有调研的分歧核对

- 无实质冲突。compose-background-compute-feasibility（2026-09-23）的「组合/测量不可后台、只能摊开」结论与 #431 落地的「按屏付费+缓存」同向；其提及的官方 `PausableComposition` 分帧原语与 stagedLimit 自研步进器是同族手段（该文档 Q5，:22）。sse-scroll-stability-iron-laws §2.5（铁律 6-8）针对**数据层派生风暴**，与本卡片的**渲染层组合成本**分层互补，互不覆盖。

---

## 5. 修复方案空间

### 方案一（保守增量）：原子块二级降级 + 代表测/解析成本归零化【推荐收尾】

1. `splitHeavyTextPart` 增加原子块降级：超预算**围栏代码块**按闭合围栏内行级切段（代码行无块间语义，渲染安全，段间续接方式需视觉判读裁决）；无空行巨段按行窗切段。**巨表保持原子**（v4 stagedLimit 已兜底，StepGroupSlicing.kt:69 设计明示）。
2. naturalWidths 代表测消重：LRU 容量 24→64 + 键改 hash(fontSize, content)（防全文键驻留内存），或按 feasibility 文档 Q2 官方路径把探测测量前移 Default 线程（StaticLayout/PrecomputedText 预测）。
3. synthetic `#sgN` 段接入 registry 预解析（放宽 :798 条件或按派生 id 注册），首进窗即终态、免 Loading 首帧。
- **TDD 切入点**（先红后绿）：StepGroupSlicingTest 增 3 类用例——「超预算围栏代码块被切分且 join 后内容守恒」「切分幂等（重切扁平化输出不变）」「每段 ≤ 预算上限（收窄 :255 的 2× 豁免）」；LRU/registry 行为各 1 例（容量逐出后内容变失配 / synthetic 命中预热）。
- 影响面：StepGroupSlicing.kt + MarkdownTable.kt 缓存参数 + MessageCardAssistant.kt 一行条件，零 UI 结构变化。
- 风险：低。不触流式路径（SSE 铁律 1/3 不涉）、不触高度引擎（铁律 4/autoScroll 不涉）；账本指纹已容忍 synthetic 转冷（StepGroupSlicing.kt:70）。
- 兼容性：与 #437 十三轮桩帧/账本体系正交；与 #439（entries 签名缓存）、#442（R2 分片增量化）不冲突。

### 方案二（结构性）：片粒度细化（候选 C）+ 片内进窗分帧

1. `STEP_GROUP_BODY_TARGET_WEIGHT` 2200→约 1100（片数×2，单帧冻结块减半），或
2. 新片进窗采用 stagedLimit 同款**片内块级帧步进器**：首帧只组合片首块，其余块每帧 +1（把 R1 的「片级首帧全量」摊成帧级）——等价于把 #429 v4 的表内分帧语义推广到片内 markdown 块。
- **TDD 切入点**：先钉「窗内片总高=Σ块高+间距逐帧单调不减」「Σd=Σconsumed 守恒」（#430 不变量族）、「块序单调渲染完整性」；帧步进器单测仿 `TableGroupBoundsTest`。
- 影响面：中-大。片数×2 → 账本键×2、stubHeightPx Σ 对齐式重验；片内分帧与 #437 桩帧、窗口体生长（审计 R4「窗口体生长无配对」，journal:668）交互需重新取证；每帧 +1 块的节奏参数需真机标定（类比 #438① 壁钟限速教训：与到达解耦）。
- 风险：中。历史上 v2 表格窗口化因滚动回退（backlog.md:132）、L1 虚拟化因 containerWidth=0 首测列宽错误回退（backlog.md:130）——**分帧/虚拟化族在本仓有两次回退前科**，需按 #429 v4 模式（组合完成全保留+滚动零丢弃）设计。
- 兼容性：不违反 #429 约束「不回归逐帧全量布局族/不渲染后补偿」（backlog.md:128）；SSE 铁律不涉（非流式路径）。

### 方案三（激进）：渲染层重构——统一块流虚拟化渲染器（模块重构/重写）

把 StepGroupSlicing + MarkdownContent 分层 + MarkdownTable 分帧三层成本统一下沉为一个 **BlockStream 渲染模块**：part 流 → 块级扫描（现 markdownBlocks 推广）→ 统一块条目（每块=一个虚拟化槽位，SubcomposeLayout 逐块进窗）→ 三账本合一（解析缓存/列宽/块高统一键控 LRU，替代 MarkdownParsedStateCache+NaturalWidthsLru+StepGroupHeightLedger 三个独立缓存）。极端形态：步组卡内嵌 LazyLayout 逐块 item 化（完全重写步组渲染）。
- 收益：R1/R2/R4 从构造上消失（块=最小付费单元）；R3 一并覆盖（Reasoning/Tool 输出走同一块流）。
- **TDD 切入点**：先写模块契约测试钉外部不变量——「flatten(切片输出)==输入（内容守恒）」「块序单调」「宿主自报高度=Σ块高（对引擎透明，StepGroupWindowedBody.kt:30-33 契约延续）」「冷降级正确性不依赖预热」；再迁移。
- 影响面：大。高度引擎配对（#435「锚即意图」）、#437 SafePrefixGate 流式放行、#439 重组隔离、#442 R2 分片增量化**全部排队在同一区域**——方案三应作为这些卡片的统一设计输入而非独立实施，否则四度重写同一宿主。
- 风险：高。违反窗口化两次回退教训的概率最大；SSE 铁律 1（rememberMarkdownState）在块流下需等价重建（retainState 语义）；工作量 L 且需用户裁决。
- 兼容性：需重接 PreRenderCoordinator 契约（I1-I5 不变量，backlog.md:158）与 V1-V6 全套验证。

---

## 6. 建议

**推荐：方案一收尾（工作量 S，1 个批次内完成）+ 复验 + V6 验收关卡。**

理由：
1. 主病灶已修（≈90% 改善有真机证据链），残余是 ~300ms 级单次 hitch——方案一恰好瞄准残余链中可低风险消除的部分（巨代码块缺口是可预期的复发性冻结；代表测 LRU/synthetic 预热是纯缓存正确性），收益/风险比最高。
2. 方案二的分帧族有两次本仓回退前科，且 #437 十三轮刚稳定桩帧/账本体系，立即再动宿主风险叠加；应先拿方案一后的新基线数据，用户仍可感再启动（届时与 #442 R2「分片增量化」合并设计——两者同是把 O(内容) 压成 O(增量)）。
3. 方案三只在 #439/#442 启动同区域重构时作为统一模块设计输入（L，需用户裁决），不应为 #431 单独触发。

**关卡路径**（依 AGENTS.md:97 验证铁律）：
1. TDD 落地方案一（先红测试）→ 全量单测 + 真机复跑批次十五 12 次滑动脚本取新基线（含 R7 漂移复验）；
2. 仪器可断言部分（Skipped 分布/渲染完整性多模态判读）AI 真机验证即收；「滚动流畅手感」属真手指体感——出 V6 人工验收清单请用户验收；
3. 用户验收通过后当场迁移卡片入 journal 关卡 #431。

**关联卡片注明**：
- #430（批次十四过程退役=本卡触发前提；编号归属见 §2 第 4 条）
- #429（v4 表内分帧+首组代表测是本卡直接基座）；#428（解析分层/缓存依赖）
- #426（列表层裂变死代码待清，含 sliceStepGroupBodies 列表路径调用点 MarkdownChunking.kt:371）
- #437（十三轮 fe795a49 改动同宿主——本卡验证数据需复验；#438 同族滚动残差）
- #439 / #442（同区域排队重构，方案三的合并对象）；#432（思考卡 prewarm 早熟已修 2da3b6d0，与 R3 思考展开路径相关）
- #444（fling 跳变族，同域不同层——其为流式配对层，本卡为渲染组合层）

---

## 7. 引用清单

**backlog**：#431 卡片 backlog.md:105-117 · 候选行 :108 · #429 :125-144 · #428 :146-154 · #423 :156-165 · #430 :119-123 · #437 :93-94 · #438 :87-91 · #439 :182-184 · #442 :177-180 · #444 :276-278 · #426 :283-284 · #443 :280-281 · #432 :102-103

**journal**（docs/journal/2026-09-22-423-prerendercoordinator.md）：批次十三 :605-645 · 批次十四（#430 过程退役+审计+立卡）:647-673 · 批次十五（#431 修复+真机对比）:675-695

**源码**（app/src/main/kotlin/dev/leonardo/ocbeacon/…）：
- ui/screens/chat/components/StepGroupSlicing.kt:19、26、40-63、44-51、66-97、99-130、141-164
- ui/screens/chat/components/MessageCardAssistant.kt:1290-1313、1317-1336、1353-1357、1362-1384、1385-1428、1441-1443、795-813
- ui/screens/chat/components/StepGroupWindowedBody.kt:22-38、55-82、90-96、121-138、142-150
- ui/screens/chat/components/CardExpandReveal.kt（高度引擎执行器，433 地图）
- ui/screens/chat/components/ChatMessageList.kt:479（expandedStepGroups 恒空死路径）· MarkdownChunking.kt:279、371
- ui/screens/chat/components/PartContent.kt:170-214 · ReasoningBlock.kt:343
- ui/screens/chat/markdown/MarkdownTable.kt:100-111、114-127、212-232、714-770、722-743
- ui/screens/chat/markdown/MarkdownContent.kt:636-667、681-713、731、745

**测试**：app/src/test/…/StepGroupSlicingTest.kt:89-95、97-108、186-259（#431 七例）

**调研文档**：docs/research/sse-scroll-stability-iron-laws.md:86-150（滚动性能铁律 6-8）、241-264（验证方法）· docs/research/2026-09-24-compose-background-compute-feasibility.md:15-24（Q1/Q2/Q5 结论）· docs/research/433-height-engine-map.md（引擎 API/契约）· docs/research/pre-render-coordinator/00-synthesis.md

**规范**：AGENTS.md:97（验证铁律）、104-113（SSE 滚动稳定性铁律）、架构承重规则 · docs/verification.md（V1-V6）

**提交**：28b268dc（#431 修复，2026-09-24 23:28，5 文件 +215/-14）· fe795a49（#437 十三轮，同宿主后改）· 9499f905/3759317a（#427 窗口化宿主史）· 2da3b6d0（#432 prewarm 早熟）
