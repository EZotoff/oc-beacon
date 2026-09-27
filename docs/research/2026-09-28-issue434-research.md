# #434「贴底构型收起镜像 dispatch 0 消费→上方内容裸下移 H px（含 #432）」深度调研报告

> 日期：2026-09-28 · 调研代理产出（只读调研，未改任何源码；唯一写入=本报告）
> 关联卡片：#432（本卡即其根因定罪载体）/ #435 / #442 / #438② / #444 / #430 / #423（其 R-A 点名本卡为唯一 P0 级引擎缺陷）

## 一句话结论

**#434 的机制层定罪成立且至今未修：严格贴底（fii==0∧fiso==0）下收起的补偿性 dispatch（−H，向最新侧）在滚动位 0 处被框架钳制成 consumed=0，PairedDispatch 按「物理不可消费」放弃（CardExpandReveal.kt:181），reverseLayout 锚定把无补偿塌缩全额转译为上方旧内容下移 H px（542px 录屏定罪）；且本报告推导（journal:156 数学锚定）该缺陷在「锚点恢复路径」同样成立（不止镜像 dispatch 路径），而卡片提议的「换向 dispatch +H」按既有真机数学是把位移反向放大而非补偿（±H 配对守恒式对符号敏感），方向必须先经一轮真机实验终审再动刀；贴底塌缩的视口平移在 LazyList 语义内物理不可约（P<0 非法），真正的方案空间是「产品级处理（接受/滑移动画）+ 把收缩×贴底语义收编进 StreamingAnchorRule 决策表」，激进选项为配对执行体系统一重写（与 #423 方案 C/#442 合流）。**

---

## 1. 问题陈述

### 1.1 用户可感知症状

- **贴底收起思考卡时整段对话向下跳**：用户停在列表最底（正在看最新回复），点击收起一张已展开的思考卡（或步骤组/工具卡），卡片塌缩的同一帧，**上方所有旧内容裸下移 H px**——视口顶部涌入更早的段落，观感为「整个对话往下掉了一截」。DSH 连接态录屏帧 94→101 判读确认「顶部露出更早段落」，位移 542px（backlog.md:62）。
- 单帧完成（批次十四收起镜像化后闭合为单步原子操作，无中间帧），无震荡、无白屏——是一次干净但巨大的跳变。
- 中位构型（fii>0）无此症状：dispatch 可消费、配对守恒（DSH 矩阵 1-6 全绿，backlog.md:62）。

### 1.2 触发条件

- **视口态**：严格贴底 fii==0 ∧ fiso==0（bottomPinnedExpandSkip 判据，CardExpandReveal.kt:1553）。
- **动作**：收起（collapse）一张已展开的**已完结 turn 内**可展开卡（流式 turn 内卡片降级裸 AV 不走引擎，CardExpandReveal.kt:63-64/90；2026-09-27 实测流式 item 内 toggle 走 SGR ledger 单通道，docs/journal/2026-09-27-card-intervention-growth-phase.md:28-35）。
- **频率**：「用户流式场景常处贴底=高频触发」（backlog.md:62）——流式期用户默认贴底跟随，收起历史卡是常见操作。
- 取证捕获到的入口是**镜像 dispatch 路径**（paired-shift 日志 consumed=0）；本报告推导锚点恢复路径同症（见 §4 R-3，待验证）。

### 1.3 卡片定位

- 卡片原文 backlog.md:61-62：P0 区，chat-ui,bug，状态 [ ]，标题即定罪结论；修法候选「consumed==0且贴底时换向dispatch +H…候选实现:PairedDispatch加方向fallback;需真机验证方向+防双发」。
- #423 今日调研把本卡列为「当前唯一 P0 级引擎缺陷」（docs/research/2026-09-28-issue423-research.md:327，R-A）。
- 登记 commit 1de9f308（2026-09-25 01:02，「#432 贴底收起0消费根因定罪(DSH连接态双证)」）——明细只落在卡片 note；对应 journal（docs/journal/2026-09-25-2026-09-25-432-collapse-drift.md）四节标题至今全空（:9-18），原始 logcat/录屏随原 session 环境丢失（handoff 记载环境全失，journal 2026-09-27-card-intervention-growth-phase.md:5）。

---

## 2. 已知历史（取证/定罪/部分修复，逐条带引用）

### 2.1 时间线速查

| 时刻 | 事件 | 证据 |
|---|---|---|
| 2026-09-20 | #420 引擎落地：KDoc 明文记载「收起负向 δ 在贴底不可消费时返回残量（物理不可约:防列表尾露空白）——该分支视口由上方内容吸收，与现状一致」——**本症状当时作为已知行为被接受** | CardExpandReveal.kt:59-60,48-50 |
| 2026-09-22 | 批次十一确立配对数学：「增长 δ 上移折叠行 δ + 滚动位前进 δ 下移 δ = 净零」 | journal 2026-09-22-423:156 |
| 2026-09-24 | #430 定罪瞬态 0 消费族（展开侧）：19:34/19:46/22:16 三次冷展开 consumed=0 tries=0 | journal 2026-09-22-423:612-622；issue430-research:59 |
| 2026-09-24 | #427 残差重试落地：PairedDispatch「0 消费=物理边缘终止」语义入代码+单测 | CardExpandReveal.kt:161-184；CardExpandClockTest.kt:50-72 |
| 2026-09-24 | 批次十四收起镜像化：收起=幕布收拢+单步原子闭合+锚点恢复/镜像位移兜底 | CardExpandReveal.kt:848-944；journal 2026-09-22-423:277-405 |
| 2026-09-24/25 | #432 十五轮仪器矩阵（3卡型×toggle×连点×交替×录屏）「全部判绿（锚点±4px守恒）」——**判据是锚点守恒，对上方内容位移盲** | backlog.md:103 |
| 2026-09-25 | #432 贴底展开跳转修复（skip-dispatch，2da3b6d0/fca689d7 prewarm 修复同批）+ steady 相贴底豁免补齐（#435） | CardExpandReveal.kt:786-803,1076-1089 |
| 2026-09-25 01:02 | **#434 立卡**：DSH 连接态 paired-shift consumed=0 实锤 + 录屏 542px 双证；矩阵 1-6 中位全绿对照 | backlog.md:61-62；commit 1de9f308 |
| 2026-09-25/27 | #435 统一化 + #437 系（帽/统一谓词/单出口/键回写/让位防御）——流式侧大改，episode 侧收起路径未动 | issue430-research:85-99 |
| 2026-09-27 | episode×流式并发「无双发」裁决；#449 勘误（流式期 MSGEFFECT/GUARD 静默） | journal card-intervention:56-63,79-117 |
| 2026-09-28 | 今日五份调研：#423 把 #434 列为 P0；#438/#442 发现让位防御死接线与 null-key 裸 index 通道（同域交叉，见 §4.5） | issue423:199-205；issue438:121-127；issue442:153-162 |

### 2.2 关键取证逐条

1. **定罪主证（DSH 连接态，2026-09-25）**：贴底收起时镜像 dispatch −H 在「新侧」（最新侧）无空间，consumed=0（paired-shift 日志：[DEBUG-427] paired-shift target=−H consumed=0，打点位于 CardExpandReveal.kt:202-208）→ 塌缩无补偿 → 上方旧内容裸下移 H 涌入视口；录屏帧 94→101 判读「顶部露出更早段落」542px。中位构型 dispatch 可消费故守恒（矩阵 1-6 全绿）（backlog.md:62）。
2. **#432 主诉的最终归属**：#432 用户主诉「收起时高度变化+双向偏移竞态」，十五轮矩阵未复现（判据=锚点±4px 守恒，塌缩单帧）——本报告判读：**该判据对「上方内容位移」天然盲**（锚点 (0,0) 在此位移中恰恰不动，见 §4 R-1 几何），故 #434 的录屏帧差判读（cv2 中列带互相关）才是看见该症状的正确仪器；#434 标题「含#432」即此收束（backlog.md:61,103）。
3. **#420 时代的知情接受**：引擎 KDoc 把「贴底收起负向 δ 不可消费→视口由上方内容吸收」记载为「与现状一致」的物理不可约分支（CardExpandReveal.kt:59-60）——补偿族当时两度被用户否决（issue423-research:100-102），#420/#427 建立配对守恒语义后，该分支成为守恒体系的洞，#434 正式定罪。
4. **0 消费语义的固化**：#430 展开侧定罪「瞬态 0 消费」（增长晚一帧落地→dispatch 内测见旧高→按物理不可消费放弃，19:34 上顶 2852px）并以欠账 rebase 根修（issue430-research:57-69）；但 PairedDispatch 的 0 消费终止语义对「真·物理边缘」（贴底钳制）与「瞬态未落地」不分——#430 修了后者（落地门+欠账），#434 是前者，两者共用 :181 的终止分支。
5. **贴底免派发原则的先例**：#432 展开侧 skip-dispatch（贴底布局以最新 item 为锚，卡向上扩展天然屏位不变，dispatch +H 反而把用户正看的最新回复推出屏=「展开跳转」主诉，CardExpandReveal.kt:786-791）+ #435 steady 相贴底豁免 drop-at-bottom（:1076-1089）——**「贴底不派发」已是引擎两侧共识**；#434 要求在收起侧开例外，这是方案设计的核心张力。
6. **并发面已排除**：episode 与流式 flush 共享 PreRenderCoordinator 单点且空间分离，实测无双发（journal card-intervention:56-63）——#434 不涉及双通道竞态，是单路径物理缺口。

### 2.3 记录盲区（如实标注）

- #434 的原始 logcat/录屏不在仓内（原 session 环境全失，journal card-intervention:5）；存活证据=卡片 note 的结论摘要 + 打点代码本身。复现配方可从 2026-09-27 重建的方法学直接继承（input swipe 100ms 同点按压注入 toggle、GrowthWatcher、mp4 帧差分类，journal card-intervention:14-18,71-77）。
- 「矩阵 1-6」的具体六构型无明细存档（仅「中位构型…全绿」一句，backlog.md:62）。

---

## 3. 现状代码走读（当前 HEAD，行号实测）

### 3.1 收起主路径（CardExpandReveal.kt:848-944，批次十四镜像化）

~~~
收起 episode（target=0f，LaunchedEffect(visible) :725，全程 withEpisode 租约 :729）
  ① 幕布收拢：draw-only 200ms（:860-872，零布局零测量）
  ② rep = clock.lastReportedH（:875）→ close-pre 日志（fii/fiso/items 快照 :876-883）
  ③ anchorKnown = !userScrollCancelled && episodeAnchorItem >= 0（:893-894）
  ④ anchorKnown → LazyListReflection.requestScrollToItemNoCancel(
        episodeAnchorItem, episodeAnchorOffset)（:896-901）
     ——待定区写入，与⑤的高度塌缩同一遍 measure 原子生效
  ⑤ withMutableSnapshot { clock.driveTo(0f) }（:911-913）单步原子闭合
  ⑥ !anchorKnown && !userScrollCancelled → 镜像位移兜底：
     backPx = −episodeShiftConsumedPx（≠0 时）否则 −rep（:919-923）
     applyPairedPreRenderShift(listState, backPx)（:924）← #434 打点现场
  ⑦ 锚点清账（:929-930）+ plain steadyRebase（:931-935，防集末 flush 双配对）
~~~

- **锚点记录**：展开 episode 在 dispatch 决策**之前**无条件记录 (fii,fiso)（:783-785）——含贴底 (0,0)；因此「贴底展开→贴底收起」走 ④ 反射路径（恢复 (0,0) 等价 no-op 待定写入），**不走⑥**。⑥的触发前提=展开集未跑到 :784（冷组合已展开/展开集中途取消）或锚点已被取消路径消费（:1156-1168）。
- **冷组合已展开态**：回收重入/持久化展开的卡以 fraction=1 冷启（KDoc :70「滑出视口回收/滑回不重播」；CardExpandClockTest.kt:25-29 coldStartExpandedReportsFull）——无 episode、anchor=−1（:391），收起必走⑥镜像 dispatch，backPx=−rep 全额。

### 3.2 配对执行器三件（0 消费的定罪点）

- applyPreRenderShift（:148-159）：展开正向恒走 dispatchRawDelta（「滚动位 0 即起点，向旧侧永远有空间、同步消费精确（真机反复实证 consumed==d）」，:149-152）。
- PairedDispatch.nextCommand（:173-184）：残差重试纯决策。**:181 if (abs(consumed) < 0.5f) return null——零消费一律终止，注释定性「物理不可消费（边缘/布局未就绪）——余量由布局吸收」**。单测钉死该行（CardExpandClockTest.kt:68-69「零消费（物理不可约边缘）→ 停止」）。
- applyPairedPreRenderShift（:190-213）：循环 dispatch + nextCommand；|consumedTotal−target|≥0.5 时打 [DEBUG-427] paired-shift target=… consumed=… tries=…（:202-208）——#434 的日志实锤即此处（target=−542 consumed=0 形态）。

### 3.3 贴底语义两侧对照（引擎现状）

| 维度 | 卡片 episode/steady 相 | 流式相（#435/#437） |
|---|---|---|
| 贴底判据 | bottomPinnedExpandSkip 严格 (0,0)（:1553；BottomPinnedExpandSkipTest.kt:16-27） | StreamingAnchorRule 原点 fiso<8px（ScrollCompensation.kt:82,92） |
| 展开向 | 贴底 skip-dispatch（#432，:792-803） | 增长免派发物理跟随（:92） |
| 收缩向 | **无任何贴底分支——镜像 dispatch −H 交给 PairedDispatch，0 消费即放弃（:181/:924）** | growthPx <= 0f -> 0f 收缩一律不配对只 rebase（:91,157）——流式侧同样放任收缩裸呈现 |
| 让位 | isScrollInProgress 弃配（:1072-1074） | 同 + shouldYieldPairing（:466-475，⚠死接线见 §4.5） |
| 执行器 | dispatchRawDelta 循环 | requestPosition 待定区+measure 原子消费（:517） |

（对照表综合自本报告实测与 issue430-research:164-173 两体系决策链对照。）

### 3.4 历史取证 → 代码位置映射

| 历史定罪 | 当前代码位置 |
|---|---|
| #420「贴底收起残量由上方内容吸收」 | CardExpandReveal.kt:59-60（KDoc 存档）+ §3.1 ⑥路径 |
| #427「收起 −20352 实消费 −14458 欠残差净漂」 | :161-171 注释 + PairedDispatch :173-184 |
| #432「贴底展开跳转」 | :786-803 skip-dispatch + :816-820 贴底欠账归零 |
| #435 steady 贴底豁免（对称漏洞补齐） | :1076-1089 drop-at-bottom |
| #434「镜像 dispatch consumed=0」 | :914-928 → :196-201 → :181 终止 → :202-208 打点 |
| #430「瞬态 0 消费」（同终止分支的另一成因） | :1094 落地门 + 欠账 rebase（issue430-research:147-148） |

---

## 4. 根因分析（论断标注证据与置信度）

### R-1 主根因：贴底收起的补偿性位移物理不可消费，守恒链在边缘断链（置信度：高）

- **机制链**：reverseLayout 惰性列表锚定 (fii=0,fiso=0)（最新 item 钉在视口底缘）。收起塌缩 −H 时，配对语义要求反向位移把上方内容**上移** H 补回（mid-list 行为：journal 2026-09-22-423:156「增长 δ 上移折叠行 δ + 滚动位前进 δ 下移 δ = 净零」——收起为镜像）；该位移方向=向最新侧滚动=滚动位 0 处被框架钳制，dispatchRawDelta 返回 0（卡片实测「在新侧无空间」，backlog.md:62）→ PairedDispatch :181 终止 → 无补偿。锚定随后把塌缩全额转译为视口内容位移：**锚点不动、上方内容下移 H、顶部露出更早段落**（542px，帧 94→101）。
- **证据**：卡片双证（日志+录屏，backlog.md:62）＋代码直读（§3.1/3.2）＋#420 KDoc 存档（:59-60）＋mid-list 对照绿（矩阵 1-6）。
- **推论（重要）**：该位移在 LazyList 语义内**物理不可约**——保持上方内容屏位不动要求滚动位 P=−H（越过列表末端），非法；任何 P≥0 的落位都保留 ≥H 的上方内容下移。#438① 的「贴底走物理跟随免派发根本不进配对 set」同理佐证：贴底构型的补偿通道天然缺位（issue438-research:133）。

### R-2 入口条件：镜像 dispatch 路径何时触发（置信度：中，待验证）

- 代码事实：⑥仅当 anchorKnown=false（:893-894,914）。触发子路径候选：(a) 冷组合已展开卡（回收重入/跨重启持久化展开，fraction=1 冷启无 episode，anchor=−1，KDoc :70）；(b) 展开集在 :784 之前被用户滚动取消（settle 窗≤600ms）；(c) 收起被滚动打断后锚点被消费（:1156-1168）再收起。
- DSH 捕获到的 paired-shift 日志证明当时走了⑥，但**原始日志已丢失**，无法区分 (a)/(b)。**待验证**：真机复现时看 [DEBUG-427] close-pre 行（:876-883 含 fii/fiso 快照）与 close-anchor-request 行（:902-908）的有无即可判别。

### R-3 新发现（本报告推导）：锚点恢复路径同症，缺陷面大于卡片描述（置信度：中高，待验证）

- 推导：贴底展开（skip-dispatch）仍记录 anchor=(0,0)（:783-785 在 #432 分支**之前**）；随后贴底收起走④反射恢复 (0,0)——对已在 (0,0) 的视口是 no-op 待定写入，塌缩后锚定结果与⑥完全同构：锚点不动、上方内容下移 H。**即：贴底展开→贴底收起的最常见路径根本不产生 paired-shift 日志，但症状相同**——#434 的取证恰好捕获了带日志的⑥，修⑥（卡片候选「PairedDispatch 加方向 fallback」）**修不全**。
- 佐证：#430 真机终验的收起构型是 mid-list（close-pre (20,36335)→close-post (19,190)，issue430-research:73），贴底收起从未在终验矩阵中单列。
- **待验证**：真机「贴底展开→贴底收起」（anchor 已知路径）录屏帧差，预期同样 542px 级位移且无 paired-shift 行。
- **附带发现（低置信，待验证）**：episode 锚点是**裸 index**（episodeAnchorItem: Int，:391）——展开与收起之间若有 item 插入（流式期新 turn 进场是常态），index 平移使恢复落点错位；与 #438② 定罪的 requestPositionAndForgetLastKnownKey 键保持通道（issue438-research:50,121）同族风险，mid-list 构型下可产生错位恢复。

### R-4 修法方向的符号疑点：卡片候选「换向 dispatch +H」按既有数学是放大而非补偿（置信度：中高，方向待真机终审）

- 推导：配对守恒式（journal 2026-09-22-423:156）确立 **dispatch +δ ⇔ 内容下移 δ、dispatch −δ ⇔ 内容上移 δ**（#432 实证 +H 把最新回复推出屏=内容下移，CardExpandReveal.kt:788-789；#427 实证收起欠消费残差=对话上移，:161-167 注释）。贴底收起的裸位移方向=上方内容**下移** H（R-1）；要抵消需内容**上移** H=dispatch −H——恰是不可消费方向。**换向 +H（向旧侧，有空间）产生的是同向再位移：上方内容合计下移 ~2H，且最新回复被推离底缘**（卡片自己也预见了底缘揭示问题，才写「不露底空白——露的是旧内容」）。换言之：符号翻转同时翻转了效应方向，「旧侧有空间」救不了「方向已反」。
- 卡片原文自带对冲：「需真机验证方向+防双发」（backlog.md:62）——立卡人并未确信方向。本报告把该不确定性升级为**应当先裁决的分歧**：若真机实验证实上述数学，方案空间必须离开「补一个反向 dispatch」的框架（见 §5）。
- 与既有调研的分歧标注：#423 调研转述本卡修法时未质疑方向（issue423-research:204-205,258-259）；本报告依据 journal:156 数学 + #427/#432 双实证提出异议，**以真机方向实验为最终裁决**。

### R-5 结构性背景：收缩×贴底语义在引擎两侧均缺位（置信度：高）

- 流式侧 StreamingAnchorRule：growthPx <= 0f -> 0f（ScrollCompensation.kt:91）——收缩一律不配对；卡片侧无任何贴底收起分支（§3.3）。两侧对「收缩 × 贴底」这个格子都还没有产品级裁决：放任（现状）/跟随/补偿，从未被显式决策过——#434 是该格子第一次被用户可见地踩中。这与 #435「锚即意图」统一化的精神（贴底跟随族免派发是**意图级**裁决，spec 2026-09-25-435，转引自 issue442-research:34）相接：收起时贴底用户的「意图」是什么，需要一次与 #432 展开侧对仗的用户裁决。

### 4.5 同域交叉引用（今日 #438/#442 发现）

- **让位防御死接线（#442 §3.8 / #438 R-2）**：shouldYieldPairing 的记忆变量 lastSetFii/lastSetFiso 声明在 PreDrawFlushTask lambda **体内**（ScrollCompensation.kt:366-369，本报告实测确认），每次 onPreDraw 重置 null → 让位分支（:466-475）不可达。**与 #434 的关系**：流式侧而非 episode 侧——不改变 #434 机制；但若 #434 修复在收起窗口引入任何与流式 flush 相邻的新派发族，该失活防御意味着「显式意图>引擎配对」保护不在岗，方案落地时应一并复活（变量外提+接线测试，issue438-research:143,149 的修法可直接复用）。
- **null-key 裸 index set 通道（#438 R-1）**：episode 的反射恢复与流式配对共用 requestScrollToItemNoCancel 执行器（含键回写 :645-653）；若 #434 修复改用反射定位处理贴底收起（§5 方案 B 变体），落点不可见时的裸 index 通道同样适用，需携带 #438② 的「无 key 不 set」熔断语义。

### 4.6 已证伪/排除项（负结果留档）

1. **「双通道竞态致病」——排除**：episode×流式实测无双发（journal card-intervention:56-63）；#434 是单路径物理缺口。
2. **「#432 十五轮全绿=无 bug」——判据盲区**：锚点守恒判据看不见上方内容位移（§2.2-2）。
3. **「与 #430 瞬态 0 消费同因」——不同因**：#430=增长未落地（时序），欠账 rebase+落地门已修（issue430-research:57-69）；#434=滚动位钳制（几何），欠账重试每轮都 0 消费直至 2s 窗弃——修法不可复用。

---

## 5. 修复方案空间

### 方案 A「先终审方向，再最小落地」（保守，S~M）

1. **Step 1 定罪会话（S，真机）**：houji + card-lab 工具链（journal card-intervention:71-77 配方：100ms 按压注入 toggle、GrowthWatcher、mp4 帧差分类），一轮覆盖三构型：贴底收起（anchor 路径）/贴底收起（冷展开态，镜像路径，可用「展开→滚走→滚回→收起」构造 anchor=−1）/中位收起（对照组，应守恒）。判据：帧差位移量 + close-pre/close-post/paired-shift 三打点齐全。**产出=R-2/R-3/R-4 三问全部裁决**；若真机证实 +H 放大（R-4），则转入 A-2a 或升级 B。
2. **Step 2a（若 R-4 成立）产品级处理（S）**：贴底收起位移物理不可约（R-1 推论），对仗 #432 展开侧 skip-dispatch 的先例做**用户裁决**：(i) 接受单帧跳变（在 iron-laws 文档登记「贴底收起=物理不可约」条款，卡片以「设计行为」关闭）；(ii) 滑动化——塌缩仍单帧原子，但把 H px 位移改为一次 animateScrollToItem 平滑滚动（视觉=对话缓缓下沉而非跳变；不动配对数学、不新增补偿族——是呈现选择非「渲染后补偿」）。裁决走 V6（docs/verification.md 验收分类：真手指体感类）。
3. **Step 2b（若 R-4 被推翻，+H 真有补偿效果）**：按卡片原案落地 PairedDispatch 方向 fallback——nextCommand 增参（贴底构型+收起方向→返回反号指令），**TDD 先行**：CardExpandClockTest.kt:59-72 决策表新增一行钉死「consumed==0 ∧ 贴底 ⇒ 反号、其余构型逐字节不变」；双发防护=保持 :893-894 anchorKnown 门与 ⑥/④互斥不变 + 反号派发后 episodeShiftConsumedPx 记账语义（:929 清零前入账，防下轮展开超额）。
- **影响面**：CardExpandReveal.kt 单文件（热文件，遵 chatscreen-editing-protocol 循环：Read→编辑→compileDevDebugKotlin→commit）+ 测试。
- **风险**：低（A-2a 零代码；A-2b 改动有单测钉子）。
- **铁律兼容**：A-2a 零冲突；A-2b 在贴底开例外，需在铁律文档「贴底免派发」条款上加收起向例外注记（sse-scroll-stability-iron-laws.md 为权威载体）；滑移动画 (ii) 不触碰四条 SSE 铁律与 #435 配对规则（非流式增长、非补偿族）。

### 方案 B「收缩×贴底格子收编统一谓词」（中庸，M）

- **内容**：把「收起/收缩在贴底构型的视口语义」作为一格显式加入 StreamingAnchorRule 决策表（现 :91 收缩一律 0f），episode 收起路径与流式收缩路径（StreamingGrowLedger note 的 d<0 分支 :157）共同消费该裁决；episode 侧执行器评估从 dispatchRawDelta 循环迁移 requestPosition 原子消费（#437 验收五轮先例，issue430-research:253）——迁移后贴底收起可走「待定区写入+measure 原子消费」通道，与键保持（#438②）/防双发（单一待定区天然互斥）协同。
- **影响面**：CardExpandReveal.kt + ScrollCompensation.kt + StreamingAnchorRuleTest/CardExpandClockTest 双决策表；顺带收编 R-3（anchor 路径与镜像路径在规则层同格处理）与 R-5（两侧语义一次性裁决）。
- **风险**：中——episode 收起时序（driveTo(0f) 快照原子性 :911-913）与 requestPosition 消费遍的耦合需重新对拍；两套账本协议差异（issue430-research:164-173）是已知债。
- **铁律兼容**：正是铁律文档 #435 更新方向（「流式增长的视口配对必须走高度引擎统一规则」）向收缩向的延伸；需同步更新 iron-laws 变更日志。
- **TDD 切入**：先扩 StreamingAnchorRuleTest 决策表补「收缩×贴底/收缩×中位/收缩×读历史」三格（红）→ 实现规则 → episode/流式两侧接线测试各一（含 R-3 双路径同构断言）。

### 方案 C「配对执行体系统一重写」（激进：模块重构/完全重写，L）

- **内容**：沿 #423 方案 C/#430 方案 C 的合流方向：单一 GrowthLedger（per-source 基线+统一欠账/重试/让位矩阵）+ 单执行器（requestPosition 单出口事务）+ 单规则代数（StreamingAnchorRule 全构型决策表，含收缩向），episode/steady/streaming 三相位全部收编 PreRenderCoordinator 域；collapse=负增量走同一代数，贴底/中位/读历史×增长/收缩全格子由构造统一，#434 类「边角构型×执行器错配」从构造上消失；CardExpandReveal（1715 行）配对子体系析出、LazyListReflection 反射族随单执行器收口（连带消化 #438② R-3 反射脆弱性）。
- **影响面**：引擎域整体重构（~2000+ 行）、12 处消费卡、3573 例量级回归（issue430-research:262）。
- **风险**：最高——十轮收口的隐性契约（整数守恒 0.5f 量化、ε 预热窗、两阶段 drawFraction、收起镜像账本 :377-392）逐条迁验（issue430-research:263）；#444 未定罪前重写=流沙上盖楼。
- **铁律兼容**：目标态与铁律完全一致（单一视口权威、flush 单点）；过渡期保持 100ms cadence/帽协议/SafePrefixGate 不变。
- **TDD 切入**：金测试先行——同一增长/收缩序列在旧/新实现的 Σ位移与最终 topY 断言相等；决策表以 StreamingAnchorRuleTest 为种子扩到 episode 相；表征测试先钉守恒量化阈与 2s 重试窗再动结构。
- **前置条件**：#444 定罪 + 与 #442（flush 深拆/R2 分片）合流裁决——单独立项 spec，不在本卡范围闭合。

### 对比速查

| 维度 | A 终审+最小落地 | B 收缩格收编 | C 体系重写 |
|---|---|---|---|
| 根因覆盖 | R-2/3/4 裁决 + 症状处置 | +R-1/R-5 语义统一 | 全格子构造性消灭 |
| 影响面 | 单文件+测试 | 两文件+双决策表 | 引擎域重构 |
| 风险 | 低 | 中 | 高 |
| 工作量 | S~M | M | L（独立 spec） |
| 铁律兼容 | 零冲突/需例外注记 | 铁律延伸 | 目标态一致、过渡期风险 |
| 前置 | 无 | 方向终审 | #444 定罪+#442 合流 |

---

## 6. 建议

**推荐：方案 A 立即执行（Step 1 定罪会话为第一优先级）；据裁决结果走 A-2a（产品裁决+文档收口，最可能）或 A-2b（方向翻案时的最小修复）；B 作为语义正解在 A 之后立项；C 登记为与 #442/#423-C 合流的长期选项。**

理由：
1. **方向分歧未裁决前任何 dispatch 修复都是赌博**——R-4 的数学推导（journal:156 锚定）与卡片候选方向相反，而卡片的原始取证资产已丢失（§2.3），一轮带打点的真机会话同时裁决 R-2/R-3/R-4 三问，性价比最高，且完全符合「先取证后结论」的仓库纪律（issue430-research:294 的教训）。
2. 若 R-4/R-1 推论成立（最可能情形），症状为物理不可约，**正确的交付物是产品裁决+iron-laws 条款+可能的滑移动画**，而非硬造补偿——「宁可少补不可错补」是本域两次否决补偿族后沉淀的准绳（issue423-research:101-102,303）。
3. R-3（锚点路径同症）意味着卡片原案修不全；即使 A-2b 落地也必须覆盖④路径——这本身就是对「先裁决再动刀」的又一支持。
4. B 是 #435「所有高度相关处理统一到高度引擎」用户裁决（backlog.md:96-97）在收缩向的自然延伸，也顺带处置 R-5 两侧语义缺位与 #430 已登记的两执行器之债；但应等 A 的真机数据后再定格子内容。
5. 关联排期：#438②（同会话可顺带采集 yield 死接线的历史日志证据，issue438-research:123 的验证方法）与 #444（fling 构型同窗观察，issue430-research:242）可并入 Step 1 会话，一次真机出三组结论。

**预估工作量**：A = S（定罪会话）+ S~M（落地：2a 纯文档/动画 S；2b 决策表+双发防护 M）；B = M；C = L。

**验证要求**（docs/verification.md 框架）：单测（决策表新行/全量回归）→ 真机三构型（贴底×双路径+中位对照，帧差判据=位移量≤阈值）→ V6 用户手感验收（跳变/滑移的接受度属「真手指体感」类，必须用户拍板）。

---

## 7. 引用清单

**backlog**：#434 backlog.md:61-62（本卡）· #432 :102-103 · #435 :96-100 · #437 :93-94 · #438 :87-91 · #430 :119-123 · #429 :125-129（含 #428 已修注记 :128）· #442 :177-180（经 issue442-research 转引）· #444 :276-278（经 issue438-research 转引）

**journal**：docs/journal/2026-09-25-2026-09-25-432-collapse-drift.md:9-18（空节=明细缺位实证）· 2026-09-22-423-prerendercoordinator.md:156（配对数学·核心）· :612-622（#430 矩阵）· :636（冷展开守恒）· 2026-09-27-card-intervention-growth-phase.md:5（环境丢失）· :14-18（注入配方）· :28-63（三场景+无双发）· :71-77（工具链）· :79-117（#449 勘误）

**research**：2026-09-28-issue423-research.md:199-205（R-A 定位本卡 P0）· :255-273（方案 A 含 #434 修法转述）· :327 · 2026-09-28-issue430-research.md:57-69（#430 三层根因）· :164-173（两体系对照）· 2026-09-28-issue438-research.md:121-127（R-1 null-key/R-2 死接线）· :133（贴底不进配对 set）· 2026-09-28-issue442-research.md:153-162（§3.8 死接线）· sse-scroll-stability-iron-laws.md:22-60（五铁律）· 433-height-engine-map.md（§4 已过时，读法见 issue423-research:185-191）

**源码**（当前 HEAD 实测）：CardExpandReveal.kt:45-81（KDoc，:59-60 贴底残量存档）· :148-159（applyPreRenderShift）· :161-184（PairedDispatch，:181 零消费终止）· :190-213（全额执行器+:202-208 paired-shift 打点）· :247/:383/:391-392（Clock/账本/锚点字段）· :725-750（episode 起）· :783-785（锚点记录）· :786-803（#432 skip-dispatch）· :848-944（收起路径：:875 rep · :893-894 anchorKnown · :896-901 反射恢复 · :911-913 原子塌缩 · :914-928 镜像 dispatch · :929-935 清账）· :1067-1135（steady flush，:1076-1089 贴底豁免，:1094 落地门）· :1143-1169（取消路径锚点消费）· :1553（bottomPinnedExpandSkip）· ScrollCompensation.kt:80-99（统一谓词，:91 收缩免配，:92 贴底原点）· :108-117（shouldYieldPairing）· :362-369（flush 任务+lambda 体内变量⚠）· :466-475（让位接线不可达）· :517（requestPosition 单出口）· :645-653（键回写）· ChatMessageList.kt:665-683（flush 宿主+sgr 注册）· ViewportDispatchGateway.kt:14-27（豁免表：episode 族 :19）

**测试**：CardExpandClockTest.kt:25-29（冷启展开全高）· :50-72（PairedDispatch 决策，:68-69 零消费终止钉子）· :90-102（收起守恒）· :482-510（欠账 rebase）· BottomPinnedExpandSkipTest.kt:16-27

**commits**：1de9f308（#434 立卡/DSH 定罪）· 108a9001（收起镜像化）· c5ddfbbf（残差重试）· 7785cfbf（反射定位）· 2da3b6d0/fca689d7（#432 prewarm/贴底展开）· c0754035（#430 根修）· af428686（#435）· cb733d80（让位防御，⚠接线缺陷同源）· 817607b4（键回写）· d28360d8（R1-A2 单出口）· 010363f2（#432 思考卡默认收起）

**关联卡片注记**：#434↔#432（本卡=其主诉的根因定罪；#432 矩阵判据盲区是漏检原因）；↔#435/#442（贴底/收缩语义应收编统一谓词=R-5，B 方案载体）；↔#438②（执行器同族：键保持/null-key 熔断语义若走反射修复必须携带；yield 死接线在同会话顺带取证）；↔#444（fling 跳变复发同窗定罪候选）；↔#430（展开侧姊妹卡，0 消费终止分支共用但成因不同，修法不可互抄）；↔#423（其方案 A 第 1 项即本卡修复，方向分歧见 R-4）；↔#433（若滚动死锁复现，引擎域同族）。

