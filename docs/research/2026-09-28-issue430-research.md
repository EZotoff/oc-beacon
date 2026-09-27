# #430「展开向上顶——稳态迟到增长零配对+欠账派发时序」深度调研

> 调研日期:2026-09-28 · 范围:只读调研,零代码改动 · 方法:卡片/journal/调研文档/源码/测试五源交叉,全部结论带 文件:行号

> **一句话结论**:#430 的三层根因(episode 单时刻配对/瞬态 0 消费/连锁断粮)已于 2026-09-24 由 commit c0754035 根修交付并真机终验全绿(Σ 守恒+topY 精确归位),但卡片至今未关闭的真实原因是:同日批次十四退役了主触发场景后,#435/#437 两轮对同一配对管线做了大改(流式侧执行器从 dispatchRawDelta 换为 requestPosition 原子消费、引入一帧缓冲帽与统一谓词),#430 终验结论未在当前代码上复核,且 #444(2026-09-27)报告的 fling 下滑跳变复发正落在这族配对 set 上——**剩余工作是「收口复核 + 审计遗留 R1/R4 竞态处置」,不是重新根修**。

---

## 1. 问题陈述

### 1.1 用户可感知症状

reverseLayout 消息列表下,展开各类卡片(步骤组/工具卡/推理块)时,**上方旧内容有时被向上顶起而非新内容向下铺开**(backlog.md:119-120;docs/journal/2026-09-22-423-prerendercoordinator.md:607)。用户点破关键观察——「同一行为多种表现 = 状态依赖的确定性结果伪装成随机」(journal:607)。

症状的三种形态(按严重度):

| 形态 | 表现 | 证据 |
|---|---|---|
| 轻度 | 展开瞬间视口上跳、数帧后自愈 | k==fii 构型天然自愈掩盖了一半样本(journal:643-645) |
| 重度 | 上方旧内容整体上顶 N px 不回(topY 982→-1870) | 19:34 实验 H=2852 上顶(journal:615) |
| 连锁 | 表格缺失(永不组合) | v7 截图:折叠行与第三步总结紧邻,无表(journal:623-624) |

### 1.2 触发条件

取证矩阵(四组真机实验,logcat CardExpand/PRD + 截图判读,journal:609-617):

| 实验 | 构型 | dispatch 消费 | 结果 |
|---|---|---|---|
| exp1 | 卡在锚点条目内(k==fii) H=278 | consumed=0 | 天然向下 ✓(锚点自愈) |
| exp2 | 锚点上方(k>fii) H=146 | 全额 | 向下 ✓ |
| 19:31 | 预热命中 H=36612 | 全额(fiso 35232) | 向下 ✓ |
| 19:34 | k>fii+切片冷组 H=2852(首组) | **consumed=0** | **上顶 2852(topY 982→-1870)** |

规则:**正确 ⟺ (k==fii) ∨ (全额消费)**;失败 = k>fii 且瞬态 0 消费(journal:617)。最重形态是连锁断粮:上顶把 reveal 顶出视口 → 切片窗口(视口±1屏)不覆盖表格 → 表格永不组合(journal:623-624)。

## 2. 已知历史(逐条带引用)

### 2.1 时间线总表

| 日期 | 事件 | 载体 |
|---|---|---|
| 2026-09-24 | #429 v4 分批交付——增长跨越 settle 窗逐帧落地,#430 主诉显性化 | backlog.md:142-144 |
| 2026-09-24 | 批次十三:#430 四组取证+根因定罪+修复协议+真机终验全绿 | commit c0754035;journal:605-645 |
| 2026-09-24 | 批次十四:过程卡片退役+引擎审计(R1-R6 竞态/死代码清单) | commit 7d5cd5fc;journal:647-673 |
| 2026-09-24 | #432 思考卡默认收起——用户裁决覆写 #430 的「思考默认展开」 | commit 010363f2 |
| 2026-09-25 | #435 高度引擎统一化:流式增长并入引擎;steady flush 增贴底豁免(补 #430 对称漏洞) | commit af428686;journal 435:11-34 |
| 2026-09-25~27 | #437 系(二十五世轮):帽协议/统一谓词/执行器换 requestPosition/cadence 收编 | 提交链 300db984→5d061df2;journal 437:244-415 |
| 2026-09-27 | #446 撕裂根修(毕业 held 收缩撤延迟);episode×流式并发「无双发」裁决 | e76aed43;journal 446:25-48;card-intervention:56-63 |
| 2026-09-27 | #444 fling 下滑跳变复发登记(未解,活跃回归) | backlog.md:276-278 |

### 2.2 前置因果链

#429 v4 时间切片(2026-09-24)让大表高度增长以「每帧 +1 组」分批落地,跨越 episode settle 窗(≤600ms)逐帧增长——窗外增量在 #430 修复前无任何配对,这正是「展开向上顶」被显性化的引信(backlog.md:142-144)。#430 勘误进一步明确:「冷路径的瞬态 0 消费自 #420 起就存在,被 #429 分批(增长跨越 settle 窗)显性化。k==fii 构型天然自愈掩盖了一半样本——『多种情况』的随机观感 = 两种构型 × 两种消费结果的确定性矩阵」(journal:643-645)。

### 2.3 批次十三(2026-09-24,commit c0754035)——定罪与根修

根因三层(journal:619-624;backlog.md:121):
1. **episode 单时刻配对**:settle(≤600ms)窗外的一切增长(#429 v4 分批表格逐组落地/asyncParse)无主;
2. **瞬态 0 消费**:增长晚一帧落地 → dispatchRawDelta 内测见旧高(容量 0)→ PairedDispatch 按「物理不可消费」放弃——19:34/19:46/22:16 三次冷展开全部命中(consumed=0 tries=0);
3. **连锁断粮**:上顶把 reveal 顶出视口 → StepGroupWindowedBody(视口±1屏)窗口不覆盖表格切片 → 永不组合 → 表格缺失。

修复协议(CardExpandReveal,journal:626-633;backlog.md:122):
- 稳态账本:measure 相 noteSteadyReport 记账 Δreport(基线/rebase 协议防双配对);
- pre-draw flush 任务:steadyHold 门(集内 dispatch 决策点前挂起)→ applyPairedPreRenderShift 同帧派发;
- 欠账 rebase(steadyRebaseAfterEpisodeDispatch):pending=目标−实消费,基线锚定目标;
- 派发门控 lastReportedH>0:增长落地才派发(19:46 定罪:欠账死于落地前派发,takeSteadyPending 先清账);
- 0/欠消费残量 2s 重试窗保留(restoreSteady;窗外丢弃=位置优先权);
- H 派发目标=落地高度(撤 maxOf finalHCache:v4 分批下落地≠缓存,超额派发=中位伪滚动);
- episode 末 late-growth catch-up 退役(死代码:measure 后 measured==reported 恒真)。

真机终验(houji 120Hz,冷进程+切片卡无预热——原 bug 满配场景,journal:635-641;backlog.md:123):
- 冷展开:settle 超时 H=17532 → paired-shift 0 → steady 三笔 25020/7788/3804 全额消费,**Σd=Σconsumed=36612 完美守恒,topY 2051→2051 精确归位**(中间仅重组合帧瞬态,331ms 三帧纠正);表格 36660 完整组合(断粮连锁消失);截图终判:折叠行钉住、表格向下铺、上方旧内容纹丝不动。
- 收起:close-pre(20,36335)→close-post(19,190) 锚点精确恢复,#427 路径零回归。
- 冷再展开(收起 21s 后,无预热):同绿,Σ 守恒。
- ANR/crash 0;单测 CardExpandClockTest 含 5 个 #430 新用例全绿;全量 testDevDebugUnitTest 绿。

### 2.4 批次十四(同日,commit 7d5cd5fc)——场景退役+审计

- 用户裁决:高度设置引擎不动;过程卡片退役,过程默认全展示;引擎做竞态/优化审计(journal:649)。
- 实现:StepGroupCard 折叠行(头/尾)+CardExpandReveal 包裹撤除,内容直渲染;heavyComposed 门/切片+窗口化/片高账本全保留(它们管「接近才付钱」);流式平铺路径不动(journal:651-653;MessageCardAssistant.kt:1291-1295 注释存档)。
- 引擎审计结论:生产消费者=PartContent 折叠族(小卡,episode 快)。竞态清单 R1-R6(journal:665-669):R1 多卡同帧稳态配对容量竞争(先到先得,边缘欠账 2s 后弃=位置优先权);R2 重试窗随增量生长滚动延展(增长止则止);R3 heavyComposed 单帧 24dp Spacer(滚入边缘可见时一帧跳变,观察项);R4 窗口体生长无配对(顶部不可见/底部贴缘理论可见,未 observed);R5 快速 toggle 链单测覆盖;R6 flush/episode 同主线程单写者,无数据竞态。
- 死代码(建议 #426 批次整体移除,该轮按「引擎不动」未触碰,journal:670-672):引擎内 pin corrector(~180 行)/phaseADrain/闭环族(dispatchClosedLoop 等);宿主侧大组条目裂变路径(expandedStepGroups 恒空)/StepGroupFoldRow/onExpandComputing+spinner 通路。
- 派生:#430 默认展示使滚动穿大表成为新付费点 → 立 #431(journal:661-663;backlog.md:106)。

### 2.5 后续演化(直接影响 #430 结论的时效性)

- **#432**(commit 010363f2):「思考卡默认收起(用户裁决**覆写 #430** 的思考默认展开)」——批次十四的产品侧决策被撤回一半(git log 摘要)。
- **#435**(2026-09-25,commit af428686):流式增长并入高度引擎统一配对。spec 明确指出 #430 引擎 steady 的**对称漏洞**——视口执行四体系并列表中「引擎 steady(#430 迟到增长)| measure 记 Δreport → pre-draw flush 派发 | 贴底豁免 ❌ 无(对称漏洞)」(docs/specs/2026-09-25-435-height-engine-unification-design.md:11-16),并增补 steady flush 贴底豁免(spec:41;落地见 CardExpandReveal.kt:1079-1089)。ScrollCompensation.kt 重写为 StreamingGrowLedger,COMP 家族(DeferredRevealCompensator ×5 挂载点)/PreRenderShiftChannel/GUARD stream-instant 退役,净 -326 行(journal 435:11;铁律文档变更日志 sse-scroll-stability-iron-laws.md:286)。
- **#437 系(二十五世轮,2026-09-25~27)**:流式侧再大改(docs/journal/2026-09-25-437-streaming-md-stable-reveal.md:244-288;git log):
  - 一帧缓冲帽 HeightReserveState/streamingHeightReserve(ScrollCompensation.kt:264-350)——增长当帧被帽裁掉,flush 单事务 {帽→真高+滚动} 同 pass 原子释放;
  - R1 统一配对谓词 StreamingAnchorRule(commit 300db984;ScrollCompensation.kt:80-99);
  - **配对执行器从 dispatchRawDelta 改为反射 requestPosition 写待定区、由下一遍 measure 原子消费**(#437 验收五轮用户裁决,对齐 #427 引擎先例;ScrollCompensation.kt:492-521 注释说明两者本质区别在「计算先行、measure 原子生效」vs「先画增长态再跳位」);
  - R1-A2 同帧双 set 单出口(commit d28360d8;ScrollCompensation.kt:477-521);
  - shouldYieldPairing 外部 pending 让位(commit cb733d80;ScrollCompensation.kt:108-117);
  - 终审 P1 拒绘方向反转修复(commit a7daa1b0;ScrollCompensation.kt:531-534);
  - cadence 48ms→100ms 收编引擎域 STREAM_FLUSH_INTERVAL_MS(commit 5d061df2;ScrollCompensation.kt:78;数据层 MessageEventHandler.kt:80-81 消费)。
- **#446**(2026-09-27,commit e76aed43):毕业 held 收缩撤销一帧延迟,撕裂 b12 族 13→2 帧收敛 88%(journal 446:25-40)——同属该管线时序族。
- **并发裁决**(2026-09-27):「episode ΔH_card 与 ledger ΔH_stream 同帧双发」实测不成立,机制三层=空间分离(LazyColumn 组合窗口分区)/单通道吸收(流式 item 内卡片 toggle 走 SGR ledger 不产生独立 episode)/单点串行(共享 PreRenderCoordinator flush);ScrollCompensation §6.2 理论缺口被证伪可触发性,不需要改生产代码(journal card-intervention:28-63)。
- **#444**(2026-09-27 登记,未解):fling 下滑跳变复发,嫌疑「R1-A2 单出口合并后的配对 set 行为变化或键保持通道在新路径的退化」(backlog.md:276-278)——正落在 #430/#435 家族的配对 set 上,**活跃回归**。

## 3. 现状代码走读(当前 HEAD,行号实测)

### 3.1 卡片侧稳态配对(#430 本体,全部在位)

文件:app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/components/CardExpandReveal.kt(1715 行)。

**(a) 稳态账本(CardExpandClock,:247)**

| 成员 | 行号 | 语义 |
|---|---|---|
| steadyLedger / steadyPending | :412-415 | 稳态基线(Int.MIN_VALUE=未起账)/ 待配对累计(px 带符号) |
| noteSteadyReport(report) | :424-441 | fraction≤0 恒清零;基线未起=静默建基线不配对;非零 Δ 入账+基线前移+2s 重试窗起算 |
| steadyRebase() | :444-447 | 清账+基线复位(episode 派发点/收起点/取消点调用,防 episode 自身跳变被双配对) |
| steadyRebaseAfterEpisodeDispatch(target, consumed) | :458-462 | **欠账 rebase**:基线锚定派发目标,pending=目标−实消费(注释含 19:34 真机定罪) |
| restoreSteady(d) | :469-471 | 残量回账下帧重试 |
| steadyHold / steadyRetryDeadlineNs | :479 / :482 | 集内挂起门 / 0 消费欠账 2s 重试窗 |
| takeSteadyPending() | :486-490 | 取走即清(flush 相单点) |

**(b) measure 相记账入口**

CardExpandGeometryNode(:1621).measure:每遍 clock.onMeasure(placeable.height)(:1673,写 lastMeasuredH、返回 report)→ clock.noteSteadyReport(report)(:1677)→ layout(width, report) 上报。分批增长源:MarkdownTable stagedLimit 首组合恒分批、每帧 +1 组(withFrameNanos 让帧;MarkdownTable.kt:222-230)——这是 steady 账本最主要的外部增量供体。

**(c) episode 主状态机(展开路径 :759-847,整体包在 PreRenderCoordinator.withEpisode 内 :729/:1023)**

关键序列:settle(:764)→ **H=落地高度 lastMeasuredH(#430 修正,:765-769,撤 maxOf 缓存)** → withMutableSnapshot 同步 driveTo(1f)(:775-777,防 dispatch 内测见旧高)→ programmaticShift 包裹:bottomPinnedExpandSkip 判定(#432,:792)→ applyPairedPreRenderShift(H)(:807)→ **欠账 rebase(:812-823:贴底或 0 消费且仍在 (0,0) 时 plain rebase :820,否则 steadyRebaseAfterEpisodeDispatch(H, consumed) :822)** → steadyHold=false(:824)→ 离底关 autoScroll(:828-830)→ 200ms 纯绘制幕布(:834-847)。

**(d) 稳态 flush 任务(:1067-1135)**

LaunchedEffect(clock.fraction>0f, listState) 注册 PreDrawFlushTask,决策链按序:

```
steadyHold?            → 挂账不派发(episode 独占)           :1071
isScrollInProgress?    → steadyRebase 弃配(位置归用户)       :1072-1074
bottomPinnedExpandSkip?(fii==0∧fiso==0)                      
  → drop-at-bottom + rebase(#435 增补的贴底豁免)             :1079-1089
lastReportedH <= 0?    → 等下一 pre-draw(落地门,19:46 定罪) :1094
d = takeSteadyPending();d==0 → 放行                          :1095-1096
consumed = applyPairedPreRenderShift(listState, d)           :1099
残量 |d-consumed|≥0.5 且窗内 → restoreSteady(d-consumed)     :1103-1107
consumed 入 episodeDisplacement + episodeShiftConsumedPx     :1108-1110
```

**(e) 历史取证 → 代码位置对应表**

| 历史定罪(journal 批次十三) | 当前代码位置 |
|---|---|
| 19:34 瞬态 0 消费(2852px 裸上顶) | 欠账 rebase CardExpandReveal.kt:458-462 + 落地门 :1094 |
| 19:46 欠账死于落地前派发(topY −22969) | 落地门 :1094 先于 takeSteadyPending :1095 的顺序 |
| settle 窗外增量无主 | 稳态账本 :424-441 + flush 任务 :1067-1135 |
| H 目标≠缓存(v4 分批落地≠缓存终值) | :765-769(H=lastMeasuredH) |
| 断粮连锁(reveal 顶出视口→表格缺失) | 修复后表格 36660 完整组合(journal:638) |
| 大组硬切换锚定五轮真机迭代均错位已撤 | ChatMessageList.kt:759-764 撤除存档(见 433 调研 04-internal-inventory.md:378) |

### 3.2 流式侧统一配对(#435/#437 后的现状,与 #430 同构)

文件:ScrollCompensation.kt(665 行,#435 重写+#437 系五连改后的现状):

- **StreamingAnchorRule 统一谓词**(:80-99):growthPx≤0(收缩)免;贴底原点(fii==0∧fiso<8px AT_BOTTOM_ORIGIN_PX :82)免;增长源不可见免;锚==增长源 → 同帧配对 +Δ;锚在源之下 → 按 coveredByFollowFamily 分流(ledger 族恒 covered=true 走跟随通道,:179-184);读历史免。
- **StreamingGrowLedger**(:126-190):note 建基线记 Δ(:141-158,冷启动静默/同帧多遍逐次累计)、forget 清账(:161)、rebaseAll 弃配(:166)、takePaired 统一规则求值清账(:174-189)。
- **streamingGrowFlushTask**(:362-537):帽释放 plan(:397-407,reserveReleasePlan :329-350)→ VTRACE 限频视口轨迹(:422-444)→ ScrollQuiescence 单点写(:447,R3)→ 用户滚动 rebaseAll(:459-461)→ **shouldYieldPairing 外部 pending 让位**(:467-475,读位≠上批 set 目标=显式意图未消费,配对会覆盖抵消)→ 帽 shift+ledger shift 同帧单事务 requestPosition 原子写入(:477-521,含键保持 :647-653)→ **ledger 派发帧拒绘(终审 P1 方向修复,:531-534)**。
- **挂载点**(ChatMessageList.kt):ledger/reserve remember(:492-494)、attachFlushHost(:670)、streamingGrowFlushTask 常驻注册(:678)、帽挂载(:1769)、streamingGrowPairing ×4(:1850/:2189/:2419/:2498)、列表级 Local 三件套+兜底 CardExpandReveal(:210-214)。
- **cadence 单一真相源**:STREAM_FLUSH_INTERVAL_MS=100 引擎域定义(ScrollCompensation.kt:78),数据层 MessageEventHandler 消费(MessageEventHandler.kt:80-81,342-349,可 debug 覆写 16-500ms)。

**两套配对体系决策链对照(现状)**:

| 维度 | 卡片 steady 相(#430) | 流式相(#435/#437) |
|---|---|---|
| 账本 | CardExpandClock.steadyPending | StreamingGrowLedger(per-key) |
| 贴底豁免 | bottomPinnedExpandSkip 严格 (0,0)(:1553) | StreamingAnchorRule 原点 <8px(:82,92) |
| 落地门 | lastReportedH>0(:1094) | 帽协议(增量当帧被裁,无此问题) |
| 让位 | isScrollInProgress 弃配(:1072) | isScrollInProgress rebaseAll(:459)+shouldYieldPairing(:467) |
| 残量重试 | 2s 重试窗 restoreSteady(:1103) | takePaired 即清(无重试窗,靠下批增长重起账) |
| 执行器 | applyPairedPreRenderShift=dispatchRawDelta 循环(:190/:1099) | requestPosition 待定区+measure 原子消费(:517) |

### 3.3 消费者现状(批次十四退役后)

CardExpandReveal 现存 12 处调用:ChatMessageList 兜底(:214)、ReasoningBlock(:304)、MessageCardAssistant 提问卡两处(:374/:457)、QuestionPartContent(:140)、EventCard(:186)、InjectionCard(:111)、MessageBubble(:218)、CompactionCard(:172)、ToolCardScaffold(:289)、GlobToolCard(:98)、TodoListCard(:159)——即审计所述「生产消费者=PartContent 折叠族(小卡,episode 快)」(journal:665)。StepGroupCard 的包裹已退役(MessageCardAssistant.kt:1291-1295),但 StepGroupCard 本体仍在非流式路径调用(:436/:831)且流式 turn 恒平铺(:398-405,#437 十三轮仪器在此设分支翻转探针 HFLICK)。

### 3.4 测试锁定

CardExpandClockTest.kt:390-510 共 **7 个 #430 用例**(卡片记录为 5 个新用例,现扩至 7):

| 用例 | 行号 | 钉住的不变量 |
|---|---|---|
| steadyLedgerAccumulatesPostEpisodeGrowth | :397-411 | 集后增量逐笔入账、取走即清 |
| steadyLedgerPairsNegativeGrowth | :417-423 | 收缩方向负增量同样入账 |
| steadyRebaseDropsPendingAndRebasesBaseline | :429-442 | rebase 丢弃+重建基线防双配对 |
| steadyLedgerSilentDuringWarmupAndCollapsed | :448-459 | ε 预热窗/收起态 report=0 静默 |
| snapRebasesSteadyLedger | :465-474 | 用户滚动打断 snap 同步 rebase |
| debtRebaseKeepsUnconsumedRemainderAsPending | :482-497 | **欠账保留(直接复现 19:34:目标 2852 消费 0→pending 2852;后续增量逐笔记账)** |
| debtRebaseZeroWhenFullyConsumed | :502-510 | 全额消费→欠账 0,steady 静默 |

另有 StreamingGrowLedgerTest 16 用例(#435 规则四构型+账本协议,journal 435:12)、StreamingAnchorRuleTest 决策表穷举(ScrollCompensation.kt:62)、ReserveReleasePlanTest 7 例(journal 437:247)。

## 4. 根因分析

### 4.1 已根修部分(高置信,证据充分)

三层根因链全部定罪并有修复+终验:①单时刻配对→稳态账本连续化;②瞬态 0 消费→欠账 rebase+落地门;③断粮→前两者修复后连锁消失(表格完整组合)。终验的数学判据(Σd=Σconsumed=36612、topY 2051→2051)是强证据(journal:636-641)。单测 7 例锁协议(§3.4)。冷展开已修的主张属实,Σ 守恒/topY 精确归位在卡片 note(backlog.md:123)与 journal(:636-637)双重记载一致。

### 4.2 剩余未解/存疑(逐条标注证据与置信度)

**(1) 终验结论的时效性缺口——高置信**
#430 终验针对 c0754035 时的代码;此后 #435 在同一条 flush 管线上增补贴底豁免、#437 系五连改流式执行器与帽协议(§2.5)。steady 相的冷展开 Σ 守恒/topY 归位**未在当前 HEAD 复测**。#444「fling 下滑跳变复发(原 #437 系修复后回归)」(backlog.md:276-278)是该域存在活跃回归的直接证据——嫌疑 R1-A2 单出口与键保持通道,二者恰是 #435/#437 改动、非 #430 原修复,但同管线,复核时应一并覆盖。

**(2) 两套账本/两套执行器并存——高置信(架构债,非现行 bug)**
卡片 steady 相与流式相协议同构(基线/欠账语义近似、2s 重试窗「#430 同款」spec:40)但代码独立、决策链分叉(§3.2 对照表)。#437 验收五轮已用户裁决 dispatchRawDelta 在流式侧是「先画增长态再跳位」的次优执行器(ScrollCompensation.kt:492-496 注释),卡片侧仍用它——语义分歧是未来 bug 温床。缓解证据:并发面已被 2026-09-27 实测证伪「双发可触发性」(journal card-intervention:56-63),故定性为债而非现行 bug。

**(3) 批次十四审计遗留竞态——中置信(登记未处置)**
R1 多卡同帧稳态配对容量竞争(先到先得,边缘欠账 2s 后弃=位置优先权)、R4 窗口体(StepGroupWindowedBody)生长无配对(顶部不可见/底部贴缘理论可见,未 observed)(journal:666-669)。生产消费者变为小卡后触发概率降低,但未消除。验证方法:同屏多张可展开卡(工具卡≥3)同帧展开,观察 [STEADY] pair 日志的消费分布与最终 topY 漂移。

**(4) steady flush 任务恒驻开销——低置信(观察项)**
注册条件 fraction>0 快照初值,组合保温卡持有 flush 任务贯穿会话,每帧 pre-draw 跑决策链(多数 d==0 早退,量级小但非零)(433 地图 §7.7,docs/research/433-height-engine-map.md:150)。#442 待复核项「flush 八职责深拆」(backlog.md:179)与此同域。

**(5) 半贴底域未取证 + 两侧阈值不一致——低置信(待验证)**
bottomPinnedExpandSkip 只覆盖严格 (0,0),fii==0∧fiso>0 保守不启用(433 地图 §7.8)。#437 已把流式侧贴底原点阈值放宽到 8px AT_BOTTOM_ORIGIN_PX(ScrollCompensation.kt:82),卡片 steady 侧未跟进——两侧阈值不一致。待验证:steady 侧放宽是否引入回归;验证方法=真机 fiso 0-20px 抖动窗(#437 二十四世轮实证贴底跟随期 fiso 在 0~20 抖动,ScrollCompensation.kt:374-377 注释)内触发展开/迟到增长,观察是否有刀锋振荡。

**(6) V6 用户验收缺位——高置信**
backlog 纪律「条目完结(用户验收)当场迁入」(AGENTS.md Backlog 纪律);journal 全量检索无 #430 用户手感验收记录(grep '#430' docs/journal/ 仅命中 423 journal 的批次十三/十四及零星引用),卡片仍 [ ] 与此一致。

### 4.3 与既有调研的分歧标注

- **433-height-engine-map(2026-09-25 前绘制)** 称「流式文本 item 增长引擎完全不管」(其 §4)——已被 #435/#437 推翻,现流式增长深度接入引擎(铁律文档变更日志 sse-scroll-stability-iron-laws.md:286 同步);其行号(如 steady 任务 :1067-1121)经 #435 增补后漂移至 :1067-1135,本报告以实测为准。该文档其余结构性描述(withEpisode 租约/六让位点/死代码清单)经抽查仍准确。
- **435 spec「引擎 steady 无贴底豁免(对称漏洞)」**(spec:14)→ 已由 #435 实施补齐(CardExpandReveal.kt:1079-1089),分歧已收敛。
- **批次十三终验「完美守恒」vs #444「跳变复发」**:不矛盾——前者覆盖冷展开/收起构型,后者是 fling 下滑构型,且时间上隔了 #435/#437 两轮改动;但正因如此,#430 判据需在当前 HEAD 重跑(§5 方案 A)。

## 5. 修复方案空间

### 方案总览

| 维度 | A 收口复核 | B steady 并入统一谓词/执行器 | C 配对体系完全重写(激进) |
|---|---|---|---|
| 工作量 | S | M | L |
| 代码改动 | 零 | CardExpandReveal+ScrollCompensation 定点 | 引擎域整体重构 |
| 主要风险 | 复测发现回归(即价值) | 欠账/重试协议重新对拍 | 隐性契约迁验不全 |
| 依赖 | #444 可同窗覆盖 | A 复核绿+#444 定罪 | 独立 spec+#442 衔接 |

### 方案 A:收口复核 + 判据固化(保守,S)

**内容**:不动生产代码。
1. 把批次十三终验判据(冷展开 Σd=Σconsumed、topY 精确归位、表格完整组合、收起锚点恢复、k>fii 全额消费)在当前 HEAD 重跑——真机 houji,原 bug 满配场景=冷进程+切片卡无预热;
2. #444 复现路径(fling 下滑)纳入同窗观察——嫌疑在流式配对 set,但 steady/流式共享 PreRenderCoordinator flush 单点,一次会话出两组结论;
3. 产出记录写 journal 后按验收流程(docs/ai-acceptance-workflow.md)请用户 V6 手感验收,通过即迁移关闭 #430。
- **影响面**:零代码;产出=journal 取证 + 探针脚本沉淀。
- **风险**:若复测发现回归(可能性中等,#444 暗示),则升级为根因定位任务——这正是本方案的价值:先定罪再动刀。
- **与 SSE 铁律兼容性**:完全兼容(SSE 铁律不动;docs/research/sse-scroll-stability-iron-laws.md §5.2 动态检查清单即其子集)。
- **TDD 切入点**:先把「Σ 守恒」从手工 logcat 判读固化为可重跑断言——CardExpandClockTest 已有 debtRebase 用例;补 1 个全链串联单测:模拟「episode dispatch 0 消费 → 三笔 steady 增长 → 全额消费 → Σ==目标」(现有用例各环独立,缺全链不变量);真机侧复用 ~/card-lab 的 GrowthWatcher/timeline 工具链(journal card-intervention:73-77,注意其 GrowthWatcher 正则与 tap 注入配方的坑:16-18)。

### 方案 B:steady 相并入统一谓词与统一执行器(中庸,M)

**内容**:
1. 卡片 steady flush 决策链的 bottomPinnedExpandSkip 换用 StreamingAnchorRule(AT_BOTTOM_ORIGIN_PX 8px 语义对齐,消除两侧阈值不一致,§4.2(5));
2. 评估 steady 派发执行器从 applyPairedPreRenderShift(dispatchRawDelta)迁移到 requestPosition 待定区原子消费(#437 验收五轮同款,#427 引擎先例)——消除「先画增长态再跳位」的中间帧。
- **影响面**:CardExpandReveal.kt(热文件,受 docs/chatscreen-editing-protocol.md 协议约束:Read→编辑→compileDevDebugKotlin→commit 循环、禁跨 agent 并行编辑)+ ScrollCompensation.kt;7 个 #430 单测+16 个 ledger 单测回归面。
- **风险**:steady 相构造性依赖「增长落地后本帧 dispatch 有容量」的时序(dispatchRawDelta 内测即知),换 requestPosition 后落地时序变为「下一遍 measure 原子消费」——欠账/2s 重试窗协议需要重新对拍;CardExpandClock 的 episode 相(展开/收起主路径)不动,只动 steady 相,边界清晰。
- **与铁律兼容性**:兼容——铁律 3「流式增长配对只应用于流式 turn」语义不变(steady 相是已完结消息内卡片,非流式 turn);统一谓词正是铁律文档 #435 更新方向的延伸(sse-scroll-stability-iron-laws.md:40)。
- **TDD 切入点**:先写 StreamingAnchorRule 在 steady 相构型的决策表用例(锚==增长源卡片/贴底原点 0-8px/读历史),钉住「卡片 steady 增长在贴底 fiso∈(0,8) 免派发不跳变」不变量;再写 requestPosition 迁移的帧序不变量(增长帧与位移帧同 pass 消费)。

### 方案 C:激进——配对体系完全重写,三相位统一收编协调器(L)

**内容**:按 #423 spec(pre-render-coordinator)Phase 1 主体构想(docs/research/pre-render-coordinator/04-internal-inventory.md:428):把 CardExpandReveal 的账本+执行器与 StreamingGrowLedger 合并为**单一 GrowthLedger 抽象**(per-source 基线+统一欠账/重试/让位矩阵),episode/steady/streaming 三相位全部走 StreamingAnchorRule 决策表与 requestPosition 单执行器,配对子体系从 1715 行 CardExpandReveal 中析出成为独立模块(引擎迁入 PreRenderCoordinator 域)。433 调研对此的预判:「收益=多事务并发有序化(#430 类战争从构造上消失)+每组件调和收拢;风险=十轮收口的隐性契约(整数守恒/缓存窗口/两阶段)需逐条迁验」。
- **影响面**:CardExpandReveal.kt(1700+ 行热文件整体重构,withEpisode 缩进债务 :1023 一并清)、ScrollCompensation.kt、ChatMessageList 挂载点、全部相关测试(3573 例量级回归,journal 446:48)。
- **风险**:最高。十轮收口的隐性契约(整数守恒量化阈 PairedDispatch :180、ε 预热窗 :332-335、两阶段 drawFraction、收起镜像账本 :377-392)分布在注释与单测里,逐条迁验成本大;#444 未定罪前重写=在流沙上盖楼。
- **与铁律兼容性**:目标态与铁律完全一致(单一视口权威、pre-draw flush 单点,AGENTS.md SSE 滚动稳定性铁律);过渡期需保持 100ms cadence、帽协议、SafePrefixGate(#437)行为不变。
- **TDD 切入点**:先为「三相位统一」写金测试(golden test):同一增长序列(episode 展开+steady 迟到+流式批)分别在旧/新实现的 Σ 位移与最终 topY 上断言相等;决策表以 StreamingAnchorRuleTest 为种子扩到 episode 相;并先钉死整数守恒(0.5f 量化阈)与 2s 重试窗语义的表征测试(characterization test),再动结构。

## 6. 建议

**推荐:方案 A 立即执行;A 的结果决定是否升级 B;C 不在本卡范围**(应作为 #442「高度引擎根修二期」的候选内容登记衔接,backlog.md:177-181 与本卡同域:flush 深拆/待复核项)。

理由:
1. #430 本体的三层根因**已修复且证据链完整**(§4.1),卡片悬置的原因是流程性的(终验后管线两轮大改未复核+V6 缺位),不是技术性的——重新根修没有靶子。
2. #444 是当前该域唯一活跃回归,且嫌疑明确指向 #435/#437 的改动(非 #430 修复本身);A 的复核矩阵把两者同窗覆盖,一次真机会话出两组结论,成本最低。
3. B 有真实收益(两侧阈值/执行器不一致是债)但应等 A 复核绿+#444 定罪后再动——若 #444 根因恰在执行器语义,迁移 requestPosition 可能顺带根治,一石二鸟;反之若 A 复核红,先修回归。
4. C 的「战争从构造上消失」愿景依赖隐性契约全部显性化,在 #444 未闭、#437 二十五世轮刚收敛(2026-09-25~27 密集改动)的当下时机不利;作为 #442 二期或独立 spec 立项更稳。

**预估工作量**:A = S(一次真机会话+journal 记录+单测补 1 全链用例);B = M(热文件协议循环 3-5 轮编译+单测扩展+真机三构型复核);C = L(独立 spec+Phase 化迁移+全量回归,参照 #423 Phase0-4 节奏)。

## 7. 引用清单

- **卡片**:backlog.md:119-123(#430)、:125-144(#429 及后续)、:146-154(#428 同族)、:177-181(#442)、:276-278(#444)
- **journal**:docs/journal/2026-09-22-423-prerendercoordinator.md:605-645(批次十三)、:647-673(批次十四)、:675-695(批次十五);docs/journal/2026-09-25-435-height-engine-unification.md:11-34;docs/journal/2026-09-25-437-streaming-md-stable-reveal.md:244-288(帽协议/底对齐/暂缓)、:400-415(帽协议代价);docs/journal/2026-09-27-446-tearing-root-fix.md:25-48;docs/journal/2026-09-27-card-intervention-growth-phase.md:28-63(三场景矩阵+无双发结论)、:79-117(#449 勘误)
- **调研/spec**:docs/research/433-height-engine-map.md(§1-§7,行号以本报告实测为准);docs/research/sse-scroll-stability-iron-laws.md:36-46(铁律 3/4 #435 更新)、:286(变更日志);docs/specs/2026-09-25-435-height-engine-unification-design.md:11-16(四体系/对称漏洞)、:36-41(规则/重试窗);docs/research/pre-render-coordinator/04-internal-inventory.md:378/:428(#430 战争根因与 Phase1 收编)
- **源码(当前 HEAD 实测)**:app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/components/CardExpandReveal.kt(:247 CardExpandClock、:412-497 稳态账本、:759-847 episode 展开、:1067-1135 稳态 flush、:1553 bottomPinnedExpandSkip、:1621-1677 GeometryNode 记账);同目录 ScrollCompensation.kt(:78 cadence、:80-99 统一谓词、:108-117 让位、:126-190 ledger、:264-350 帽、:362-537 flush task、:620-664 反射);ChatMessageList.kt(:210-214、:492-494、:670-678、:1769、:1850/:2189/:2419/:2498);MessageCardAssistant.kt(:374/:457 提问卡、:398-405 流式平铺、:1291-1295 退役注释);markdown/MarkdownTable.kt:222-230(stagedLimit);data/repository/handler/MessageEventHandler.kt:80-81,342-349(cadence 消费)
- **测试**:app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/components/CardExpandClockTest.kt:390-510(7 个 #430 用例);StreamingGrowLedgerTest(16 用例,journal 435:12)
- **提交**:c0754035(#430 根修)、7d5cd5fc(过程卡片退役)、010363f2(#432 覆写)、af428686(#435)、300db984/d28360d8/d1e99dc7/a7daa1b0/cb733d80/12513603/5d061df2(#437 系 R1-R4/终审链)、e76aed43(#446)

### 关联卡片备注

- **#429↔#430**:#429 v4 分批是 #430 主诉的显性化引信(增长跨越 settle 窗);#430 反过来约束 #429 的展示形态(过程默认展示→#431 滚动穿表)。backlog.md:142-144。
- **#432 覆写 #430**:思考默认展开→默认收起(commit 010363f2)。
- **#442(高度引擎根修二期)↔#430**:cadence 收编(S4)与 StreamingPairingRule 缝退役(S3)已完成于 5d061df2/12513603,但卡片文字仍列为「Medium 欠账」(backlog.md:179-180)——**卡片文字滞后于代码**;其待复核项(flush 八职责深拆/ScrollQuiescence 单例假设/HeldTail 锁高裁剪视觉等价)与本报告 §4.2(2)(4) 同域。
- **#437↔#444**:#444 是 #437 系修复后的回归(backlog.md:276-278),与 #430 共享配对 set 管线——本报告建议的方案 A 复核窗同时服务三卡(#430/#444/#442 待复核项)。
- **#446/#449(已完结,同管线时序族)**:毕业 held 收缩时序(journal 446)与「流式开始滚底=turn 边界 item 坍缩 clamp」勘误(journal card-intervention:94-100)提示:该管线的历史行为屡有「看似 A 实为 B」的归因反转,方案 A 复核时应保持判据开放,先取证后结论。