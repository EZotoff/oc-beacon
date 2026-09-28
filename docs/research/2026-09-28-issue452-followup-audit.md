# #452 修复后全面深审：remember 集合 key 恒等洞的机制复核与全仓扫描

日期：2026-09-28 · 性质：调研报告（不改动任何代码）· 被审修复：da0300d7（chatEntries remember key：displayItems 实例 → displayItems.size）

## 问题

#452「新会话首条消息发送后不上屏」已修复并独立复验 PASS。本报告做修复后深审：①定罪链逐环复核 ②全仓同模式隐患扫描 ③新 key 边界分析 ④插桩去留 ⑤测试充分性。唯一写入物为本报告。

## 一、根因定罪链复核（逐环验证）

### 环 1：SnapshotStateList 同实例自反 equals 恒真

- `ChatScreen.kt:988`：`displayItemsState = remember { mutableStateListOf<Pair<Int, ChatMessage>>() }` —— 实例在屏幕级生命周期内恒定（R4-B3 步2 设计：差量写入不换实例，`set(i)` 只失效读 `[i]` 的 item 作用域）。
- JVM `AbstractList.equals` 契约首条即 `if (o === this) return true`（引用短路——不遍历内容、不建立内容依赖、不读快照状态）。`remember(displayItems)` 的 key 比较「本次实例 vs 上次实例」永远同一实例 → 恒真 → 计算块在初始组合后**永不重算**。环 1 成立。
- 机制细节（回答「块内读内容有依赖为何还冻结」）：remember 计算块内对 displayItems 的遍历（`ChatMessageList.kt:779` buildChatEntries 读 size/get）建立的快照依赖注册在 **RecomposeScope** 上——变异只使 scope 失效触发**重组**；重组时 remember 先比 key，恒等 → 返回缓存。**失效依赖与 key 比较是两层机制**，依赖失效不等于 remember 重算。

### 环 2：turnGroups/turnAnchors Map 值比较 + user 不入组

- `TurnGroupCalculator.kt:53-73`：`buildAssistantTurnGroups` 仅收 `msg.isAssistant` 连续段——user/synthetic 分支只闭合进行中的组，user 自身**不进任何组**。
- `ChatMessageList.kt:287-296`：turnGroups 由 `messagesLifecycleSignature`（id 序列+completed 位，`MessageFingerprints.kt:34-41`，按序哈希）签名缓存——签名**会**因 user 增量而变并触发 `computeTurnGroups` 重算，但重算产出仍是 `emptyMap()`；而 chatEntries 的 key 比较用的是 Map **equals 值比较**：`emptyMap().equals(emptyMap())` 恒真 → key 不变。环 2 成立（「签名变但值不变」的两级差异是本环最易误判处）。
- turnAnchors 同构：`TurnGroupCalculator.kt:38-50` 只对 assistant 组产出锚；user-only 增量下同样空↔空。
- 已由 `TurnGroupCalculatorTest.kt:87`（`#452 user-only growth keeps turnGroups structurally equal`）钉死。

### 环 3：buildChatEntries 对 user-only 会发射条目

- `MarkdownChunking.kt:459-461`：user 无任何 plan 时落 else 分支 `entries += ChatEntry.Turn(displayIdx, turnKey, isUser = true, ...)` —— 条目发射前提成立（`TurnGroupCalculatorTest.kt:100` 钉死）。
- 真机插桩时间线（修复前 22:35 复现，v2@4096 免费档）：`send-seed 08.800 → [452-combine] visible=2 08.801 → [452-display] n=2 08.815 → ENTRIES 冻结 n=0（08.704 旧值）→（3.9s）SSE 首个 assistant 12.639 → ENTRIES n=2 12.684`。三 key 恒等窗口与条目冻结窗口完全重合，assistant 到达（turnGroups 变非空 Map）即解冻——机制与现象闭环。

### 复核结论

定罪链**完备无缺环**：三通道在「纯 user 增量」同时恒等，且该形态恰为新会话首条消息播种独有（老会话有历史 assistant 兜底、V1 无播种靠 SSE 回显 user+assistant 相邻到达、本地服务器 ~300ms 间隔掩盖）。修复 `ChatMessageList.kt:768` 引入 `displayItems.size`（快照读建立失效依赖 + Int 值比较）→「条目数变化必重建」，修复后真机 send-seed → ENTRIES n=1 18ms，一致。

## 二、全仓同类隐患扫描

范围：`app/src/main` 全部 137 处 `remember(key)` + 6 处 `derivedStateOf` + 2 处 `produceState`。判据三分：

1. **洞**：key 为可变集合实例（原地变异不换实例，如 SnapshotStateList）且 remember 块缓存**纯计算值**（非 derivedStateOf 包装）。
2. **安全**：key 为不可变值语义集合（上游每次发射新实例，equals 结构比较——新实例结构变即失效）。
3. **安全**：`remember(...) { derivedStateOf { ... } }` —— derived 对象复用与否无关，重算由 derived 内部快照读驱动（与值缓存模式本质不同）。

### 🔴 同款洞未修复：displayItems 单 key 值缓存三处（均在 ChatMessageList.kt）

| 位置 | 缓存内容 | 陈旧后果 | 危害 |
|---|---|---|---|
| `:595` `turnOrdinalByMsgId = remember(displayItems) { turnOrdinalByAnchorId(displayItems) }` | 轮次序号表（#310④ 轨迹台账）。注释明言「分页窗口变化号码随之平移」= 设计预期随内容重算 | 初始组合后**永不更新**：翻页后新窗口序号缺失/平移失效 → 台账轮次号错位 | **中**：功能预期被打破；消费面窄（统计弹窗/台账）故未暴露 |
| `:599` `displayItemMessageIds = remember(displayItems) { CompactionDividerPolicy.displayItemMessageIds(displayItems) }` | 尾部兜底去重判据（#217/#226） | 新消息 id 不进集合 → V1 摘要去重判据漏判 | **低**：V1 专属尾部场景 |
| `:602` `v1CompactionSummaryInList = remember(displayItems) { ... != null }` | V1 摘要消息在列判定（Boolean） | 摘要消息后到时判定冻结初值 | **低**：V1 专属 |

修法建议（登记 backlog 后下批做，本报告不改动）：照抄 #452 修复加 `displayItems.size` 为 key，或低频消费处改 `derivedStateOf`。

### 🟡 恒等 key 但有兜底/语义免疫

| 位置 | 分析 |
|---|---|
| `:438` `renderableTurns = remember(rawMessages, displayItems, turnGroups)` | displayItems key 恒等；但 rawMessages（filterNot 新 List，值语义 equals）+ turnGroups 兜底。displayItems 唯一变异源 diffDisplayItemsInto 本身由 `remember(rawMessages)` 驱动（`ChatScreen.kt:993`）→ rawMessages key 必先失效。**安全** |
| `:895` `highlightedTurnKey = remember(highlightedMsgId, displayItems, turnGroups, turnAnchors)` | user-only 增量时三集合 key 全恒等 → 陈旧；输出为「指定 msgId 的 entry key」，user 增量不改变既有消息 key，且高亮 5s 自清（`LaunchedEffect :900`）。**语义免疫** |
| `:1353` `transcriptCardPlan = remember(chatEntries, displayItems, commandFeedbackRows, compactionEntries)` | LazyColumn item 内（item 级生命周期）+ chatEntries 修复后 size 变必重建兜底。**安全** |
| `ChatScreen.kt:961/:993` rawMessages 链 | rawMessages/jkMsgs 均为 remember 产出新 List（值语义）。**安全** |

### 🟢 安全模式抽查（代表性）

- **derivedStateOf 家族**：`ChatMessageList.kt:882-888`（`remember(displayItems,...){derivedStateOf{find...}}`——displayItems 恒等 key 在此模式无害，derived 内部快照读驱动重算）；`ChatScrollController.kt:115`；`SessionTreeList.kt:111`；`QuestionPartContent.kt:329`；`ChatCompositionLocals.kt:60`（key=String）。**均安全**。
- **值语义集合 key**（上游 StateFlow/data class 新实例）：`ServerModelFilterScreen.kt:61`（uiState.groups）、`ChatScreen.kt:619/662/934`（sessionDiffs/allPartsMap/compactionEntries）、`MessageCardUser.kt:112`（contentParts=filter 新 List）、`ContextToolGroupCard.kt:32/35`、`MessageCardAssistant.kt:1311-1317`（step.groups 数据类字段）、`ShareTargetPickerDialog.kt:50/66`、`ChatInputBar.kt:153/163`、`DiagnosticsScreen.kt:136`、`ModelPickerDialog.kt:104`、`WorkspaceScreen.kt:140`、`GitChangesPanel.kt:51` 等。equals 结构比较语义正确，**均安全**。
- **有意引用语义**：`TermuxTerminalHost.kt:62`（同 session 不重建 client）、`SafeFlingBehavior.kt:36`（同 listState 复用 FlingBehavior）。
- **sealed/data 状态 key**：`ApplyPatchToolCard.kt:43`、`WebSearchToolCard.kt:67`、`SkillCard.kt:46`、`TodoListCard.kt:66`（tool.state equals 结构比较）。
- **produceState**：`ImagePreviewDialog.kt:71/120`（String key）。

安全模式逐文件清单（值语义集合 key，equals 结构比较语义正确）：

- `ui/screens/server/ServerModelFilterScreen.kt:61` —— uiState.groups（UI state data class 新实例）
- `ui/screens/chat/ChatScreen.kt:619` —— sessionDiffs（StateFlow 发射新 List）
- `ui/screens/chat/ChatScreen.kt:662` —— allPartsMap（eventDispatcher.parts StateFlow 新 Map）
- `ui/screens/chat/ChatScreen.kt:934` —— compactionEntriesForSession（Room flow 新 List）
- `ui/screens/chat/components/MessageCardUser.kt:112` —— contentParts（filter 产出新 List）
- `ui/screens/chat/tools/ContextToolGroupCard.kt:32,35` —— parts（装配层预计算不可变 List）
- `ui/screens/chat/components/MessageCardAssistant.kt:1311,1314,1317` —— step.groups/stepSlices（data class 字段 / remember 产出新 List）
- `ui/navigation/ShareTargetPickerDialog.kt:50,66` —— servers/sessions/serverSessions
- `ui/screens/chat/input/ChatInputBar.kt:153,163` —— skills/commands（配置流新实例）
- `ui/screens/chat/input/ChatTextField.kt:54` —— confirmedFilePaths
- `ui/screens/settings/DiagnosticsScreen.kt:136` —— entries+查询词
- `ui/screens/chat/dialog/ModelPickerDialog.kt:104` —— providers
- `ui/screens/workspace/WorkspaceScreen.kt:140`、`workspace/git/GitChangesPanel.kt:51` —— git 变更集
- `ui/screens/viewer/FileViewerScreen.kt:91,106` —— annotations/content

共性：这些 key 的上游都是「每次变化产新实例」的值语义源（StateFlow/data class/纯函数产物），equals 结构比较在 JVM List/Map 契约下正确失效——与 displayItems 的「同实例原地变异」形成对照。判别口诀：**看上游是「换实例」还是「改实例」**。

### 扫描结论

同款洞剩余 **3 处**（`:595/:599/:602`，全部 displayItems 单 key 值缓存），集中在 ChatMessageList；全仓无其他文件命中。系统性根因：R4-B3 步2 引入 SnapshotStateList 承载时，下游以 displayItems 为 key 的 remember（共 8 处）中 3 处缺兜底 key、4 处有兜底、1 处语义免疫——#452 修复的是其中后果最重的一处（chatEntries 直接决定上屏）。

## 三、displayItems.size 作为 key 的边界分析

写路径全枚举（`DiffList.kt:8-22` diffListInto，唯一写入通道）：

1. **size 变化**（消息增删/分页/清空重填）→ `clear()+addAll()` → size key 失效 → 重建 ✓。覆盖 #452 主场景与绝大多数运行时路径。
2. **同 size 逐槽替换**（`set(i)`：DSH pending-*→durable 换装、REST 刷新同长度替换）→ size 不失效；turnGroups 大多数变（id 序列变 → sig 变 → 新 Map 值）→ 兜底重建 ✓。**纯 user 换装为盲区**（user 不入组，Map 空↔空）：chatEntries 冻结一轮——但同 size 同结构下 entry 数量与 displayIndex 映射不变，LazyColumn item content 经 `get(i)` 快照依赖自愈显示新内容（**视觉正确**）；残余为 entry.key 陈旧一轮（Compose item 复用语义下 key 与内容短暂错位，极端表现如展开态保持错位），至下次 size/turnGroups 变化自愈。**低危可接受**。
3. **同 size 重排**：sig 按序哈希 id → 含 assistant 的重排必变 sig 兜底；纯 user 重排后 entries key 陈旧但 item 内容自愈（同上）。数据层未发现「同 size 纯 user 重排」触发路径（revert/替换均伴随长度或 assistant 变化）。**理论边界、无已知触发**。
4. **jkHold 冻结交互**（`ChatScreen.kt:946-960` 滚动期快照冻结）：冻结期间 rawMessages 不更新 → diff 不跑 → displayItems 不变 → 无额外窗口。

结论：**不需要更强 key**。彻底方案候选（displayItems 的 id 序列 join 指纹，O(n)/重组）代价收益不合算；设计依据已锚在注释 `ChatMessageList.kt:759-767`。

## 四、[452-combine]/[452-display] 插桩去留

- `[452-combine]`（`MessageDataDelegate.kt:313-321`）：visible.size/sid 双门控——流式期 size 恒定零输出；新消息/会话切换各一条；`sid.takeLast(8)` 无敏感信息。卫生合格。
- `[452-display]`（`ChatScreen.kt:1027-1033`）：size 门控（intArray 记上次值）——流式期 rawMessages 每 48ms 新实例使 remember 重跑但 size 未变 → 零输出（初版无门控的 ~20/s 刷屏路径已修）。卫生合格。
- 两者与既有 `ENTRIES n=`（SGB，`ChatMessageList.kt:780` 附近）互补构成「combine → display → entries」三段管线观测，对后续上屏/流式类诊断有复用价值（本次深审排查 L595 等隐患时即复用该心智模型）。

**建议：保留**（DEBUG 构建专属，发布面零成本）。

## 五、测试充分性

已有覆盖：

- `TurnGroupCalculatorTest.kt:87` `#452 user-only growth keeps turnGroups structurally equal`：钉死环 2 机制根源（若未来 user 入组语义变化 → 测试失败 → 提醒复核修复设计依据）。
- `TurnGroupCalculatorTest.kt:100` `#452 user-only displayItems emit Turn entries`：钉死环 3 发射前提。
- 既有 `:69` `only user messages returns empty map` 与 `:87` 构成因果链。
- `DiffListTest.kt` / `DisplayItemsDiffTest.kt`（共 8 处引用）：差量写入 size 变/同 size 逐槽语义已覆盖。

缺口与建议（按价值排序）：

1. **Compose 层 key 语义回归测试**（最重要缺口）：现有纯函数测试防不了「key 被改回 displayItems 实例」——remember 是组合层语义。建议 androidTest（已有 HiltTestRunner 基建；需确认 compose-ui-test 依赖是否在 androidTest classpath）用 `createComposeRule`：`mutableStateListOf` 驱动 add → 断言以 size 为 key 的计数器递增、以实例为 key 的不递增。这是唯一能直接钉死修复本身的测试层。

建议 1 的测试骨架（androidTest，示意）：

```kotlin
@Test fun snapshotListSizeKey_recomputes_onAdd() {
    val rule = createComposeRule()
    val list = mutableStateListOf(1)
    var recomputed = 0
    rule.setContent {
        remember(list.size) { recomputed++ }
    }
    rule.runOnIdle { list.add(2) }
    rule.waitForIdle()
    assertEquals(2, recomputed) // size key：add 后必重算
    // 对照：remember(list){...} 在同实例 add 后 recomputed 不变（恒等洞）
}
```

说明：跑在真机/模拟器（compose-ui-test-junit4 依赖 androidTest classpath；未确认项目是否已引入，引入前先核对 `app/build.gradle.kts` androidTest 依赖块）。
2. **同 size 换装边界文档化测试**：buildChatEntries 对同 size 新内容的 entry key 稳定性断言（当前「key 陈旧一轮自愈」为隐性契约，宜显性化）。
3. **L595/599/602 修复配套**：各配一条「内容变化后缓存更新」单测（若采纳 size-key 方案，纯函数层即可）。

## 引用（文件:行号）

- 修复点与注释：`app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/components/ChatMessageList.kt:759-768`
- 同文件：turnGroups/turnAnchors 缓存 `:285-309`；streamingMsgId `:422`；renderableTurns `:438`；隐患 `:595` `:599` `:602`；derivedStateOf 安全模式 `:882-888`；highlightedTurnKey `:895`；transcriptCardPlan `:1353`
- 分组器：`ui/screens/chat/util/TurnGroupCalculator.kt:18-26, 38-50, 53-73`
- 条目发射：`ui/screens/chat/components/MarkdownChunking.kt:296-468`（else 分支 `:459-461`）
- 差量写入：`ui/screens/chat/DiffList.kt:8-22`；`DisplayItemsDiff.kt:9-15`
- 签名：`util/MessageFingerprints.kt:34-41`
- displayItems 承载/插桩：`ui/screens/chat/ChatScreen.kt:988-1033`（jkHold `:946-960`）；`ui/screens/chat/MessageDataDelegate.kt:148-149, 313-321`
- 测试：`app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/util/TurnGroupCalculatorTest.kt:69, 87, 100`；`ui/screens/chat/DiffListTest.kt`；`ui/screens/chat/DisplayItemsDiffTest.kt`

## 建议汇总

1. **登记 backlog 卡片**：修复 L595/599/602 三处同款洞（size-key 或 derivedStateOf），附带危害评估（:595 为中危——台账轮次号翻页后错位）。
2. **补 Compose 层 key 语义回归测试**（androidTest，先确认 compose-ui-test 依赖）。
3. 插桩保留；displayItems.size 无需更强 key。
4. 换装「key 陈旧一轮自愈」为可接受隐性契约，建议在 buildChatEntries KDoc 显性化。
