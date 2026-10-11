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

## 21. #543 根修：嵌套双 reveal 引擎定罪与唯一所有权修复（af1e785a8）

**定罪（判读轮，e99e93197 探针）**：四假设裁定——H1/H2（跨 item 双条目）/H4（displayItems 双行，display-dual 恒 0）全排除；真身=H3 精确化：**单槽单卡内嵌套双 reveal 引擎**。压缩卡内部 MessageBubble 内容栏 CardExpandReveal（contentExpanded 分支）+ CompactionCard markdown body CardExpandReveal 被同一 expanded 驱动，每 toggle 各跑完整 episode 各锚 +H（6/6 恒成对 episode-start 同 hostKey 同毫秒）；换装重建不重建槽位（slot-compose 零新增），其作用=取消在飞 episode 对（LeftCompositionCancellationException ×2 = R9 −H 回撤变体）+同槽重武装。reasoning 折叠块对照恒单 episode（结构对照成立）。

**根修（af1e785a8）**：展开引擎唯一所有权——CompactionCard:147 注释自述互斥意图（「无支持行＝随展开态动画」）但实现成「外层动+内层也动」。修复：无支持行分支卡内去 reveal 直渲染 body（外层 MessageBubble reveal 独享动画）；有支持行分支维持卡内 reveal（外层常驻 null）。**EventCard 同型同修**（description==null 分支；InjectionCard 本就独占无恙）。非补丁——实现纠正回自述设计意图，与家族约定对齐。

**验证（houji Phase 6 全绿）**：V1 idle toggle ×10：10/10 恰 1 episode-start、5/5 恰 1 锚（+H=720）、LEAP 严格对称 ±192、零取消/零三跳；视觉无回归（展开体 156→756 正常渲染，收起归零）。V2 重建编舞 5 轮：「同 toggle 双 episode-start」0 次出现（核心断言）；V4 reasoning 对照 3/3 保持单 episode（未误伤邻域）。V3 EventCard 腿 SKIPPED——4096 全会话无带正文的 event 消息（无实例可测；同型修复经代码对称覆盖）。修复前→后：压缩卡 2/toggle→1/toggle、双锚→单锚、双取消变体→0。工件 /tmp/543-fix-*。

**探针处置**：[DEBUG-543] 三探针（episode-start/slot-compose/display-dual）按用户「探针永久保留 DEBUG-only」裁决保留为 #543 回归探测器（签名：toggle 双 episode-start=复发）。

**单测缝说明**：嵌套双 reveal 是组合结构属性，JVM 缝不可达（无 compose 树断言基建）；回归防线=E2E 探针签名（本轮即 Phase 1 回路的绿态）。

## ## 22. 用户 beta 复测反馈定罪：降级路径负向配对三缺陷（V2 SSE 腿仪器+视觉三层实证，2026-10-10 深夜）

**用户反馈（原话要义）**：输出中展开/收起仍上下震荡、像渲染后补偿；输出完毕列表静态后同一操作正常。修正澄清：仅输出中有，静态无——排除翻转竞态假设，嫌疑域=流式活跃期降级路径本身。

**环境**：houji dev 包（与 HEAD ec4c39551 同码）+ v2:4096 真实 SSE（此腿 R3/G6 从未仪器覆盖过）；新会话 MiMo-V2.6-Flash（reasoning+question 工具+双子智能体多相 turn）。工件 /tmp/v2leg-*、/tmp/v2col.mp4。

**绿腿（先立基线）**：步间窗（question 等待中）展开 44 档/收起 15 档全配对（set/release-dis consumed==capd 逐帧精确、零 nodis/yield）；离底锚重复展开亦干净——A 案正通道在 V2 上工作正常。

**红腿三缺陷（输出中）**：
- **D1 展开帧内缩未配对（consumed=0）**：exp3——toggle 后 21ms flush(0,0) release-dis capd=-699 **consumed=0**（同时窗 GapDiag MC len=2468 async=true=展开触发 asyncParse 重解析 + InjCard re-render 重组）→ −699 全额未配对=首跳；随后 parse 分档增长逐档 set LEAP (0,0)→(7,3256) 共 +3615=拖拽第二段。用户观感=先跳再拖。
- **D2 收起配对被 yield 误判饿死**：col3——收起全程 76 帧 release-dis 全被 yield(external-pending) 拒发：塌缩自带被动锚漂移使 read≠lastSet 逐帧成立，shouldYieldPairing 无法区分「自身塌缩漂移」与「外部显式意图」；read 持续运动 → shouldAdoptExternalPosition 静止采纳永不触发。配对设计全程零执行，视口被动漂至 (7,477)。
- **D3 大卡收起 dispatch 边界钳制缺口**：col4——首档 release-dis capd=**-3655 consumed=-277**（LEAP 7→0 off 277→0 触底原点墙），**3378px 补偿缺口全额未配对**；后续每档 consumed=0。录屏视觉定谳：收起=瞬时猛拉（下方内容上跳约 30% 屏高，非动画），1 秒内完成。
- 辅证（D0 行为差异根）：降级路径无 settle 等待（机件 #422 settleUntilContentStable 专属），asyncParse 分档落地期视口被逐档拖拽；机件路径静态 settle+原子反射+幕布揭示零中间帧=用户「静态正常」的机制面。

**结构根因陈述**：降级路径（流式 turn 卡）与机件路径（静态）确为两套逻辑（CardExpandReveal.kt:565 单分叉，LocalInStreamingTurn 门控）；A 案修好了**正向（展开增长）反射通道**，但**负向（收缩）补偿走 dispatchRawDelta 增量通道**——该通道在贴底/近底几何被列表滚动边界钳制（消费不足/为零）且被 yield 守卫误判饿死；正向反射 set 写 item 内偏移无边界问题。负向通道缺一个与正向对称的反射出口（§12 当年弃反射因「负向折叠越窗尺寸谱缺失」——#438 R-1 dataKeyAt 越窗键投影已提供该机构）。

**修复方向（消除源，待用户裁决）**：①负向配对改走反射通道（复用 resolvePairedTarget+#438 dataKeyAt 越窗折叠；D3 根修）②yield 守卫对窗内（expandPairingWindows>0）负向释放豁免或以「预测塌缩漂移」判别（D2 根修）③展开触发 asyncParse 重解析的源消除（retainState/预热，D1 的分档面）。禁：episode 层兜底/事后回锚（补偿味，违 #540 清剿）。

**残留说明**：R3(G1-G3) DSH 演示绿腿与本腿不矛盾——演示容器只有短文本 turn（E4 勘误在案），无大思考卡重解析、无 question 卡重簇缩、无大卡收起（−3655 级），三缺陷面全部缺席。


## ## 23. 二轮第一刀实施与验证（裁决落地，2026-10-11）

**裁决**：四轮拷问收敛（spec docs/specs/2026-10-11-unified-expand-placement-design.md）——§12 弃反射理由推翻、#433 延伸、#420 原则维持实现退役、目标架构 C 全统一两刀落地（第一刀挂 #540、第二刀立 #544）、收起语义=表述乙（揭示缘参照）、yield 宽豁免、重挂裁 ii⁺ 幂等。

**实施（四件）**：
1. ③接线：ReasoningBlock 传 everKey（pinKey）+收起态流完预热（prewarmTerminalParse：MarkdownParsedStateCache 同键形后台解析，照 #515 先例）；
2. 乙反射化：applyPairedShift 负向分支弃 dispatchRawDelta——shrinkPairedTargetFiso 纯函数资格判定（锚在塌缩区[stickTop,stickTop+span]内→粘揭示缘 目标=trueH−stickTop；区外→零操作 null——LazyList 默认锚定本就正确，盲镜像位移=col4 跳变自造的修正读法；未捕获→保守镜像钳底）；反射写+lastSet 回写；release-ref 新签名取代 release-dis；
3. 宽豁免：shouldYieldPairingWindowed（窗内一律不让位）；
4. ④补洞：降级分支同值守卫改造（初值 false+收起态挂载跳过）——展开态挂载（重组重建）与真 toggle 同样开窗。
配套：HeightReserveState 增 hostItemTopY（CML item Box onGloballyPositioned，物主键守卫）+expandCollapseStickTop/SpanDepth；reveal-geo 探针（stickTop/span/itemH/boxY）。

**验证**：compile 绿；全量单测 --rerun 绿（新增 ShrinkPairedTargetTest 8 判定表/YieldWindowedExemptionTest 4/PrewarmTerminalParseTest 3；FlushTaskPhasesTest 旧 dispatch 契约重写为反射三态：未捕获镜像/区内粘缘/区外零操作）；lintDevDebug 0 错误（4m32s）。
**E2E（houji dev 包+v2:4096 MiMo-V2.6-Flash 多相轮）**：
- 腿A 输出中展开：reveal-geo 捕获（stickTop=56）、零 release-dis/dispatchRawDelta/consumed=0/yield、逐档 set 配对（pilot 揭示节奏 11-33px 小步）；
- 腿B 输出中收起：39 帧 release-ref，fiso 精确锁定 h−56（5074=5130−56…逐帧咬合）=锚严格粘揭示缘随塌缩下行；录屏复核无猛拉（对照修复前 30% 屏高瞬时跳变）；终态稳定；
- ③ everKey 实证：再展开命中 path=cachedTerminal len=3199（pilot 终态缓存复用，不走 asyncTerminal 重解析）；后续 MDResize 档全为弹簧动画步且逐档配对（设计内编舞）；
- 腿C 静态机件：展开全链 settle→quiesce→expand-anchor→episode done 276ms 与 R4-R6 同族零降级；收起 episode-start target=0.0 零 flush 通道介入——机件路径零回归；
- DSH 腿：demo 模型本轮不出推理卡，共享管线签名已由 V2 腿全覆盖（同一渲染代码路径），等价性论证入档；R3 类签名（degrade toggle+pairing-window+paired set）在腿A/B 逐项验证。

**残余**：prewarm 的 MarkdownParsedStateCache 路径（真首开场景）本轮未获直接实证（everKey cachedTerminal 先命中）——单测覆盖+代码对称，留 E2E 复验面；第二刀（#544 编舞统一）待第一刀用户验收后开工。工件 /tmp/k1e-*、/tmp/k1col.mp4、/tmp/k1c-*。


## ## 24. 第一刀系统性真机 E2E（用户指令批：不介入，2026-10-11 深夜）

**矩阵与判定（houji dev 包+v2:4096，工件 /tmp/r1-*、/tmp/r2*、/tmp/r4-*、/tmp/r5-*）**：
- **R1 无干预全流窗**（200s+ 长思考+长答）：零崩溃/零旧负向通道/零 yield/零 release-ref（无交互预期）；SGR-GATE 全程仅发送时刻 1 击 primary；nodis×9=贴底物理跟随预期面；VTRACE 终态 (0,0) 钉底——#541 基线签名保持。
- **R2a 输出中展开（贴底）**：reveal-geo 捕获、65 档 set 全配对、零 dis/yield/consumed=0。
- **R2b/腿B 输出中收起**：39 帧 release-ref，fiso 逐帧精确=h−stickTop（粘缘数学咬合）；录屏无猛拉（对照修复前 30% 屏高跳变）。
- **R2d 离底阅读位**：展开 53 档配对（期间唯一 yield 为滚动手势期正常让位——守卫本分）；收起 37 帧 16k 大跨度粘缘精确。
- **R5 连击竞态**：3 toggle/3 开窗/3 关窗、终态 windows=0、计数严格配对；异常扫描 9 行全为系统噪音（MIUI xr flags/captain 进程）。
- **R4 收起态流完首开**：syncSmall 路径（本轮推理 1427≤2048 同步解析）首测即终高、54 档配对——**≤2048 面实证**；>2048 面（prewarm 缓存路径）两次造轮被工具因素干扰（截断括号触发模型跑 ls -R 跑偏轮+巨内容引发 #477 内容级 a11y 盲化），未获真机直接实证——单测（PrewarmTerminalParseTest）+everKey cachedTerminal 实证+与 #428 命中路径代码对称覆盖，留 beta 复测窗口自然覆盖。
- **静态机件回归（腿C）**：settle→quiesce→expand-anchor→episode done 全链与 R4-R6 同族、零降级介入；收起 episode 零 flush 通道。
- **R2c 区外零操作几何**：构造与可视性矛盾（头部可点击必锚在体内）——昨夜该几何诞生于 LEAP+重簇漂移组合，今日粘缘正确反而不可造；FlushTaskPhasesTest 三态单测覆盖+自然观察收档。
- **R3 步间窗**：本轮会话未触 question 流；降级分支同一代码路径已被 R2 系覆盖。
- **R7 DSH**：demo 模型（nemotron free 轮换）两轮不出推理卡；共享管线等价性论证（变更面全部为服务器无关渲染层）。
- **R8 全批次巡检**：13 份日志零 FATAL/零 release-dis/零负计数；release-ref 恰好只出现在收起腿且与 set 1:1。

**过程发现（非缺陷）**：#477 dump 盲化存在内容级触发面（含 ls -R 巨输出的会话，冷启新进程 3 分钟内即盲）——佐证 R 假设（内容创建时刻/体量相关），已在观察卡域。img 分析通道（CDN 签名上游 400）双侧故障，视觉腿以录屏工具+日志签名替代完成。

**结论**：第一刀在真机输出中/静态/竞态/位置矩阵全绿；两处显式残余（prewarm>2048 真机实证、步间窗复验）交由 beta 复测窗口。


## ## 25. review 轮回执处置：P1+三 P2 即修，两 P2 登记 #544（2026-10-11）

**双轴 review（分身，ec4c39551..HEAD）**：Standards 零硬违规（SSE 铁律逐条过）；Spec 发现分级处置如下。

**即修（本 commit，全量单测绿+lint 0 错误+真机静态抽查同签名）**：
- **P1 混合帧拆账**（applyPairedShift 负向分支）：total=帽净负+ledger 增长的合并量曾让 noop 格把同帧本应 +g 配对的批增长一并吞掉（takePaired 已清账不补→收起窗内全部批增长累计为一次性上漂，正坐在被定罪的「输出中收起」流形）。修=决策只用帽负分量（reserveShift），ledger 折加按格处理：区内格绝对量免疫（trueH−stickTop 已含同 item 增长/他 item 增长在此锚位格恒免配对）；镜像格折净量；noop 格 ledger 非零仍写 fiso+ledger。
- **P2 物主守卫**：shrinkPairedTargetFiso 隐含假设锚 item=帽物主——排队发消息（物主 index≥1）/收起动画中换锚时 anchorTop=itemTrueH−fiso 为跨 item 垃圾值可误判入区向错误 item 写 stick。修=visibleItemsInfo 反查物主 index，fii≠物主一律区外零操作。
- **P2 prewarm 触发面收窄**（ReasoningBlock）：LaunchedEffect(isStreaming,expanded) 首组必跑曾让滚动浏览历史会话时每张 >2048 收起思考卡都烧一次 Default 解析（CPU+LRU32 扰动）。修=prevStreaming 翻转门（仅 true→false 才预热）。
- **P2 亚像素防御**：净量∈(−1,0)（分数 ledger+整数帽负）曾可 require(delta<0) 收 0 崩 flush。修=reserveShift>−1f 亚像素门（跳过塌缩判定只走 ledger 折加）。
- nit：reveal-geo 探针 boxY=NaN 打 "na"（原 NaN.toInt()=0 与真 0 不可辨）。

**登记 #544（第二刀同批）**：①ScrollCompensation.kt:877/:962 头注释漂移（仍称"已改走 dispatchRawDelta"与本刀相反）②多窗单槽冲突（同 item 两卡并发收起时后开窗者独占 stick 槽，先收起卡负向帧引用错误揭示缘=有界瞬态；修=槽位带 entryKey 标签按 hostKey 匹配消费）。

**测试**：FlushTaskPhasesTest +3（物主守卫 noop/混合帧 noop 格 ledger 折加 set(3,1650)/亚像素镜像 499）+ 既有 3 例改 stateWithOwnerItem（物主可见性缝）；全量 --rerun 绿（1m47s）；lint 0 错误（4m39s）；houji 静态抽查：settle→expand-anchor→episode done 282ms 零降级介入、收起 episode-start target=0.0（唯一 flush 行为机件 #430 稳态正配对=设计内）。

**review 裁决依据存档**：分身总裁决「可放行 E2E 无 P0」；P1 为分析性发现（不在 leg B 已验证区内几何上）——按「发现即修」纪律随 beta 复测包一并落地。


## ## 26. 用户 beta 复测二报定罪与资格判定修订（2026-10-11 凌晨）

**用户二报**：①sse 输出时展开会震；②收起有几率拖动上半部分（理论上不应动）。路由裁决：均非 #544（第二刀=编舞统一）范围，属第一刀资格判定缺口，归本卡即修。

**②定罪（两 face，几何全推导+单测钉死）**：
- face-1「跳揭示缘」：修订前区内格目标=trueH−stickTop（首帧跳到缘）——锚在被收起体内深处时＝一次性把上半内容拖下一大截（缘从屏中跳到视口底）。修=**逐帧镜像骑行**（fiso−Δ/帧，随上方内容坐标下移保持画面固定，自然落位 fiso₀−H，越界钳 item 底为跨 item 折叠有界近似）。
- face-2「盒上方漏配镜像」：修订前盒上方走区外零操作——但上方内容坐标随塌缩**逐帧下移**（item 底不动、总高缩），零操作=被动被拖。修=镜像侧扩展到盒底及以上（anchorTop ≤ stickTop+spanDepth）；零操作仅保留给盒下方新内容侧（该侧坐标不变，LazyList 默认锚定本就正确）。
- E2E 实测（R2d 16k 大跨度收起）曾验证旧 stick 数学精确（fiso=h−56 逐帧）——该验证成立的几何=锚已在缘附近；修订消除的是锚深体内时的首帧跳变与盒上方漏配，镜像是两者的统一超集。

**①分析与处置**：正向窗内配对原为盲配（裁决时「锚位格全部让位」）。推导发现：锚在揭示缘上方 ⇒ 卡在视口底之下 ⇒ **tap 不可达**——盲配对 tap 展开不构成错误面；唯一可达面=④重挂窗（阅读位上方重组重挂已展开卡，+H 在锚下方）→ 已加**正向否决门**（reserveReleasePlan 增 anchorAboveReveal：仅正增长可否决，负向走自己的塌缩判定；未捕获几何不否决保持 legacy）。①的残余候选归档：a) 分档 LEAP 观感（#544 原子性+预热已有排期）；b) 重组churn 重挂窗反复配对（需用户复测口径定位：服务器类型/卡片种类/滚动位置/构建时戳 23:15 vs 02:54——02:54 前的构建含未修的 stick 跳变，②的面可能同时贡献①的观感）。

**实施**：shrinkPairedTargetFiso 判定表重写（镜像/零操作/未捕获三态）；applyPairedShift 负向调用链简化（未捕获镜像并入纯函数）；reservePhase 计算 anchorAboveReveal 接线。测试：ShrinkPairedTargetTest 重写 8 判定；FlushTaskPhasesTest 改 3 增 2（镜像骑行/盒下方 noop+ledger 折加）；ReserveReleasePlanTest 增 3（否决正增长/负向不受否决/未否决恒配对）。全量 --rerun 绿；compile 绿。真机复验腿因流式目标 tap 漂移两次未命中（a11y 内容级盲化+卡片移动窗），行为面以单测矩阵+先前通道级 E2E（release-ref 反射链不变）收口，交用户 beta 复测直接验证。


## ## 27. 根修审计（帧前高度）+ 炮台驱动可靠性定罪 + 5h soak 发射（2026-10-11 上午）

**根修审计定罪（用户「确保根修」指令驱动）**：判定表锚位原用帧后真高（trueH−fiso）——塌缩帧 trueH 已含本帧收缩量，锚位被低估一个 δ（初帧 δ 实测可达数百 px），盒下方临界锚会误入镜像侧被拖。根修=帧前高度（reserved−fiso；帽协议下 reserved 恰为评估时刻未释放值，递归自洽：上帧释放后 reserved=上帧后高=本帧前高）——判定边界与 toggle 时刻捕获几何严格对齐。正向否决门同修。参数更名 itemPreH+KDoc 承载；单测全量绿（判定值全部不变=夹具本就是 toggle 时刻高度语义）。

**炮台驱动可靠性定罪（非产品缺陷）**：rr-battery v1/v2 三轮 tap 全空——定罪链：uiautomator dump 取文档序第一卡=旧轮完结卡（走机件路径无 degrade 签名）→v2 改取最底卡仍空→活体取证：tap 事件均送达（MIUIInput DOWN/UP 在案）但 RB-EXP mapHit=false 无 toggle 注册+像素差分被流式刷新淹没——定性=**流式目标上 dump 驱动 tap 的组合不可靠**（陈旧树+目标移动+偶发未注册三因叠加，#477 域已知面的 tap 侧形态）。测试策略重构=验证式 tap 协议（位置双读稳定才 tap+签名验证+位移重试）并入 5h soak 分身执行。

**5h soak 发射**（subagent driven，用户指令）：25 成功周期目标/5h 上限；变体矩阵（思考相/文本相/静态/连击×prompt 轮换含长卡轮）；断言组=展开（geo+零旧通道+配对）/收起（镜像骑行逐帧校验±4px+单调+无大跳）/连击（计数配对）/全局红线（零 FATAL/零 release-dis/零 windows 负）；缺陷分诊（tap-miss≠缺陷；断言 FAIL 保留片段继续；FATAL 循环才提前终止）。工件 /tmp/soak5h/。

**beta 时序注记**：03:29 beta 不含本节帧前高度根修——soak 通过后重打。


## §28 5h soak 收官——红线全绿 + 双 DEFECT 判读非缺陷 + beta 重打

- **执行**：09:57–14:43 真机 houji dev 包 v0.4.0-beta(1791660859)，v2:4096 反向端口全程保持；53 编号周期 + 14 调试期运行；证据 78 文件归档 `~/.cache/soak5h/`（内存副本 /dev/shm/soak5h 重启即失）。
- **总量**：成功收起周期 13（全镜像写入零违例 3：c36=27帧/c37=30帧/c51=35帧；区外 noop 合法腿 10）；exp 腿 17 PASS/3 FAIL；tap-miss 51（流结束后滞后 tap，协议内不计）；变体腿 burst 9 PASS、static tap PASS 4、textphase 全程 noflip（思考完成无自动收起——流中卡不自动翻转，符合降级路径设计）。
- **全局红线（终扫）PASS**：FATAL=0 · pairing-window windows=-N=0（含 7 连击 burst）· release-dis=0（源码核实：通道已退役恒真）· 零崩溃。
- **[DEFECT-1] 窗后 yield(external-pending)（c13/16/26，3/20）→ 判读非缺陷**：亲自核 f16/f26 原始日志——窗关后 13–28ms read(fii=7→0,fiso=1→0)，期间无任何滚动写入日志，且**两例流均在窗关后 ≤34ms 结束**（后续零 SGR 帧）。定性=turn 完成时刻流式 item 结构/终态渲染重映射撞 toggle 窗尾；yield 为守卫性不写入（正确让位），流已结束无后续配对帧。边界态签名，不修；#544 第二刀降级路径退役后自然消失。
- **[DEFECT-2] 收起镜像帧合并偏差（c22/27/52，9 违例帧/90+）→ 判读非缺陷**：逐行核账 c27——h 回写与 capd 逐帧精确匹配，fiso 写入比「capd 累计预测」多减 33/23/129px。多出量=**塌缩动画同期推进量**（trueHeight 被动画逐帧压低，flush 目标位正确含动画进度；断言预测公式只含帽计划量=模型盲区）。终态精确收敛：fiso 钳 0（coerceAtLeast 合法）、h 平滑至 503、尾段 24 帧 noop；无 >800 单帧跳变。写入正确，不修。
- **[非缺陷·环境] BLANK-RENDER-FREEZE ×5 → 已登记 #545**（P2）：历史会话内整串 input text 必现主线程渲染冻结（a11y 树空 2627B、进程存活、仅 force-stop 可恢复）；新空会话+分块注入 40+ 周期零复现；手工几乎不可达。
- **测试侧勘误（分身终报自纠）**：镜像断言行序一帧错位（行内自洽语义 fiso_n=fiso_{n-1}+capd_n）；长上下文 ~84k tokens 抑制 MiMo reasoning（NOCARD 根因→改每周期新会话）；c17 大跳为测试侧刹车滚动污染已剔。
- **beta 重打**：14:49 版 app-beta-release.apk（0.4.0-beta/41 沿用测试包豁免；release keystore CN=OC Beacon 验签 ✓）——含 §26/§27 全部根修（逐帧镜像+正向否决门+帧前高度）。**03:29 旧 beta 不含帧前根修作废，以本包为准**。装机时无线 adb 闪断发作（mDNS 陈旧端口拒连），后台轮询自愈后补装（见 §29 补记）。

## §29 补记——beta 装机阻塞于无线 adb 掉线（待用户恢复）

- 14:49 beta 包验签后装机两次失败：`adb install` 会话卡死（3.5min 无响应）→ push/pm install 报 `connect failed: closed / device offline`——houji 无线 adb 闪断再发作（[[wireless-adb-flapping]] 已知史）。
- 与既往「端口轮换」不同：mDNS 广播完全消失（旧记录 192.168.1.110:41571 拒连且不再刷新），15min 后台轮询 60 次未自愈 → 判定设备端无线调试服务整体掉线，需物理介入（重开无线调试或插 USB）。
- 按真机纪律未触碰任何系统设置。APK 已就绪：`app/build/outputs/apk/beta/release/app-beta-release.apk`（0.4.0-beta/41，14:49，含 §23-§27 全部根修）。恢复连接后直接 pm install -r 覆盖装（同证书，无数据迁移）。

## §30 装机落定——用户指令 USB 通道，实装 PLK110

- houji 无线仍离线；用户 17:13 指令改走 USB——实际接入设备为 PLK110（3B165D00SX600000，USB transport，用户以此解禁本机本次安装操作）。
- pm install -r 成功（17:13:46，versionCode=41/0.4.0-beta，覆盖 03:04 旧根修链包；同证书保数据保配置）。
- reverse 重建：tcp:4096（v2）+ tcp:4199（v1）；设备侧 curl 127.0.0.1:4096/doc = 200 连通验证通过。
- 复测面就绪：流式输出中展开（①震）+ 收起（②拖上半部）；PLK110 侧 beta 已含 §23–§27 全部根修。

## §31 三报根修——降级 AV 尺寸退役为 flush 单事务驱动盒（竞态热修+真机五腿+帧级数值验证）

- **三报定罪（用户 PLK110 beta 复测：展开还好、收起来回震荡）**：`streamingHeightReserve` 帽是天花板（`min(child, reserved)`）——增长被帽遮到 flush 同事务「抬帽+滚动」原子落地（展开/流式增长稳定的原因）；**收缩穿透 min() 当帧进布局**，而滚动修正是 pending 待定位、下一遍 measure 才消费 → 每帧 draw 画在「新高度×旧滚动」错位态、下帧回位=逐帧交替震荡。展开常见几何（锚在揭示缘下方）被否决门判零写入=单写者天然稳定——与「展开还好、收起震荡」精确吻合。soak 日志断言只验写入目标（数学正确），draw 时刻相位错不可见=五小时 13 次收起全绿仍震荡的盲区根因。
- **根修（fc0710453）**：降级路径 AV 尺寸动画退役——盒布局高=`DegradeDriveState.h`（快照态），由 flush 单事务步进（`degradeDriveStep` 指数逼近 32%/帧）+滚动写同快照、同遍 measure 原子消费（帽协议增长向的对称复刻）；内容常驻组合无约束测高、顶锚裁剪=shrinkTowards/expandFrom Top 语义、alpha 派生淡入淡出；帽预记 `reserved=trueHeight+driveStep` 防残差帧重复记账；让位/手势帧步进随 plan 丢弃（冻结而非错位）。无 reserve 供给（横幅/预览）保留裸 AV。单测 +4 组（纯函数/纯驱动帧原子事务/手势冻结/正向否决仍推进），全量 4031 绿。
- **竞态热修（3a1f9b0 后继 commit）**：首版驱动注册在 LaunchedEffect（本帧布局**后**执行）——visible 翻转的重组当帧 contentActive/hProvider 先读到未注册态（driveOwned=false）→ 收起内容瞬时 dispose（h0=0）/展开瞬时满高（h0=tgt），驱动动画被完全旁路成单帧裸落（真机 19:02 双向定罪：col `h0=0 tgt=0 capd=-2866 noop`、exp `h0=2866 tgt=2866`）。热修：翻转检测+驱动注册+开窗+几何捕获全部挪**组合期同步段**（同帧布局即读 driveOwned=true），稳定监视循环留 LaunchedEffect（toggleSeq 键控）。
- **真机验证（houji 无线 19:12，dev 热修包）**：五腿全命中（3 收起+2 展开）；收起签名完美——`drive h0=2016 tgt=0` 同步注册、21 帧指数步进（-645→-438→-298…恰 32% 衰减）每帧 drv 与 set() 同快照、fiso 单调骑降钳 0、h 平滑收敛；展开同构 21 帧（正向分支日志暂无 drv 标记，capd 序列+set 骑升等价证明）。
- **帧级数值验证（录屏 119fps 相位相关）**：三次收起上半屏**零符号翻转**、累计 ≤2.6px（画面钉死），下半屏单调上滑 -230~-257px 平滑塌缩；两次展开双半屏零位移（纯揭示）。唯一残余：C1 首驱动帧上半屏单帧 88px 一次性自校准（几何捕获一帧滞后，1/3 腿、无交替）——观察项不阻塞。**震荡结构性消除**。
- 测试侧沉淀：PLK110 pm install 需亮屏解锁（黑屏挂起）；MIUI 安装 debug 包弹兼容性告警（可调试应用+16KB 对齐提示，见 #546）；录屏>8MB 视频分析通道不可用 → 宿主 pip opencv-headless 相位相关逐帧分析（`/dev/shm/v540h/` 含 dys/tops/bots.npy 可复跑）；10.0.2.2→4096 模拟器通道可用（备用）。

## §32 beta 交付——19:23 包（三轮全修）落装 houji

- 19:23 beta（0.4.0-beta/41 沿用测试包豁免；release 验签 ✓）含 §26/§27/§31 全部根修（逐帧镜像+否决门+帧前高度+驱动盒+竞态热修）。
- PLK110 USB 已拔（用户取走）；beta 落装 **houji**（覆盖 0.3.0→0.4.0-beta，19:24:10 生效，同证书保配置）。PLK110 侧待重插后 `pm install -r` 补装（APK 就绪于 `app/build/outputs/apk/beta/release/`）。

## §33 四报收官——SILENT-STATE-RESET 定罪（partId 换名）+ 双根修 + soak4r + 复验

- **四报**（用户 beta 复测：流式中连点展开/收起，每次结果不同，疑竞态严重）。
- **系统性 soak4r**（subagent，12 轮 67 分钟，55 degrade toggle/64 窗全配对）：红线全绿（零 FATAL/零负窗/零 release-dis/零跨卡覆盖窗）；七类竞态签名扫描命中真凶——
- **🔴 SILENT-STATE-RESET 定罪**（soak4r c9，RB-EXP 探针验尸）：展开完成的思考卡 2 秒内**无任何 toggle 日志**静默回退收起；id 从 `ng_ord_0`（=…_reasoning_ord_0 派生契约 id 尾 8 字符）变为 `bbb657fa`（REST 真实 partId）。根因链：分段切换（thinking part 结束/answer attach）REST 权威刷新 → `dedupOverlappingTextParts` 内容重叠判胜 → REST 侧胜出携真实 id 落位 → **partId 换名** → partId 键控的展开状态 map 查无此键 → 默认收起。此为 #266 身份回填（mergePart 侧）的 dedup 侧缺口；#509 只覆盖 DSH part id 首帧稳定，V2 派生 id 仍漂。
- **根修 A（竞态三连，commit「驱动列表化」）**：①驱动单槽→列表制（多卡并发不互逐不孤儿）；②stick/span 随卡走 + `driveScrollShift` 逐卡 containment（单卡与旧判定表逐点等价，测试锁定）；③快速重向权威续值（drive.h 不被滞后一拍的放置值回拨）；④非驱动负余量过区界门。4033 测试绿。
- **根修 B（换名，commit「dedup 胜者身份先行」）**：dedup 胜者**身份先行、内容权威**——胜者采派生契约 id（流式在册身份，兼防 Room 孤儿行）；双侧 legacy 维持原语义。+3 单测（REST 权威胜出保派生 id/mergePartsList 装配序落回派生 id/双 legacy 不受影响）。4036 全绿。
- **复验（houji dev 21:27）**：①展开态跨分段存活 PASS——id 70s 全程 `ng_ord_0` 稳定（覆盖思考段完结=原 2s 复位点）、expanded=true 不丢；②连点爆发命中 toggle 签名教科书级（h0=3946 同步注册、指数步进 -1262→-12 每帧同快照 set()、fiso 骑降钳 0、锚离物主后 noop 守卫、零负窗零 FATAL）。8 连点仅 2 命中系卡头漂移+贴顶滚动推离（测试面，soak 已证；产品面健康）。
- **次要发现**（不阻塞，#544 域）：#2 窗双开（分段边界自动路径 5ms 重入，id 稳定后预期消失，观察）；#3 流结束后 toggle 仍走降级路径/流中反走机件（episode 路由时序，无视觉损害）；#4 大 capd 单帧=设计内 deferred dispatch。
- **交付**：beta 21:34 包（0.4.0-beta/41）落装 houji；PLK110 待重插补装（APK 就绪）。soak4r 证据 `/home/leonardo/.cache/soak4r/`（52 场景日志+40 录屏 470MB）。

## §34 架构审查+终包 soak5r 交叉对账——六候选落账与 #547 残留定罪

- **用户质询三问**（是根修吗/为何总加代码/是否过于繁杂+终包全量测试缺口）触发本轮。
- **终包 soak5r**（subagent，12 轮 83 分钟，/home/leonardo/.cache/soak5r/）：修复②驱动列表化全绿（53 episode 严格配对、零方向翻转、多卡 3-4 并发合法、大 capd 全为拒发帧、零 FATAL）；修复①原触发面 10/12 干净；**r13 残留变体定罪 → #547**（多消息轮次重组换宿主→派生 partId 前缀级联→展开态 map 脱靶，79ms 静默清回，与四报同类不同源）。
- **架构勘察**（subagent，8 摩擦点）+ 主线合成六候选 C1-C6，递进 **C2→C3→C1**：C2 死机制清剿（100+ 行零风险）→ C3 三量分通道（高频点接口稳定）→ C1 第二刀主体（降级分支退役+正向单槽几何并入）。净增 2,804 行的反馈循环定性：双路径+共享可变结构下「竞态根修=加协调机制」；终止符=路径消亡。报告 /tmp/architecture-review-20261011-height-engine.html。
- **落账**：#547 新卡（P1，三修向 a/b/c 推荐 a=ExpandStateStore）；#544 note 扩充（六候选+递进+报告存档）；spec §5 补章（税基线/六候选/soak 对账/执行序）；本节。
- **顺手修**：RB-EXP 探针越裁（W 级无条件→D 级+DEBUG 守卫，已 commit）。
- **待裁决**：#547 修复时机（独立先修 vs 随 C4/第二刀合流）；发版前 #547 修复或接受残留。#540 用户五测验收仍挂起。

## §35 #547 根修——展开态稳定键（宿主级联换名免疫，2026-10-12 凌晨）

### 定罪复述（soak5r r13）

V2 多消息 turn 重组换宿主（msg_12b9107bf001JPaf83 → msg_12b91c58a001eWpX9i），派生 partId 前缀级联漂移 → partId 键控 _toolExpandedStates map 脱靶 → toggle 后 79ms 静默回退收起（无 toggle 日志，mapHit true→false）。与四报 SILENT-STATE-RESET（REST dedup 换名）同类不同源。map 无删除路径（toggleToolExpanded 只增写）——脱靶纯系键漂移。

### 修向 a（C4 键律先行）落地三件套（67f1cb867）

1. **ExpandStateKeys.forPart**（ChatCompositionLocals.kt）：派生契约 id（内嵌宿主前缀者）→ "x<kind>:空白归一化前96字符指纹"；服务器赋值 id（工具/patch/shell）直通；空白文本直通。不变量：流式 append 只增长→入窗后指纹恒定；换宿主内容不变→跨重组同键；H1 空白折叠两套字节表示归一同键。
2. **migrateExpandAlias 别名收养**（MessageDataDelegate + Local 管线）：键演化（成长跨指纹窗/空白直通→首内容）时旧键状态单向迁移新键（幂等，新键有值不覆盖）；消费点组合期同步调用（LaunchedEffect 晚一帧=一帧假塌缩，#540 四报组合期同步注册同款先例）。
3. **键切换帧权威读**（PartContent）：rbKeyJustChanged 帧直读 toolStatesFlow.value[key]（migrate 同步写已完成），不读 collectAsState 镜像——镜像下一帧才跟上，读镜像=一帧假塌缩（驱动盒方向翻转复活入口）。

测试逮住的设计缺陷（先行后修，TDD 实效）：初版指纹窗变长窗口（toggle 时文本<96 字符则跨窗键变）——ExpandStateKeysTest「成长跨指纹窗键变」用例钉住该事实，别名收养由此立项。

### E2E 双包 A/B 证据（houji，V2 4096 + MiMo p1，配方 /home/leonardo/.cache/547e2e/）

- **旧包（23:57，无权威帧读）**：r3 抢救轮天然跨窗 c94433c1→ed797412，中间夹 27ms (expanded=false, mapHit=true) 假塌缩帧——预判竞态的反向实证。
- **新包**：9 轮全 PASS（r4-r7、r9-r12；r8 驱动失手非产品）。R1（同键 true→false 无 toggle）/R1'（跨键连续性，扫描器为本卡新增红线）/R2（mapHit 脱靶）零违例；6 起天然跨窗迁移全部 expanded=true/mapHit=true 直落零闪断；k=6 burst 连点轮含。r11/r12 实证同前缀指纹跨会话复现（MiMo 公式化前导）——会话内存 map 隔离，无影响，符合有界碰撞设计语义。
- **宿主换名本体未自然再现**（9 轮全单 turn item；1/12 概率类）：该不变量由单测钉死（r13 双宿主原始 id 同键用例）；线上机制与跨窗迁移同构（map/收养/权威读三件套同路径）。

### 验证清单

ExpandStateKeysTest 10/10；全量 testDevDebugUnitTest 4046 零失败；lintDevDebug 门禁过（基线陈旧提示为既有现象）；commit 67f1cb867。卡片 #547 → verify 态待验收。

### 勘误与登记

- 红线扫描器初版盲区：按键分组漏跨键 true→false（R1' 补上）——判读工具教训同 soak5r N2（可见域≠全部事实域）。
- RB-EXP 探针改完整 id+key+mapHit（N2 落地）；toggle 侧探针同步去 takeLast(8)。
- C4 关系：ExpandStateKeys.forPart 即 C4 ExpandStateStore 键律先行；store 化单一真相源/default 冻结仍在 #544 第二刀范围。

