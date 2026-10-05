# #442 B案 节奏收编设计（delta 直入引擎 · 全链重组根修）

> 承接：spec 2026-09-26-437-height-engine-redesign.md 架构1 + journal 2026-10-01-442 §6 适配性分析。
> 开工裁决（2026-10-02 用户）：B 面（B1-B6）全量 + A 线收尾（A2.5/A4/A3）一次到位；裁决按 §6 调研结果。

## 0. 目标与回退

- **根修根因二**（journal 437:402）：100ms 批 flush 的 `_parts` StateFlow 发射 → 十源 combine →
  ChatScreen 投影 → ChatMessageList 2756 行函数体重跑。B案后流式稳态（无结构事件）顶层重跑 ≈0。
- **不修**：帽/配对/跳转/贴底铁律链全部原样（pilot 域必要工作面不变）。
- 回退：`STREAM_DELTA_BUS` dev-only buildConfigField（beta/stable 无此旗标=false）；关=逐字节今日行为。

## 1. R1-R9 裁决（按 journal §6.5 调研结论）

| # | 裁决 |
|---|------|
| R1 | **reasoning 必须进范围**：ReasoningBlock 直读 bus（B4），否则 glm 系推理先行轮 Waiting 期全链仍在 |
| R2 | 累积语义不变——`_parts` 热视图**照旧每 flush 更新**（applyDelta 纯函数原样）；只改 UI 消费源 |
| R3 | isStaleDelta/inferDeltaKind/持久化全部继续读热视图——**零读点变更**（R2 设计红利） |
| R4 | copy 流式期本就隐藏（2026-09-27 裁决）+ 完结后 structural 权威已发布；搜索走 Room（持久化照旧）——**无需对账 cadence** |
| R5 | 完结权威对账：text.ended → PartUpdated 走结构性发布（携带全量累积）；bus 同点清除；pilot 前缀差分自证（等值=no-op，不等=既有 #472 宽限+resetKey 兜底） |
| R6 | bus 按 partId 键；terminal/removed/clearForSession/clearAll/setMessages(全量替换) 清 bus；DSH echo 语义留在 applyDelta（不动） |
| R7 | **bus 非真相源**：`live ?: part.text` 回退——翻页回收/冷启/旗标关=今日原路径 |
| R8 | JankHold 保留（B案后自然失活=无害；退役留独立清扫批，probe 保留纪律同理） |
| R9 | 归一化全串重算 O(len)/flush 维持（哨兵快路径）；增量归一化留优化位非阻塞 |

## 2. 架构

### 2.1 数据层（B2）

`_parts` 热视图**不动**（所有既有写点/读点零变更）。新增两个出口：

1. **structuralParts**（`StateFlow<Map<String, List<Part>>>`）：UI 主列表消费的结构性视图。
   发布点=中央 `handle()` 分发尾（覆盖全部 SSE 结构事件；Delta 事件后的发布被 StateFlow 值相等去重吸收=零成本）
   + 直调入口（setMessages/upsertMessages/seedCachedMessages/clearFor*/clearAll/patchFileUrl 等 dispatch 外写点）。
2. **StreamingDeltaBus**（ui 引擎域单例，precedent=ScrollQuiescence/broker + 5d061df2 数据层消费引擎域常量先例）：
   `StateFlow<Map<partId, Live(partId,text,reasoning,generation)>>`。flushPendingDeltas 在 `_parts.update` 后
   把本批触及 part 的**累积全文**（与热视图同字符串实例，零拷贝）发布进 bus。

发布顺序约定（完结）：`_parts.update` → `publishStructural()` → `bus.clear(partId)`。

### 2.2 UI 接线（B3/B4）

- **text**：PartContent 文本分支 `val live by bus.liveFor(part.id).collectAsState()` →
  `MarkdownContent(markdown = live ?: part.text)`。重组作用域收敛到 PartContent→MarkdownContent
  （消息链静默后这是唯一失活面）；pilot/gate/shard 机器零改造（驱动源从参数快照换 bus 快照，串内容等价）。
- **reasoning**：PartContent 推理分支同式 `ReasoningBlock(text = live ?: part.text)`（块内重排版=必要工作）。
- **combine 源切换**：MessageDataDelegate:194 / ChatScreen:660(shell 输出解析) / MessageDataDelegate:484(跳转镜像) /
  TaskDelegate×2（任务聚合/前台计数）→ `getStructuralPartsMap()`（ChatRepository 新接口方法，Impl 委托
  eventDispatcher.structuralParts）。旗标关=getAllPartsMap 原样。

### 2.3 A2.5 资格泛化（renderItem 级拆分）

- **注册资格放宽**：首个 Single-Text renderItem 的位置从 index 0 放宽到**任意位置**（推理/工具前缀后）。
- **发射**（buildChatEntries，reverseLayout 语义）：turnShards 命中且分片 part 的 renderItem 索引 k>0 时：
  `Turn(tail, 原键)` → shards 逆文档序 → **`StreamPrefix(key=t_X#p)` 最后发射**（视觉=turn 顶部），
  `displayEntryStart` 钉 prefix。k==0（text-leading）不发射 prefix（今日行为）。
- **k 的计算**：`shardPartIdx: Map<turnKey,Int>` 在 ChatMessageList 从 renderableTurns+streamShards 派生，
  值相等 Map 作 chatEntries remember 键（纯文本增长期不变=零额外重建；B案后 renderableTurns 结构性稳定）。
- **渲染**：tail Turn 走 MessageCardAssistant **子范围 [k, size)**（新增 itemsFrom/itemsTo 参数，默认全范围；
  主循环切片，question 锚计算仍用全表）；prefix 走 `ChunkAssistantItems(subList(0,k))`（复用历史 Chunk 路径
  首段机制——reasoning/工具卡/提问卡锚定全部内建）。帽物主=tail（原键零迁移）；prefix 静态无帽无账。
- **间距**：turn 顶距（isLast 的 bottom padding）职责移交 prefix 在场时的 prefix 条目。

### 2.4 A3 影子态裁决

**放弃**（按调研）：A4 烟测实证 tail 恒 ≤800ch（800ch/frame 快速重灌已覆盖）；影子态复杂度无对应收益。
A4 正式复测若推翻此前提再立卡。

## 3. 测试与判定

- 单测：structuralParts 发布策略（流式静默/结构发射/终态清 bus）、bus 语义、A2.5 发射序与 displayEntryStart。
- 既有全量套件零回归（flush 语义未变——_parts 热视图照旧）。
- 真机 B5：text-first（Big Pickle）+ reasoning-first（LongCat）模型烟测（fire/完结/回收/切会话/零重复）。
- 真机 B6+A4 判定矩阵：旗标关（基线）vs A+B 开——gfxinfo 滑动 p90（≤12ms 目标）+
  **重组计数**（[452-combine]/ENTRIES/DEBUG-jk 发射频：流式稳态 →≈0/s）+ 既有铁律网（VDRAW 泄漏=0/
  CONTENT-BLINK=0/#492 检测网）。

## 4. 批次

B1 spec（本档）→ B2 数据层 → B3 text → B4 reasoning → A2.5 → B5 真机烟测 → B6+A4 判定+收尾。
