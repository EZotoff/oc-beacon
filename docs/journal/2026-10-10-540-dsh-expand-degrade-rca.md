# 540-dsh-expand-degrade-rca（2026-10-10）

> 状态：进行中
> 关联：（spec 路径，若有）·（issue 编号，若有）
> 来源：用户反馈 / grilling / E2E / 顺带发现

<!-- 过程中的取证/验证证据直接写本文件；backlog.md 只留 ≤3 行卡片。 -->

## #540 RCA：DSH 流式/输出后展开「上推→跳回」——双路径架构定罪（2026-10-10，纯分析未动手）


## 0. 用户主诉（原话转写）

DSH 类型服务器会话中，SSE 输出的时候（输出后）**有时候**展开折叠卡出现：
1. 展开过程中将内容往上推，展开完毕之后卡片的高度跳到原来的位置；
2. 重新进入会话后恢复正常的展开逻辑：渲染前计算高度然后通过反射设置进去。

## 1. 两条展开路径与门控（单一分叉点）

分叉点唯一：`CardExpandReveal.kt:566` — `if (listState == null || LocalInStreamingTurn.current) → 降级路径`。

- `LocalInStreamingTurn` 逐 item 提供：`ChatMessageList.kt:2933`，值 = `entryStreaming = entry is ChatEntry.Turn && entry.isStreaming`（`ChatMessageList.kt:2903`）。
- `entry.isStreaming` 在条目构建期编码（`MarkdownChunking.kt:186,366-368`）：`(turnGroups[displayIdx]).any { it.message.id == streamingMsgId || (!it.isUser && it.message.time.completed == null) }`。**DSH 路径 streamingMsgId 恒 null**（`MarkdownChunking.kt:363-364`，#437），DSH 完全依赖 `completed == null` 判据。

### 1a. 机件路径（idle item，即「正常」路径 = 主诉②）

`CardExpandReveal.kt:835-969`（批次九用户裁决「渲染前计算+反射设置」）：
1. `clock.warmup()`（ε=0.001 零视觉入树首测）→ `settleUntilContentStable`（≤600ms，#422）；
2. `awaitAnchorQuiescence`（#529 静止门：滚动静止 + 整列基指纹 2 帧稳定，≤200ms）；
3. `H = clock.lastMeasuredH`（落地实测高，非缓存）；
4. `normalizeExpandAnchor`（#508 折叠算术：向旧侧折算 + 宿主容量 +H）→ `LazyListReflection.requestScrollToItemNoCancel(fii, fiso+H)` 写**待定滚动位**；
5. 同一 `Snapshot.withMutableSnapshot { clock.driveTo(1f) }` 施加高度增长——**与待定滚动位同一遍 measure 原子生效，中间帧从构造上不存在**；
6. 幕布纯 draw 揭示 200ms（零布局零滚动）。

「反射」实体：`ScrollCompensation.kt:799-857` — 反射 `LazyListState.scrollPosition.requestPositionAndForgetLastKnownKey(index, offset)` + 戳 `measurementScopeInvalidator`，下一遍 measure 消费；不取消进行中 fling。

### 1b. 降级路径（流式 item = 主诉①的发生地）

`CardExpandReveal.kt:566-627`：
1. 裸 `AnimatedVisibility`（spring ~300ms）——**零高度预计算、零配对**；
2. reverseLayout 下 LazyList 锚定 firstVisibleItem（贴底=fii 0/fiso 0），item 增长 +H **全额转译为上方内容上推 H**（#420 头注释原始主诉，#526 注释同症：「卡头连同上方整体上推 H」）；
3. #526 根修（a7f8d413）：toggle 时记 (fii,fiso)+盒高，`withFrameNanos` 等 AV 尺寸稳定（3 帧稳定 / ≤40 帧上限 ≈650ms，`:599-607`）后 `requestScrollToItemNoCancel(fii, fiso+Δ)` **一次性事后反射回锚**（`:610`）——终态正确，但瞬态（上推 300-650ms → 跳回）**结构性地存在**。

**主诉①逐句对位**：「展开过程中将内容往上推」= 降级 AV 零配对上推段；「展开完毕之后卡片的高度跳到原来的位置」= AV 稳定后的一次性反射回锚（跳回）。**主诉②对位**：重进会话后无流式 item → 机件路径原子落位。

## 2. 为什么降级路径存在（互斥约束，非偷懒）

流式 item 的增长配对由**引擎域**独占：HeightReserve 帽协议（#437 引擎①，`ScrollCompensation.kt:307-359`：一帧缓冲 + pre-draw flush 单事务 {帽→真高+滚动+Δ} 同 pass 释放）+ StreamingGrowLedger（#435 锚即意图）。CardExpand 机件若同时在流式 item 上跑自己的配对/反射落位 = **双重注入**（#420 竞态矩阵第一条，`CardExpandReveal.kt:63-65`）。降级是互斥裁决的产物。

## 3. DSH 独有的「输出后」延长窗（主诉①的时窗②，根因②）

- **工具卡宿主 completed 恒 null**：`DshEventMapper.kt:1400-1437` `mapToolCall` 建 `Message.Assistant(time = TimeInfo(created = time))`——**无 completed**；整装/回放腿亦不携带（`MessageEventHandler.kt:285-286` 明证）。唯一回填点 = `markSessionIdle`（`MessageEventHandler.kt:1625-1654`），触发链 = **turn/end → SessionIdle**（`DshEventMapper.kt:57` 时间契约）。
- 因此 DSH 多步 turn 中：文本输出停止 ≠ turn 结束。工具卡宿主（以及步间空窗的流式宿主 `dsh-t{turn}s{step}`，`DshEventMapper.kt:78`）completed 全程 null → 整个 turn 内所有卡（含已完结的工具卡）`entry.isStreaming=true` → **降级路径伴随整个 turn 生命周期**。「输出后（步间/turn 末未 idle）」展开工具卡 = 同一上推→跳回。
- **重进会话**：markSessionIdle 回填已落盘（`persistSseUpdate`，`MessageEventHandler.kt:1650-1654`）→ Room 读到 completed → 全部条目非流式 → 机件路径 = 主诉②「恢复正常」。V1/V2 SSE 同构降级路径存在，但 V1/V2 的 completed 回填同样走 markSessionIdle（V2 SSE 从不携带 completed，`MessageEventHandler.kt:822`）——DSH 差异在**多步 turn 更长 + 工具卡独立宿主更多**，暴露面显著更大。

## 4. 次级污染面（机件路径下「输出后」残余候选，待取证定谳）

1. **HeightReserve 帽宽限**（#440）：流结束后帽在物主 item 上保持（`ChatMessageList.kt:1904-1908`，`reserveOwner` 分支 `:1931-1936`），item 上报高度被钳 `min(child, reserved)`（`ScrollCompensation.kt:351`）。刚完结 turn 内展开：driveTo(1f) 增长可能被帽吞一帧，与机件「同遍原子」假设冲突 → 待定位错帧。
2. **#533 落地期桥接**（`CardExpandFinalHStore.kt:53-79`，measured<floor 持有 1.5s 稳定释放）：只有 ReasoningBlock 传 cacheKey（`ReasoningBlock.kt:296`）；**工具卡 storeKey=null 不受进程级 floor 保护**（`CardExpandReveal.kt:1945` 注释「工具卡等无键卡维持现状」）——切片槽位（StepGroupWindowedBody 出窗即弃）重建的工具卡欠测无兜底。

## 5. 判读探针（复跑取证用，全部已在库）

- `[DEBUG-526] degrade toggle`（`CardExpandReveal.kt:574`）/ `[DEBUG-526] top-pin restore`（`:612`）——降级路径 toggle/回锚两钉；
- `[DEBUG-466] expand-anchor`（`:916`）——机件路径反射落位参数；
- `RESERVE measure h/reserved`（`ScrollCompensation.kt:349`）+ `VDRAW h/fiso`（`ChatMessageList.kt:1921`）——帽钳制与实际上屏高；
- `TurnFin`（`ChatMessageList.kt:2910`）——entryStreaming 翻转帧；`LRef set`（`ScrollCompensation.kt:839`）——反射通道唯一出口留痕。

复现配方：DSH 演示容器（dsh-pw-demo@8181）或 live 3080 造多步 turn，流式中/步间/输出后三个时窗各展开工具卡与思考卡，logcat 按上述 tag 过滤对帧。

## 6. 根因陈述（一句话版）

**展开的「渲染前算高+反射原子落位」机件与流式增长配对域（帽协议/#435）互斥，流式 item 被整体降级为零配对裸 AV + 事后回锚（#526），其 300-650ms 上推瞬态与终局一次性跳回即主诉①；DSH 工具卡宿主 completed 恒 null 至 turn/end 才回填，把降级窗从「输出中」延长到「输出后」，而重进会话读 Room 已回填值回到机件路径即主诉②的「正常」。**

## 7. 修复方向备忘（消除源优先，未动手待用户裁决）

- **A 消除源**：让展开增长改走帽协议通道（flush 单事务 {帽→真高+滚动+Δ} 本就是「同帧原子落位」的现成机构）——展开不再自带配对，与流式域天然单通道，双注入从构造上消失；降级路径整个退役。
- **B 收窄窗**（独立小改）：DSH 工具宿主 completed 在 step/整装边界回填（不等 session idle）——「输出后」工具卡窗直接关闭；需核对红点水位线 UnreadStateStore 对 completed 时刻的依赖（§2.3 契约）避免提前回填污染未读判定。
- **C 降级路径内预置**：toggle 时先用 finalH store 预反射（工具卡需补 cacheKey），AV 增长与滚动位同帧——治标性质，最后备选。

## #540 五腿校验裁定（V1-V4+P，2026-10-10）——主体成立 + 3 勘误 + 探针帧级实证 + 修复方向修正


## 8. 五腿校验矩阵（V1 代码事实 / V2 数据域契约 / V3 修复可行性 / V4 回归史 / P 探针实证）

| 腿 | 判定 |
|---|---|
| V1 | C1/C3-C9 成立；C2 两处修正（编码点在 MC:419/490；「DSH streamingMsgId 恒 null」为 #437 时代 stale 注释，HEAD CML:469-473 下 DSH 应非 null——探针 SGR 行 `sid=dsh-t2s1` 实证） |
| V2 | A1-A5 全成立（7 条工具宿主路径恒无 completed；markSessionIdle 唯一回填；落盘回读闭环）；B 影响面 B1-B14 清单；勘误：RCA 引「V2 SSE 从不携带 completed」为 stale 注释（V2SseMapper.kt:212 自 31f6c0e3d 携带，两处代码注释也应更新） |
| V3 | 四方向评审+第五方向 E（活动门）；**两处事实错误被代码+探针推翻**：①「DSH 整装文本 completed 仍 null」——错，DshEventMapper.kt:1327 明写 `TimeInfo(created=time, completed=time)`；②「DSH streamingMsgId 恒 null→recentStreamedTurnKeys 失效」——错（同 V1 勘误，探针 sid=dsh-t2s1）。两者均使 B 的评估向有利方向修正（见 §10） |
| V4 | 「结构性残留非回归」独立成立：降级=#420 设计（29ea9a383），结构=#526（a7f8d413），其后五处代码面+AV 弹簧+稳定常量零改动；保留意见①#526 流式降级路径当年未获视觉验证（journal §3 ⚠️ 行，仅以「已知边界+观感不佳再议窗口缩短」保留条款披露）——**本次主诉即该条款触发**；②#288（7cfab01a8）新增 tool-workflow 卡边际扩宽暴露面 |
| P | E1-E6 见 §9（原始日志 /tmp/540-w{1,2,3}.log；模拟器 emulator-5554 + dsh-pw-demo@8181，pid 9146，session-bbca5110） |

## 9. 探针实证（E1-E6 判定 + 帧级铁证）

- **E1 ✓**（w1+w2）：`[DEBUG-526] degrade toggle visible=true reason=in-streaming-turn`，与 RCA 预测 reason 逐字一致。
- **E2 ✓**：toggle→restore 间隔 w1=544ms（15:23:45.751→46.295）、w2=487ms（15:28:15.165→15.652），均在预测 300-650ms 窗内。
- **E3 ✓（帧级铁证）**：w1 toggle 后窗口内逐档 `RESIZE h 143→148(d=5)→160(d=12)…` + `RESERVE measure/flush` + **`SGR-435 drop(append/reading-away)` / `release capd=5 led=0 (nodis target-fii=7) h->148`**——AV 弹簧增长逐档经帽通道释放且**免派发（nodis）**＝上推机制本体；终局 `LRef set idx=0 off=122` 一次性反射＝跳回。w2 同构（532→537→549→563…）。**新增发现：降级路径的展开增长本就骑在帽通道上，缺的只是「expand 源类型」的配对决策**——直接收窄方向 A 的缺口。
- **E4 部分✓**：w2 在 turn-2 流式中 degrade（15:28:15）+ TurnFin（15:28:16）；纯文本 turn 的输出后窗 ~1s（末整装自带 completed 即翻转）。**工具宿主长窗未获仪器捕获**（演示容器只出短文本 turn）——该窗由 V2 代码级全证（7 路径恒 null+唯一回填点），留真机工具 turn 复验。
- **E5 ✓（换位取证）**：w1 15:25:58（TurnFin 后 100s，同会话同卡 H=122）完整机件链：`settle done H=122`→`anchor-quiesce stable=2 ms=61`→`LRef set idx=7 off=122`→`[DEBUG-466] expand-anchor fii=0 fiso=0 +H=122 host=7 norm->7:122 pinned=true`→episode 397ms completed=true。w3（重进）有 `[PRD-warm]` 预热活动+载入即 TurnFin（机件域活跃），但未捕获 toggle——由 w1 换位样本覆盖。
- **E6 ✓**：TurnFin 实测（见 E4）；文本 turn 窗短、工具 turn 窗待真机。

同卡 A/B 对照（w1）：同卡 H=122 两次展开——流式中走降级（526 两钉+544ms 窗）、turn 结束后走机件（466+quiesce 61ms+397ms episode）——同一实体双路径行为差异的直接实证。

## 10. 修复方向修正后综合（V3 报告 + §9 纠偏）

- **A（终修，消除源）**：探针证明增长已在帽通道（§E3），缺口=StreamingAnchorRule 扩「expand 源类型」恒配对行（贴底原点格必须覆盖，否则主诉不修）+收起向 planned-shrink+帽×episode 单写者约定（收编 §4.1 宽限帽双注入面）。工作量由「中-大」下修为「中」。三个语义裁决点待用户拍板（贴底原点/读历史/收起镜像）。
- **B（先行，收窄「输出后」窗）**：V3「最小形态零收益、必须连带整装回填」**被推翻**（整装本就带 completed，MC:367-368 组判据在工具宿主回填后即翻非流式）；分片振荡地雷亦基本拆除（recentStreamedTurnKeys 对 DSH 在役，sid 非 null）。剩余代价=V2 的 B6/B7 统计栏步间翻转（配套：统计栏流式判据并 FSM activity）+回放腿数据形状变化记录。取值铁律：终态事件服务器信封时刻（禁本地钟）。工作量「小-中」。
- **C（止血备选）**：仅缓存命中起效（首开原样）；其中 **ToolCardScaffold 补 cacheKey 子项独立成立**（修 §4.2 工具卡无 #533 floor 保护），无论方向如何值得单独做。
- **D**：单独不可行（物主 item 构造性 2H 双注入反例成立）＝A 完成后的路径统一清理，不独立立项。
- **E（V3 新提，轴 1 纯 UI 活动门）**：零数据面风险，但轴 2（帽配对）仍不可省——与 B 二选一，B 兼修步时长数据正确性。

**推荐组合**：B 最小形态（工具宿主 step 边界回填+统计栏判据配套）先行 → A 终修（含单写者协议）→ cacheKey 子项随时可插。处置超越 #526 保留条款的「窗口缩短」止血选项。

## 11. 待用户裁决

1. 轴 1 选型：B（数据正字，兼修步时长/红点喂入）vs E（纯 UI 活动门，零数据风险）；
2. A 的三格语义：贴底原点展开（维持机件「+H+立即解跟随」）、读历史展开（维持全域 (fii,fiso+H) vs 接受锚即意图免派发）、收起镜像；
3. B 若先行：统计栏流式判据是否并 FSM activity（否则步间计时器翻转抖动）。

## #540 方向 A+B+cacheKey 实施与验证（2026-10-10）


## 12. 方向 A 实施内容（消除位移源）

- `reserveReleasePlan` 增 `expandPaired` 通道（ScrollCompensation.kt）：展开窗内增长/收缩恒配对（覆盖贴底原点/读历史格）；收缩在窗外维持「帽不回改」存量语义。
- `HeightReserveState.expandPairingWindows` 计数窗（换主 resetIfOwnerChanged 清零；计数制支持同 item 多卡并发）。
- flush 单出口 `applyPairedShift` 增负向分支（`release-dis` 日志签名）：收起配对走 dispatchRawDelta 镜像同帧消费——反射负向目标需向 index 减小折叠而 reverseLayout 下方项不在可见窗（尺寸谱缺失）；dispatch 后回写 lastSet 防 yield 误判；无拒绘（同帧生效）。
- `LocalCardExpandReserve`（CML 逐 item 供给）两处消费：降级分支配对窗 + 机件原子帧单写者。
- 降级分支（CardExpandReveal.kt:566+）：#526 事后回锚**删除**，替换为「toggle 开窗 → AV 尺寸稳定关窗」；E2E 边界定罪后补 `sawChange` 守卫（静默期空卡 AV 内容迟到 +63ms 时 3 帧稳定会在内容落地前触发=窗 211ms 早闭——盒高须相对 toggle 时刻变化过才计稳，零净变化由 40 帧上限收窗）。
- 机件单写者（§4.1 收编）：展开/收起原子帧的 `withMutableSnapshot{driveTo}` 内同快照 `hatPairedRaise/hatPairedLower`（纯函数，非物主/未初始化/零增量不动；落帽下界归 -1 哨兵）。

## 13. 方向 B 实施内容（数据域根因②）

- `mapToolResult`：tool/result 边界给 `dsh-call-*` 宿主回填 `completed = 事件信封时刻`（错误终态同回填；merge `created=min` 保 #453 计时锚；#338 消毒第一守卫短路、markSessionIdle 幂等只填 null）。
- `mapCodeDispatch`：subagent 终态帧的宿主重申携带 completed（本分支即终态）。
- 统计栏视觉族配套：`LocalTurnActive`（ChatViewModel.turnActiveState，3s 防抖）经 CML 供给；`MessageCardAssistant` 拆 `visualStreaming = isStreaming || turnActive` 只喂 `AssistantTurnTail`（计时器/流式徽标/反馈钮门）——**parse 族（asyncParse）仍吃 isStreaming**（完结即终态解析= #428/#509 既定毕业语义，不吃防抖）。
- cacheKey：`ToolCardScaffold` 增 `cacheKey` 参数 → `CardExpandReveal`；通用渲染器 `ToolCardRenderer` 传 `tool.id`（工具卡入 #533 进程级终高 floor；专用小卡 Patch/Edit/Task 等输出小、非欠测重灾区，维持 null 后续按需）。

## 14. 验证矩阵

- 单测（红→绿）：ReserveReleasePlanTest +6（窗内恒配对×3/收缩规划/手势持帽/窗外语义不变）+ FlushTaskPhasesTest +2（贴底原点走反射不 nodis / 负向走 dispatch 镜像不反射不拒绘）+ ExpandHatWriterTest 新文件 7（单写者纯函数）+ DshEventMapperTest 更新 3+守卫 1（起始腿 assertNull completed）+ DshHistoryFolderTest 金样本 +1 事件（19）。
- 全量 `testDevDebugUnitTest --rerun` 三轮绿（含 sawChange 守卫后终轮 1m40s）；`lintDevDebug` 0 新错误（3m46s）。
- **E2E 红→绿（emulator-5554 + dsh-pw-demo@8181，与修复前同会话同卡天然 A/B）**：
  - G1 ✓ 流式中展开：degrade toggle + pairing-window open/close；窗内 8 条 release **全 set(fii=..) 配对、0 nodis、0 drop、0 top-pin**（修复前：≥10 条 nodis 连环 + VDRAW h 振荡 fiso 恒 0 + top-pin restore 跳回）。
  - G2 ✓ 流式中收起：13 条 `release-dis` 逐帧 `capd==consumed` 精确（-124/-1015/-880…），h/fiso 锁步收缩。
  - G3 ✓ 几何：VDRAW h 递增与 fiso 递增逐条咬合（修复前 h 振荡 148→160→148、fiso 恒 0）。
  - G4 ✓ 输出后/重进：机件路径不变（expand-anchor + episode done completed=true，438/521ms；另有 3 次意外 turn 后 tap 全走机件=附加样本）。
  - G5 ✓ 巡检：23MB logcat 零 FATAL/零 CardExpand 异常/VTRACE 无抖动。
  - G6 跳过（模拟器仅配置 DSH 8181，无 V2 profile；三端共享管线+单测覆盖）。
- 边界观察（已修）：静默期空卡窗早闭（§12 sawChange 守卫）。
- B 的 E2E 腿：本轮 A 验证构建早于 B 落地；B 行为面（TurnFin 移至 step 边界/统计栏不抖）待下一构建真机/模拟器复验——单测与 V2 全影响面审计（§8 V2 腿 B1-B14）已覆盖语义面。

## 15. 残余与后续

- 降级路径窗关后的卡内迟到增长（分批表格/asyncParse）无 steady 配对接管（机件有 #430）——大表在「输出中」展开的窗后增量仍走流式语义；B 落地后「输出后」窗大卡直接走机件（settle+loading）大幅缩面。观察卡素材。
- 专用工具卡（Patch/Edit/Task/Skill/WebFetch）cacheKey 未接（输出小非重灾区）。
- V2 交叉 E2E 未跑（无 profile）。

## #540 cacheKey 全量接入（2026-10-10 用户裁决：所有卡统一入店 + 键律保竞态稳定）


## 16. cacheKey 全量接入（用户裁决）

用户裁决：接 cache 即所有卡片接入，且一致性/竞态稳定性各方面保持一致。落地为**键律 + 全覆盖**：

**键律（LocalCardExpandCacheKey KDoc 承载，竞态稳定性承重）**：
1. 键 = 稳定内容身份（part.id 跨毕业稳定 #509 / 消息级 eventKey / 对象 id）；
2. 禁类型级常量键——单调 store 下旧实例高度会过冲给新实例（1.5s 有界但违规）；
3. floor 语义集中在 CardExpandGeometryNode（1.5s 稳定释放/宽度键控/LRU 512）——接入只供键，机制全卡一致。

**覆盖矩阵**：
- part 域（机制化）：`LocalCardExpandCacheKey` 由 PartContent 逐 part 供给 part.id；ToolCardScaffold 兜底读 local（15 个专用工具卡零签名改动一次覆盖）；GlobToolCard/TodoListCard/QuestionPartContent 直调 reveal 处读 local；ReasoningBlock 维持显式 pinKey（=part.id，同值）。
- 消息域（显式键）：InjectionCard/EventCard=eventKey（展开态记忆键复用）；CompactionCard="compact-{timeMs}"（0=尾部兜底无时间源→不入店）；MessageBubble 增 expandCacheKey 参数透传（仅 EventCard/CompactionCard 两消费方）；MessageCardAssistant 两处提问块=锚 part.id / pendingQuestion.id。
- 横幅域（实例键+显式排除）：question/permission/prefixQ 三横幅传实例 id；五个状态横幅（revert/retry/maxTokens/工具条/步进）内容随状态变化、无稳定内容身份——按键律**显式不入店**，BannerReveal KDoc 声明排除理由。

**验证**：compileDevDebugKotlin 绿；全量 testDevDebugUnitTest --rerun 绿（1m33s）；lintDevDebug 0 新错误（3m50s）。floor 机器（store/bridge/geometry node）既有单测在役；新增面为纯供键接线（compose 层），E2E 判读沿用 #533/#540 既有探针（RESIZE/GapDiag/finalH 脉冲签名）。

## #540 事后回锚全量清剿（2026-10-10 用户裁决：所有服务器类型/所有卡/其他地方）


## 17. 事后回锚全量清剿（穷举扫描 + 切除）

**扫描**（穷举腿，90 处滚动写入全部核实）：全 chat UI 域按五类分拣——A 渲染前原子（4，KEEP）/ B pre-draw 同帧（4，KEEP）/ C 事后回锚（3）/ D 用户导航（8，KEEP）/ E 跟随守卫（5，KEEP——经 episode 租约+departure+流式静默三重门控证实不修展开位移）/ F 手势（2，KEEP）。三端（V1/V2/DSH）共享渲染管线，代码级清扫即全覆盖。

**切除（本轮 commit）**：
1. **C1 end-restore**（#424）：episode 终态上屏 2 帧后 dispatchRawDelta 对账纠偏——仓内唯一存活的「上屏后修正」活体（仅反射探针降级时可达）；删除=降级即弃配（dispatch fallback 本身全额重试）。
2. **死补偿设备**（零运行时路径，占 CardExpandReveal ~430 行）：#423 钉位修正器全套（pinPending/pinAnchor/guardCur/coordStamp/pinLedgerBase/pinDispatchedTotal/pumpArm + FLUSH 修正环 + pin-placed 即时修正；pinPending 永假=批次九退役残留）、#426 drainPhaseA+phaseADrain（永假）、applyPreRenderShift（零调用）、dispatchClosedLoop（#425，零调用）、easedFraction+GEOMETRY_TWEEN_MS（零调用）、smoothScrollToBottom（KT1a 台账死代码）；episodeEndCorrection 及其单测随 C1 删除；HOLD_VENT_FRAMES/PIN_QUIET_MS 常量随之。**保留** closedLoopCommand（纯数学、成套单测在役、文档价值）。
3. 取消路径锚点恢复（cancel-on-scroll）定谳为**渲染前原子**（反射恢复与 snap 塌缩同一遍 measure，无中间帧）——保留；其两点观察（陈旧绝对锚 vs 镜像目标的语义不一致、20k 级全额 teleport）留作后续改进素材。

**保留理由清单**（非事后回锚）：A1/A2 反射待定位+driveTo 同遍 measure；A3/A4 dispatch fallback（measure 前）；B1 引擎 flush（pre-draw 单事务+拒绘）；B2 稳态账本（pre-draw）；E1-E5 跟随守卫（三重门控）；D1-D8 用户导航（含 JNC 900ms 稳定窗——蒙版遮蔽下导航收尾，标注事后性质）。

**另案登记**（同形不同域，未盲删）：#541（P2）snapToBottom 3×120ms 强推钉底——位移源=滚底后新入视口 item 延迟测量长高（#256 取证），消除源方向=引擎接管延迟测量域，盲删回归「FAB 拉不到最低」；#542（P3）ForceScroll verify-retry——显式意图域，随 #541 一裁。

**验证**：残留 grep 零命中（11 个符号全仓）；compile 绿；全量 --rerun 绿（1m38s）；lintDevDebug 0 新错误（3m54s）。

## 18. C2/C3 另案收口：#541 锤退役 + #542 verify-retry 退役（取证驱动）

**取证（bbf90b44a 埋点 → 模拟器 E2E，v1:4199 Lighthouse 会话）**：
- S1 FAB×5：pin→LEAP 原子落位（+2~3ms，27123px 级）；#256 位移源「滚底后延迟测量爆发」真实复现（3 卡 ×+197/+197/+255px，pin 后 35-99ms，正落锤窗内）但**零 fii/fiso 位移**——锤 15/15 击 consumed=0，pre/post 恒 (0,0)。
- S2 发送×3：ForceScroll primary pin 3/3 一帧即贴底，verify 窗 0/3 开启、重滚 0/3。
- 几何定谳：reverseLayout 贴底锚 (0,0) 下 item 增长向上扩展不移动锚点（#432 免派发同源）——#256 时代推动者已被 #432/#435/#437/#476 滚动域根修消灭，两兜底零承重。附加发现：verify-retry 无视 autoScroll，verify 窗（1s）内用户上滑翻历史会被强拉回底=阅读位置铁律违例面。

**修复（513fae567）**：
- snapToBottom 删 3×120ms scrollBy(-10000) 强推锤，保留单次 explicitPin 原子锚定（非 suspend 化）；真实离底（横幅插入 index 位移等）由 MSGEFFECT/GUARD 跟随族按用户语义重锚（尊重滚动让位）。
- ForceScrollExecutor 删「一帧校验+1s 未回底重滚」块与 waitOneFrame 参数；atBottom()/AT_BOTTOM_OFFSET_MAX/VERIFY_TIMEOUT_MS 死件随删。
- 网关 R5 豁免表删 snapToBottom 行（强推删除后该函数全经网关，无豁免项）。
- 测试：④「补偿收敛不重滚」删除（前提消失）；⑤「永不收敛重滚一次」重设计为「永不收敛也不重滚」（#542 不变量：单次锚定即终态，防再引入事后修正）。

**验证矩阵**：全量单测 --rerun 绿（1m42s）；lintDevDebug 0 新错误（9m11s，baseline 失效条目按 #534 裁决不顺带）；修复后 E2E 5/5 全绿——S1×3：pin→LEAP +3ms、atBot=false 零翻转（#256 症状零复现）、FAB 消失（dump 判定，右下余钮为常驻 Quick navigate）；S2×2（20s 窗）：tick→grew→pin 链完整、verify-retry 绝迹；**全流加证**：两段完整流式期（209s/216s，回复增至 ~5900px）atBot=false=0、SGR-GATE 全程仅 primary 一击（整段流式零程序化视口写入）——单次原子锚定与「贴底物理跟随」自洽的基线签名。原始日志 /tmp/541-{s1,s2,post-*}.log 与截图在档。

**复发预案**（勿恢复锤/重滚）：「FAB 拉不到最低」复发 → 先查 index 位移推动者（横幅/问题卡插删引起 fii 抬升），归 MSGEFFECT/GUARD 跟随族域处理。

## 已完结卡片迁入（2026-10-10）

### **#541 snapToBottom 3×120ms 强推钉底——位移源（滚底后新入视口 item 延迟测量长高）未消除，靠事后周期性强拉补偿（#540 清剿扫描 C2）** `scroll`
  - FAB 点击/错误 snackbar 后 snapToBottom（ChatScrollUtils.kt）到底部后 3×120ms 周期性 scrollBy(-10000) 强推钉底——#256 实证位移源=滚底后新入视口 item 的延迟测量长高（+55/+194/+466，30-70ms RESIZE 爆发窗）
  - 属贴底钉底域非展开补偿；消除源方向=延迟测量增长经引擎域接管（帽只覆盖流式 item，此域是滚底后旧 item 重组）——需先取证引擎覆盖缺口再动；盲删会回归「FAB 拉不到最低」用户可见缺陷（#256 验收史）
  - 取证退役（bbf90b44a→513fae567，journal §18）：模拟器 S1×5 锤 15/15 击零消费——#256 延迟测量爆发真实复现但零位移（reverseLayout 贴底锚增长向上扩展不动锚，#432/#435/#437/#476 已灭推动者）；删 3×120ms 强推锤保留单次 explicitPin；修复后 S1×3 全绿（pin→LEAP +3ms、atBot=false 零翻转、FAB 消失）+两段全流期加证零翻转；verify 态待验收
  - 迁入依据：2026-10-10 用户确认修复完毕（本批 systematic E2E+review 随后进行）；证据 journal §18（backlog.sh migrate 2026-10-10）

### **#542 ForceScroll verify-retry 发送后补滚——显式意图域内的下一帧校验修正（#540 清剿扫描 C3）** `scroll`
  - ChatScrollController.kt:410-417：强滚锚底后 waitOneFrame + ≤1s snapshotFlow 等待未到位重滚——位移源=发送后新消息异步布局长高，域=用户显式意图（发送后跟随），量级=锚底差值
  - 与 #526 同形不同域；处置随 C2 卡（snapToBottom）一裁：若延迟测量源被引擎接管则本卡随之消解
  - 随 #541 一裁收口（513fae567，journal §18）：S2×3 verify 窗 0/3 开启、重滚 0/3 零承重，且 retry 无视 autoScroll 有强拉翻历史用户违例面；删一帧校验+1s 重滚块（waitOneFrame/atBottom/AT_BOTTOM_OFFSET_MAX 死件随删），单测重设计为「永不收敛也不重滚」不变量；修复后 S2×2+全流 209s/216s 零 atBot=false；verify 态待验收
  - 迁入依据：随 #541 一裁同批完结；证据 journal §18（backlog.sh migrate 2026-10-10）

## 19. 系统性真机 E2E + 双轴代码 review（用户指令批）

**代码 review（681db82d6...HEAD，5 代码 commit，Standards/Spec 双轴并行）**：
- Standards：零硬违规。SSE 四铁律逐条对照通过（配对窗仅流式降级分支、帽写手 owner==host、引擎单出口、autoScroll 双键未动）。判断题 smell 存档：DshEventMapper 宿主 upsert ~6 处同形可提 helper、开/关窗 DEBUG 日志复制、调用点与 hatPairedRaise/Lower 双重守卫、cacheKey Any? 贯穿（仓库惯例压制）、hatReserve/pairingReserve 同物两名、注释「回合级」术语漂移（CONTEXT.md 轮次词条）。
- Spec：A 三件齐且互斥正确；B 两腿落（含 error 腿）；13 处 cacheKey 合规（五状态横幅显式排除核实）；#17 删除符号零活引用；#541/#542 与 §18 一致。**UnreadStateStore 水位线核验闭环**：水位线取服务器 completed 时刻、B 回填值单调序保持（工具宿主时刻 ≤ 轮末整装时刻）、markSessionIdle 只填 null 不覆写——无不利交互。

**review 双根修（196deb895）**：
1. expandPairingWindows 负计数（架构级）：窗计数是列表级引用计数（卡侧协程 ++/finally -- 配对），resetIfOwnerChanged 换主清零在飞窗 → 关窗减成 −1 → `>0` 恒配对判据永久失效（DSH 步边界换主常规路径可触）。根修=计数所有权归卡侧窗协程，物主 reset 不再清零（reserved/trueHeight 重立保留——那是正确的物主域语义）。PairingWindowResetTest 双断言。
2. turnActive 视觉防抖收窄：会话级 LocalTurnActive 直并 visualStreaming 会让所有历史轮尾部计时器复活跳动。根修=提取纯函数 turnTailVisualStreaming，防抖门收窄 isTurnLast（B 桥接语义完整保留），TurnTailVisualStreamingTest 四行判定表。

**真机 E2E（houji e69a99d8，R1-R9 全 PASS，零崩溃，43 工件 /tmp/540r-*）**：R1 FAB×5（pin→LEAP 2-3ms、atBot=false 零、FAB 消失）·R2 发送×2（primary 恰 1 条/次、零 verify-retry、全流加证零翻转）·R3 DSH 流式中展开（pairing-window open/close 配对、38 组 set 全配对、零 nodis/drop/top-pin；11 份日志全局 windows= 负数零）·R4 输出完即展开（机件链完整、零 in-streaming-turn 降级；tap 晚于完全结算 ~60s，B 边界即打性由单测+R3/R4 两半覆盖）·R8 重进同签名 ✓·R5 cacheKey（思考卡 200/工具卡 259：PRD-warm 命中、两循环同高、互不污染）·R6 机件完整链 3 处交叉+episode 后零修正（清剿删净）·R7 取消路径原子生效零拉锯·R9 V2 stretch（mini-R2 + 压缩卡）。V6 截图 /tmp/540r-hist-tail.png 待人工核验（历史轮固定时长）。nodis/drop 仅出现在贴底跟随流式期=预期免派发路径。

**R9 原始观测（open，定向复现在跑）**：V2 完成态压缩卡展开时窗内双 episode 同 H=720 并发锚定（(9,376)→(10,172)+720）→ 双 exit-CANCELLED → −714 收缩，净效≈单次展开但 135ms 三跳瞬态。认领逻辑已排除尾部兜底双认领（完成态必让位）；嫌疑=item 层同消息换装重组窗瞬态双渲染（共享屏幕级 expansionKey，一次 toggle 点亮两宿主）。定罪与处置见 §20。

## 20. R9 压缩卡三跳瞬态定罪（定向复现 2/4 RED）→ #543 立卡

**复现**（houji，ses_edc0918d9ffeGkF5JsuOwrHL43，脚本 /tmp/543-round.sh v15）：R19/R21 两轮 RED——同窗两条同 +H=720 expand-anchor（间隔 111/127ms）+ 双 episode 并发（tap 同刻出生、97-107ms 先后完成，并存 ~300ms=≈18 帧持续双组合）。编舞勘误：TurnFin±0.5ms 内 tap 被 burst#1 重组风暴吞掉（4 轮实证零响应）；有效配方 TurnFin+1.35~1.41s（burst#1 与 burst#1.5 reconcile 之间）。模型事故处置：exo-free 410 → 切 zhipuai glm-4.6v。

**双宿主来源裁定=槽位迁移旧新并存（换装重建窗），排除数据重复**：Room（WAL 三件套 89 行 89 distinct id）/服务器 API 双零重复；第二锚严格出现在 upsert:MergeStrategy→[452-display] 重建之后；InjCard 同 id 双渲染；稳态 dump 节点数=1（瞬态收敛）；阴性对照成立（idle tap 单 episode、手动收起单 steady-report −720、无重建重叠轮全单锚）。

**静态排除**：diffListInto 同长就地 set/异长 clear+addAll——单组合内原子应用，不产生可见中间态；diffDisplayItemsInto 同。嫌疑收敛：itemsIndexed key=entry.key **含 chunk #c<i> 后缀**（#438 注释实证）——重建窗内同一消息条目身份变更（重分片/换装 key 变更）产生新旧两 key 并存，未走 #509 原地换名语义。两宿主共享屏幕级 expandedStates[expansionKey]（CompactionDividerPolicy.kt:184,256 expansionKey=msg.id），toggle 一次点亮两宿主各自跑完整机件 episode（各自局部语义正确，总和=双倍位移）。

**变体谱**：R9=双 episode 同刻 exit-CANCELLED+−H 收缩第三跳；R19/R21=双 completed 无回撤跳。同族双锚根因。

**处置**（#543 立卡，P2）：修复靶=chatEntries 分片/换装身份变更的原子性（#509 域残余通道）；先加 episode 级探针（hostItemKey+expansionKey 打点）钉死双 key 具体形态再动刀；**禁 episode 层去重/幂等补丁**（补偿味，违反消除源哲学）。原始件：/tmp/543-repro-run{0..21}.log、/tmp/543-r{19,21}-shot*.png、/tmp/543-roomdir/、/tmp/540r-s9b.log。
