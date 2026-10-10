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
