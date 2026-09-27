# 2026-09-28 · #433 滚动死锁（fling+tap 后 LazyList 完全无滚动响应，跨重装持久）调研报告

> **一句话结论**：#433 的主嫌疑已从卡片原始猜测「持久化状态触发测量/布局死循环」转向一条时间线严丝合缝的替代链——事发（09-25 00:07）恰好落在 **#430「思考卡默认全展开」（09-24 生效，DataStore 持久化 → 跨 install -r 存活）× clickableMarkdown 旧实现 detectTapGestures 消费 down 致外层手势全灭（22f3e68d 于同日 18:55 才根修）** 的 ~24 小时交叠窗内：全屏思考卡 markdown 正文把「起于文本的滑动」全部在 down 事件上吞掉，LazyList 拿不到 unconsumed down → 任意方向零滚动响应；重启/重装后 DataStore+Room 重构同一现场，pm clear 毁数据即消失。两腿修复同日落地后死锁再未复现。建议：双包 A/B 重建现场定罪（S）+ 手势消费审计与回归钉（S-M）收口销案；不应直接进入滚动引擎重构（现役配对/租约机器与「零响应」症状不同族，见 §4.4）。

- 调研代理产出（只读调研，未改任何源码/配置）
- 关联卡片：#432（死锁首发现场）/ #430、#429（暴露窗制造者）/ #437（22f3e68d 修复提供方）/ #444、#438②（fling 同域但不同症状族）/ #245（同症状家族先例）
- 前置调研：本报告站在 docs/research/433-streaming-pipeline-map.md、433-height-engine-map.md、433-compose-viewport-research.md、sse-scroll-stability-iron-laws.md 之上做增量，不重复其内容

---

## 1. 问题陈述

### 1.1 用户可感知症状（backlog.md:64-65 原文）

- 2026-09-25 00:07，**fling 进行中 tap 思考卡**后，消息列表对**任意方向滑动零响应**。
- 仪器观察：MIUI Input 事件送达 app；无 ANR；CPU 仅 ~20%。
- 恢复尝试全部失败：force-stop 重启、卸载重装（`install -r` 保留数据）均不恢复。
- `pm clear` 后现场丢失，无法复验——卡片自此挂起，等待重建环境。

### 1.2 触发条件（唯一一次现场）

- 前置：会话内有思考卡（reasoning part），当时**默认全展开**（见 §2.3）。
- 动作：fling（惯性滚动）中 tap 思考卡。
- 环境：#432 批次同期注记「opencode 服务器 API 漂移（SSE 返 HTML）」（backlog.md:103）——服务器侧异常与死锁并存但未证因果。

### 1.3 「跨重装持久」的含义

`install -r` 保留应用数据（DataStore + Room），`pm clear` 全清。因此死锁若随数据存活，其载体必在**持久化层**（DataStore 设置 / Room 消息内容），或在数据依赖的 UI 重构路径上——进程内状态（滚动互斥锁、租约、展开集合等）force-stop 即灭，不可能承载该症状。这是全案最重要的筛子。

## 2. 已知历史（逐条带引用）

### 2.1 登记与同日时间线（git 考古 + backlog）

| 时刻（2026） | 事件 | 证据 |
|---|---|---|
| 09-23 22:21 | d3462892 #429 L0 交付（表格 DisableSelection + 长按菜单时代开启） | git log；437 journal:36 考古 |
| 09-24 | #430 裁决「过程默认展示」→ `expandReasoning` 默认 **true** | backlog.md:119-123；010363f2 diff 中的旧注释「#430 过程默认展示:默认 true」 |
| 09-25 00:07 | **死锁事发**（fling+tap 思考卡） | backlog.md:65 |
| 09-25 00:39 | 0f708b30 登记 #433（嫌疑：持久化状态 expanded 集合/DataStore 触发测量/布局死循环或滚动消费悬挂） | backlog.md:64-65 |
| 09-25 03:12 | fca689d7 贴底展开免派发；944a89ec journal 加「滚动死锁勘误」**空节标题**（勘误内容从未写入） | git show 944a89ec |
| 09-25 03:25 | 010363f2 **思考卡默认收起**（用户裁决覆写 #430，理由原文「展开态思考卡占屏过高」） | git show 010363f2（SettingsDataStore.kt diff） |
| 09-25 04:04-05:19 | 66a226ce stream-instant 守卫；95451268 #433 高度引擎统一化登记；af428686 #435 落地 | git log |
| 09-25 18:55 | **22f3e68d 长按手势全局失效根修**——clickableMarkdown 的 detectTapGestures 消费 down 致外层手势全灭（真机双实证） | git show 22f3e68d；437 journal:34-39 |

此后（09-26/27 全部 journal 与 backlog）**无任何死锁复发记录**（grep 死锁/锁死/零响应/滚不动 于 2026-09-26、09-27 journal 零命中）。

### 2.2 取证沉淀现状

- #432 journal（docs/journal/2026-09-25-2026-09-25-432-collapse-drift.md:9-18）当前**只有空节标题**；git 考古（0f708b30 创建、944a89ec/997560d7 追加）证实该文件**自创建起各节即为空**——死锁取证从未落 journal，唯一记录=卡片 note。
- #432 卡片（backlog.md:102-103）与 #433 为同一事件：「②滚动死锁(fling+tap后列表锁死,跨install -r持久,pm clear毁现场未定位)」。

### 2.3 事发时代代码形态（影响嫌疑评估）

- 事发时 HEAD ≈ 0f708b30^：流式补偿仍是 **PreRenderShiftChannel + DeferredRevealCompensator + GUARD 250ms 去抖** 时代（#435 引擎统一化于 04:30 才落地）；三份既有 433 调研（streaming-pipeline-map §3-§5）即该时代的全图。
- 事发时 `expandReasoning` 默认仍为 **true**（#430 生效中；03:25 才翻回 false）。
- 事发时 clickableMarkdown 仍为**消费 down 的 detectTapGestures 旧实现**（修复在 18:55）。该实现自 84476ccd（07-31 rebrand，即项目初期）就在，但「铺满视口的大面积正文」直到 #430 全展开才出现（见 §4.1 反证检查）。

### 2.4 既有调研与本卡的关系

- 三份 433-*.md 调研是 #435 的侦察产物（管线图/引擎图/官方视口语义），**均未覆盖手势消费路径**——本报告补的正是这块增量。
- docs/research/2026-09-28-issue438-research.md:123（R-2）与 2026-09-28-issue442-research.md:3、:155-157 交叉发现：**shouldYieldPairing 让位防御死接线**——记忆变量 lastSetFii/lastSetFiso 声明在 PreDrawFlushTask lambda 体内每次重置为 null，永不触发。该缺陷仍在当前代码（见 §3.4），与本卡同属滚动域但症状族不同（§4.4）。
- docs/research/sse-scroll-stability-iron-laws.md（AGENTS.md:104-112 铁律的完整回归史）界定了修复方案的合规边界。

## 3. 现状代码走读（当前 HEAD 关键路径）

### 3.1 列表手势链

- LazyColumn 挂载：ChatMessageList.kt:2307-2322，修饰符链 = `fillMaxSize → testTag(#149) → megaDeltaScrollGuard(2321) → pointerInput detectTapGestures(onTap=收起键盘)(2322)`。
- `megaDeltaScrollGuard`（ScrollIsland.kt:33-67）：#245 巨帧守卫，在 **Initial 隧道趟**（parent→child，先于子滚动器 Main 趟）监看，仅对单帧 >300px 的病态合并增量切片直派 `dispatchRawDelta`（:45-52），健康帧零触碰；其头注（:10-17）沉淀了 2026-08-27 真机「拖动全灭」家族取证（§4.2）。
- 外层 `detectTapGestures`（:2322）挂在 LazyColumn **用户修饰符**（外层节点）上：Main 趟分发顺序为内→外，内部 scrollable 先见到 down，故**不构成**列表滚动阻塞（与叶子消费 down 的情形本质不同，见 §4.1 机制注）。

### 3.2 思考卡装配与展开态来源

- 装配：PartContent.kt:196-213——`rbExpanded = toolExpandedOrDefault(part.id, expandReasoningDefault)` → `ReasoningBlock(isExpanded=rbExpanded, ...)`（:205）。
- 折叠入口：ReasoningBlock.kt:223 头部 Row `.clickable { }`（正文=SelectionContainer，:343 起正文渲染走 `MarkdownContent`）。
- 展开态两层来源：
  - **会话内显式展开集合**：`toolExpandedStates` = 内存 MutableStateFlow（MessageDataDelegate.kt:152-153；ChatViewModel.kt:653-659 透传；ChatScreen.kt:631 注入）——**进程死即清空，非持久化**。
  - **默认值**：`expandReasoning` = DataStore 布尔（SettingsDataStore.kt:53 key、:262 `prefFlow(EXPAND_REASONING_KEY, false)`、:394 snapshot 兜底）——**跨 install -r 存活**；当前默认 false（#432 裁决，010363f2）。

### 3.3 markdown 正文手势（事发嫌疑点现状）

- 正文段落/代码块/表格单元的链接点击统一走 `clickableMarkdown`（MarkdownContent.kt:464、487、521、907；MarkdownTable.kt:347、600）。
- 当前实现（ClickableMarkdown.kt:204-262）已是 22f3e68d 修复版：`awaitEachGesture { awaitFirstDown(requireUnconsumed=false); waitForUpOrCancellation() ... }`（:245-259）——**down 不消费**，仅命中链接时消费 up；修复注释（:239-244）完整记录了旧版「等待 tap 期间消费 down（consumeUntilUp 家族）→ 同树外层手势全部失效」的定罪链（真机探针 Main 相 Press consumed=true）。

### 3.4 高度引擎配对路径（现代，#435/#437 时代）

- flush 任务：`streamingGrowFlushTask`（ScrollCompensation.kt:362-537），ChatMessageList.kt:670-682 常驻注册进 PreRenderCoordinator 单点；`streamingGrowPairing` 记账挂载 4 处（ChatMessageList.kt:1850、2189、2419、2498）。
- 统一配对规则 `StreamingAnchorRule.pairedDelta`（ScrollCompensation.kt:80-99）：贴底原点/读历史免派发，锚==增长源才 +Δ——即 AGENTS.md:111 铁律。
- **用户滚动让位**：flush 相一遇 `isScrollInProgress` 即 `ledger.rebaseAll()` 弃配（ScrollCompensation.kt:459-462）——引擎配对**从构造上不与用户手势对抗**。
- **shouldYieldPairing 死接线（#438②/#442 交叉发现，当前仍在）**：`lastSetFii/lastSetFiso` 声明在 lambda 体内（:367-369），读点 :467（set 之前求值）、写点 :518-519（跨帧意图），每次 onPreDraw 重置 null → 让位分支不可达——但受影响的是「外部显式滚动意图 vs 引擎配对 set」互搏（#444 域），非用户手势通道。
- 反射通道 `LazyListReflection.requestScrollToItemNoCancel`（:638-664）：绕过官方 scroll{} 互斥锁只写待定位置；BOM 绑定注记 :547。

### 3.5 滚动控制器让位链

- ChatScrollController.kt：双键 snapshotFlow（isScrollInProgress × isAtBottom，:127-157，AGENTS.md:112 铁律的载体）；MSGEFFECT（:162-263）与 GUARD（:218-260）让位于 jumpLock/lease/streamingActive（:175-177、:197、:229-231）；ForceScroll/PENDING 同构（:265-310）。**所有让位只静默程序化锚定，不触碰用户手势通道**。

### 3.6 其余全屏/边缘手势消费点（审计清单）

- ChatFabMenu.kt:417-423：菜单展开时的**全屏透明拦截层**（`if (expanded)` 条件组合，detectTapGestures 收起）——设计意图，但属「全屏消费面」形态。
- TerminalDrawerEdgeGesture.kt:30-56：屏缘 18dp 竖条 detectTapGestures(onLongPress) + detectHorizontalDragGestures——**小面积**，但 onLongPress 变体的 detectTapGestures 同属 down 消费家族，起于该条的垂直滑动理论上同样无法成 drag（面积小、未被报告）。
- ChatMessageList.kt:2322 外层键盘收起 tap 检测器：如 §3.1 所析不阻塞滚动。

## 4. 根因分析

### 4.1 H1（主嫌疑）：手势吞没 × 持久化默认展开的「数据态现场重构」

**机制链（每环都有仓库内一手证据）**：

1. 事发时 `expandReasoning` 默认 true（#430，09-24 生效）→ 思考卡全展开 → 长推理正文经 MarkdownContent→clickableMarkdown **铺满视口**（ReasoningBlock.kt:343；MarkdownContent.kt:464/487/521/907）。暴露面是 #430 当天才造出来的。
2. 事发时 clickableMarkdown 为消费 down 的旧实现：等待 tap 期间消费 down（Main 相 Press consumed=true，真机探针定罪，437 journal:34-38；修复=22f3e68d，**晚于事发 18h48m**）。
3. LazyList 拖拽检测器需要 unconsumed down 才能起 drag（scrollable 家族语义；433-compose-viewport-research.md §1 链接的 androidx 源码域 + ScrollIsland.kt:19-21 对分发趟序的勘误性认知）→ **起于正文的任意方向滑动全部无法成 drag** → 「列表零滚动响应」。
4. 持久化解释：DataStore(expandReasoning=true) + Room(消息/推理内容) 在 install -r 下存活 → 每次重进会话，思考卡重新全展开、同一现场**重构**——不是进程态存活，而是**数据态重构**，与 §1.3 筛子完全一致。pm clear 清掉两库 → 现场消失。

**与全部观测吻合**：MIUI Input 事件送达（事件确实进了 app，被叶子 tap 检测器在 Main 趟消费）；无 ANR（主线程健康，tap 检测器正常运转）；CPU ~20%（无忙循环）；「fling 中 tap」只是用户注意到异常的时刻（fling 会自然衰减；tap 若落在头部 Row 折叠仍生效——叶子 clickable 与正文 tap 检测器是不同节点）。

**为什么此前数月无人报告「正文滑不动」（反证检查，待验证）**：clickableMarkdown 自 07-31（84476ccd）即为旧实现，但 (a) down 消费行为是否随某次 Compose BOM 升级才引入（现 BOM 2026.08.00，反射绑定注记记 2026.05.01，ScrollCompensation.kt:547）——若为库行为变更，暴露窗从升级日起算而非项目初期；(b) #430 之前思考卡默认收起、正文密度低，用户滑动多起于非 markdown 区域，bug 潜伏。**验证方法**：检出 d3462892（09-23）与 84476ccd 后多个中点版本装包，对同一段 assistant 正文做「起于正文的上滑」对照，定位 down 消费行为的确切引入版本（BOM 升级时间线可在 git log -p app/build.gradle.kts 中核对）。

**置信度**：机制存在性=**高**（真机探针双证 + 同日修复）；「即 #433 本因」=**中高**（时间线/症状/持久化三方面全吻合，但现场已毁、无法直接复验——正是卡片挂起的原因）。

**与既有调研的分歧标注**：卡片原始嫌疑（backlog.md:65「expanded集合/DataStore 触发测量/布局死循环或滚动消费悬挂」）中，DataStore 一半被本假设继承（但角色是「暴露面持久化」而非「死循环触发器」）；「测量/布局死循环」一半被 CPU~20% 证据削弱（满负荷测量环应打满单核并趋向输入超时/ANR）。三份既有 433 调研聚焦的流式管线/引擎域**不承载**本症状（§4.4）。

### 4.2 H2（次嫌疑，同症状家族先例）：#245 巨帧冻结

ScrollIsland.kt:10-17 取证（2026-08-27 真机）：冷启动进场后平台把 2.5s 拖动合并成 2-3 个巨型单帧 move（~850px/帧）送达，「列表 scrollable 认领手势（isScrollInProgress=true）却对巨帧零消耗——idx/off 钉死，拖动全灭；轻点/键盘/程序化滚动不受影响」。形态与 #433 高度相似（MIUI 平台侧输入异常、事件送达、无 ANR）。**但不解释持久性**：平台输入合并是瞬态，且 pm clear 不改变平台行为。判定：同症状家族参考项，非本因。置信度：形态相似=高；为本因=低。守卫（megaDeltaScrollGuard）对 #433 形态是否有效**效果存疑（仓库自注记，ScrollIsland.kt:17）**。

### 4.3 H3（低置信，保留待验证）：持久化状态触发测量/布局死循环

卡片原始嫌疑的另一半。反对证据：CPU ~20%（测量环会打满主线程）；零响应期间 tap 语义据报道正常（「fling+tap 后」tap 已完成）。无任何仪器证据支持。若要证伪/证实：复现时抓 `gfxinfo framestats` + Choreographer 日志（帧率正常即排除）。置信度：低。

### 4.4 H4（排除为「零响应」本因，但同域交叉）：现代配对/租约机器

现役代码中与 fling 相关的已知缺陷——shouldYieldPairing 死接线（§3.4；#438②/#442）、#444 fling 下滑跳变复发（backlog.md:276-278）——症状形态均为**视口跳变/被拽回**，而非手势零响应；且用户滚动遇 `isScrollInProgress` 即 rebaseAll 弃配（ScrollCompensation.kt:459-462）、租约只静默程序化锚定（ChatScrollController.kt:175-231）——**引擎域从构造上不阻塞手势通道**。判定：与 #433 不同症状族；若未来重建现场时出现「拉不动+被拽回」混合形态，再回头并入 #444 联合定罪。

### 4.5 残余风险面（当前代码仍开着的口子）

1. TerminalDrawerEdgeGesture 18dp 边缘条的 down 消费（§3.6）——小面积同族隐患。
2. ChatFabMenu 全屏 scrim 依赖 `expanded` 布尔正确复位（§3.6）——若状态机异常+透明层滞留，形态即「全屏零响应」；当前无滞留证据。
3. clickableMarkdown 修复依赖「库行为不再变」——BOM 升级若改变 awaitEachGesture/消费语义，同类死锁可复发；LazyListReflectionTest 式的签名钉死（ScrollCompensation.kt:554-555 先例）未覆盖手势语义。
4. 「大面积叶子消费 down」无任何 lint/测试规约拦截新增违例。

## 5. 修复方案空间

### 方案 A（保守·防御闭环）：手势消费审计 + 回归钉死（工作量 S-M）

- **内容**：①全库手势消费者清点（§3.6 清单为起点），按「消费面面积 × 是否消费 down」分级，大面积叶子一律改 22f3e68d 模式（down 透传、命中才消费 up）；②把「大面积叶子禁消费 down」写入 AGENTS.md/chat-ui-event-lifecycle.md 的规约区；③为手势语义加 BOM 升级护栏（仿 LazyListReflectionTest 的 JVM 钉死思路，对手势断言用 compose-ui-test）。
- **TDD 切入**：先写不变量测试——「起于 markdown 正文的垂直 swipe 必然改变 listState.firstVisibleItemIndex / 产生 scroll delta」+「思考卡头部 tap 可折叠」两个锚，当前 HEAD 应绿（钉住现状），任何让它们变红的改动即回归。
- **影响面/风险**：零生产行为变更（当前已修），纯防御；与 SSE 铁律零冲突（不触碰 AGENTS.md:104-112 任何一条）。
- **兼容性**：完全兼容既有能力（不动引擎/管线）。

### 方案 B（激进·手势架构收编/重写）（工作量 L）

- **内容**：仿 PreRenderCoordinator 收编视口写入的先例（#423），把散布的手势消费（clickableMarkdown/MarkdownTable/ReasoningBlock 头部/边缘手势/FAB scrim）收编为**单一手势路由层**：叶子只声明意图（链接区/菜单区/折叠区），down 消费策略由路由统一裁决（默认透传，命中确认后才消费 up/长按后置消费）；配套「手势树 dump」调试工具（列出全树各节点的 pass/consume 策略，死锁再发时一照即知）。更激进的变体：思考卡正文改 Canvas 直绘/原生容器，整体退出 pointerInput 树。
- **TDD 切入**：先为每类手势面写参数化消费契约测试矩阵（down/move/up × 命中/未命中 × 手势进行中），红绿重构——契约先行，迁移不破坏任何一条既有交互（表格复制菜单/正文选择/链接点击，即 22f3e68d 复活的三能力）。
- **影响面/风险**：大——ClickableMarkdown.kt/MarkdownTable.kt/ReasoningBlock.kt/TerminalDrawerEdgeGesture.kt/ChatFabMenu.kt/SheetGestures.kt 全动，回归面=全部触摸交互；#429 长按菜单、#437 修复语义都压在这些路径上。收益是根治「消费策略散布」的结构性缺陷。
- **兼容性**：与 SSE 铁律正交（不动流式管线）；但须避开 chatscreen-editing-protocol 的并行编辑禁区，批次成本高。

### 方案 C（中道·本卡直接收口）：双包 A/B 重建现场定罪（工作量 S）

- **内容**：git worktree 检出 `0f708b30^`（事发版）构建安装真机 A 包；HEAD 构建 B 包。构造近似现场（服务器放一段长推理输出 + A 包设置 expandReasoning=true），执行「fling 中 tap 思考卡 → 任意方向滑」序列：A 复现零响应、B 不复现 → H1 定罪成立，#433 销案；A 也不复现 → 转向 H2/H3（补 gfxinfo/FreezeDiag 探针取证，ScrollIsland.kt:56-61 的 ratio 探针现成）。
- **TDD 切入**：无代码测试，产物是「A/B 对照取证记录」写回 journal（补上 944a89ec 那节欠了三天的勘误）。
- **影响面/风险**：只读取证；唯一成本是真机时段与构造长推理数据（事发原会话已毁，用近似数据）。
- **限制**：近似数据 ≠ 原现场，若 A 包不复现只能收窄而不能完全销案（如实注记）。

## 6. 建议

**推荐：C 先行、A 跟进、B 暂缓。**

1. **C（S，一次真机时段）**：H1 的时间线证据已足够强，双包 A/B 是唯一能把「中高置信」抬到「定罪」的低成本动作；同时偿还 #432 journal 的空节欠账。
2. **A（S-M）**：无论 C 结果如何都值得做——修复已在但无回归钉，BOM 升级与新增手势面是现实复发通道；两枚不变量测试是最低限度的护栏。
3. **B（L）仅当触发条件**：A 审计发现 ≥2 处新的大面积 down 消费违例，或 #433 形态在 HEAD 上复发且定罪指向手势架构本身——否则不为单一历史事件启动 L 级重构。
4. 与其他卡片的联动处置：#444/#438② 的配对域问题与本卡分案处理；若 C 取证时观察到「拉不动+拽回」混合形态，在 #444 卡下合并定罪；#245 的巨帧守卫有效性（仓库自注「效果存疑」）可顺带在同场真机验证（FreezeDiag ratio 探针现成）。

## 7. 引用清单

**仓库文档**：
- backlog.md:64-65（#433 卡）、:87-91（#438）、:96-100（#435）、:102-103（#432 含死锁同事件）、:119-123（#430 默认展示裁决）、:146-154（#428 同族背景）、:276-278（#444）
- docs/journal/2026-09-25-2026-09-25-432-collapse-drift.md:9-18（空节标题，勘误欠账）；docs/journal/2026-09-25-437-streaming-md-stable-reveal.md:34-39（down 消费定罪链+真机双实证）、:47-50（fling 家族未复现记录）、:812-829（四十五世轮 fling 取证）
- docs/research/433-streaming-pipeline-map.md（事发时代管线全图）、433-height-engine-map.md（引擎体系地图）、433-compose-viewport-research.md §1-§3（androidx 滚动语义一手源）、sse-scroll-stability-iron-laws.md（铁律回归史）
- docs/research/2026-09-28-issue438-research.md:8、:53、:123（shouldYieldPairing 死接线 R-2）；2026-09-28-issue442-research.md:3、:114-157（同发现交叉）；AGENTS.md:104-112（SSE 铁律）

**源码（当前 HEAD）**：
- ChatMessageList.kt:422-427（streamingMsgId）、:670-682（flush 宿主/任务注册）、:1850/:2189/:2419/:2498（配对挂载）、:2307-2322（LazyColumn 手势链）
- ScrollCompensation.kt:80-99（StreamingAnchorRule）、:108-117（shouldYieldPairing）、:362-369（flush 任务与死接线变量）、:459-462（用户滚动 rebaseAll）、:467-475（不可达让位分支）、:518-519（lastSet 写点）、:547（BOM 注记）、:638-664（反射通道）
- ChatScrollController.kt:35-37（双键铁律注）、:127-157（双键 effect）、:175-231（lease/streaming 让位）、:265-310（ForceScroll/PENDING）、:436-476（AutoScrollArbiter）
- ClickableMarkdown.kt:204-262（现行手势实现+修复注释）；MarkdownContent.kt:464/:487/:521/:907（挂载点）；MarkdownTable.kt:347/:600
- ReasoningBlock.kt:223（头部 clickable）、:343（正文 MarkdownContent）；PartContent.kt:196-213（展开装配）；ChatScreen.kt:631/:1343/:1354（Local 注入）
- SettingsDataStore.kt:53/:262/:394（expandReasoning 持久化）；MessageDataDelegate.kt:152-153（内存展开集合）；ChatViewModel.kt:653-659（透传）、:1197-1201（卡死流式修复）
- ScrollIsland.kt:10-17（#245 拖动全灭取证）、:33-67（巨帧守卫）；ChatFabMenu.kt:417-423（全屏 scrim）；TerminalDrawerEdgeGesture.kt:30-56（边缘条）

**git 提交**：84476ccd（07-31 旧实现引入）、d3462892（09-23 22:21 #429 L0）、0f708b30（09-25 00:39 #433 登记）、fca689d7/944a89ec（03:12）、010363f2（03:25 默认收起）、af428686（04:30 #435）、22f3e68d（09-25 18:55 手势根修）

**外部一手源**（经 433-compose-viewport-research.md 转引，本次直连 raw.githubusercontent 网络不通未能复核原文，如实注记）：androidx ScrollableState.kt / LazyListState.kt / LazyListMeasure.kt（ gestures 包源码链接见该文档 §附）
