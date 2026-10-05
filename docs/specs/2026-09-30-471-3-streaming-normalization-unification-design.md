# #471③ 流式归一化统一（Normalization Unification）——终帧=流式帧 设计 spec

> 状态：**设计定稿，待实现**（2026-09-30 调研闭环；本文档为唯一权威，供新会话零上下文执行）
> 关联：backlog **#471**（③归一化重排，本批主修）· #437（稳定揭示域/铁律收编素材）· #472（pilotTerminalHold，前置）· #469（表格键治，前置）
> 前置 spec：docs/specs/2026-09-25-437-streaming-md-stable-reveal-design.md（SafePrefixGate）· docs/specs/2026-08-30-streaming-markdown-state-pilot-design.md（pilot）
> journal：docs/journal/2026-09-30-471-3-finalize-normalization-reflow.md

## 0. 一句话

把完结侧才做的 Markdown 归一化**前移到流式 ingest**（gate 之前），让流式显示的文本与完结渲染的文本**逐字节一致**——完结换装（pilot→async）从「文本不同→排版重排→跳变」变为「同文本换渲染器→视觉无事发生」。

## 1. 问题与证据

#471 完结瞬间高度跳变族的最后活动残余③：归一化只在完结发生 = 一次性重排。

| 证据 | 场景 | 量级 |
|---|---|---|
| 2026-09-29 E2E 表格轮 | 完结/换装震荡（t_msg 项 14k px，归一化+staged 重建+换装叠加） | **-8544/+7236** |
| #472 验收茶文化轮 | 纯文本轮完结残余（归一化微差） | ±24px |
| #471④ 实验A（服务端重启 reconcile） | ③族残余定量 | -1330px |
| #472 修复前同场景 | 对照 | -13171/+12824 |

根因链：StreamingMarkdownPilot.kt:66-68「**冲突①裁决**：delta 未经 normalizeForRender——流中放弃归一化，完结时由 preParsedState 分支的既有归一化+分片路径接管」。该裁决来自 #265 时代：流式管线只能追加（库 StreamingMarkdownState.append()，com.mikepenz.markdown.model，0.45.0），而归一化是「看到后面才知道前面要改」的变换（\$\$ 闭合才知道开头要变 tex 围栏、看到 |---| 才知道上一行是表头要补空行）——追加管线表达不了回改。

**解开冲突的钥匙**：单调性只需对**已放行前缀**成立。SafePrefixGate 本来就把危险行（双美元、☐☑✅、|、反斜杠、[] 等活动标记行，SafePrefixGate.kt:380-388）**扣留不上屏**——归一化的回改点恰好全部落在扣留区。把归一化挪到 gate 之前对累积快照做，回改全发生在扣留区 → 已放行前缀永不被改写 → 不变量不破。先例：#437 阶段 C 已用同模式前移了表格空行注入（injectTableBlankLines，SafePrefixGate.kt:415-444）。

## 2. 现状管线全景（代码级锚点）

### 2.1 归一化函数（MarkdownContent.kt）

- normalizeForRender(raw,isUser)（:164-165）= splitOversizedParagraphs ∘ normalizeTaskListMarkers ∘ normalizeMarkdown
- normalizeMarkdown（:116-135）= CRLF→LF（:120）+ ensureBlankLineBeforeGfmTables（:125）+ transformMathFallback（:130）+ 用户专属单换行空行化（:132-134）
- ensureBlankLineBeforeGfmTables（:146-157）：正则（:109），无 | 快路径（:151）
- splitOversizedParagraphs（:221-268）：普通行 run（isPlainParagraphLine :188-199 判定）总字符 ≥3000（:185）且 ≥2 行 → run 内全部行间 \n→\n\n
- 变换器：transformMathFallback（MarkdownMathFallback.kt:34，快路径 :36-38；块级 $$ 与 \\[..\\] → tex 围栏 :127-148；行内 \\(..\\) → 行内代码 :101-113；跨空行不配对 :154-158；幂等）· normalizeTaskListMarkers（NormalizeTaskListMarkers.kt:10，☐☑✅→- [ ]/- [x]，围栏内保留，快路径 :14-17）

### 2.2 归一化的全部消费者（完结侧）

| 消费点 | 位置 | 路径 |
|---|---|---|
| async 终态解析 | MarkdownContent.kt:751-761（parseAsync 内 :755）、:764-773 | asyncParse（完结/静态大文本）|
| 小文本同步解析 | :836-841（:839） | asyncParse ≤2048 |
| libStreaming 流式 fallback | :696-703（:698，**pilot 关闭时的流式路径，本来就在流式侧归一化**）| beta/stable 现状 |
| 预解析（跳转/视口供给）| RenderReadiness.kt:121-123（preParse 内）| 后台 Default |
| 用户消息 | MessageCardUser.kt（import :33）| isUser=true |

### 2.3 pilot 流式路径（本批改动靶区）

- 分支准入：MarkdownContent.kt:602-636（streamingPilotEligible，StreamingMarkdownPilot.kt:122-126：assistant 专属）→ rememberPilotStreamingMarkdownState(markdown, freeze)（:636）
- 包装器：StreamingMarkdownPilot.kt:170-300——prev/released 快照坐标（:172-177），LaunchedEffect(markdown,...)（:186）：前缀差分+首跑铺开（:197-227）/ 非前缀+宽限窗 300ms+风暴抑制（:229-254）/ 增量批放（:255-283）/ heldTail（:286-297）
- gate：SafePrefixGate.kt——空行毕业（:58-70）/ 围栏（:100-113）/ 表格（:115-140）/ 缩进扣留（:143）/ 纯文字直出（:144-150）/ 列表·引用·ATX（:151-185）/ 行内安全帽（:186-196）/ 批预算 400（:447）；lineHasActiveMarker 含双美元（:380-388）；releaseDelta（:408-413）+ injectTableBlankLines（:415-444，**本批退役**）
- 完结桥接：asyncTerminal（MC :612-618）+ pilotTerminalHold（MC :626-627, SMP :138-141）+ HeldTailReveal 降亮区（MC :654-660）
- EOF：无显式 flush 代码——完结 = asyncParse 翻转 → 分支切 async/preParsed 全文渲染（一字不丢）

### 2.4 测试面

NormalizeSentinelEquivalenceTest（哨兵快路径等价，:86-94 含 split 端到端）· SplitOversizedParagraphsTest · SafePrefixGateTest · SafePrefixGateBlockGranularityTest · MarkdownMathFallbackTest · NormalizeTaskListMarkersTest · HeldTailAgingTest/HeldTailCursorTest · InlineSpanSafetyTest · StableTailBoundaryTest

## 3. 设计

### 3.1 函数重构（MarkdownContent.kt）

    // 新：共享核心（assistant/user 通用，全部为放行安全变换）
    internal fun normalizeMarkdownCore(raw: String): String {
        if (raw.indexOf('\r') < 0) { /* CRLF 快路径：整串跳过 replace */ }
        var result = raw.replace("\r\n", "\n").replace("\r", "\n")
        result = ensureBlankLineBeforeGfmTables(result)
        return transformMathFallback(result)
    }

    // 新：pilot ingest 专用（assistant-only，无 isUser 分支）
    internal fun normalizeForStreaming(raw: String): String =
        splitOversizedParagraphsByPosition(normalizeTaskListMarkers(normalizeMarkdownCore(raw)))

    // 改写：完结路径 = 同一核心 + 用户专属变换
    internal fun normalizeForRender(raw: String, isUser: Boolean): String {
        val marked = normalizeTaskListMarkers(normalizeMarkdownCore(raw))
        val withUser = if (isUser) marked.replace(SINGLE_NEWLINE_REGEX, "\n\n") else marked
        return splitOversizedParagraphsByPosition(withUser)
    }

**不变量断言**（进 NormalizeSentinelEquivalenceTest）：normalizeForRender(raw, isUser=false) == normalizeForStreaming(raw) 逐字节。注意用户单换行从 normalizeMarkdown 内部（task 之前）挪到 task 之后——两者皆行锚定操作、可交换（断言混合 fixture 等价）。

### 3.2 位置制空行化（语义重定义，替换 splitOversizedParagraphs）

**规则**：普通行 run 内，行 j 之后的边界升级为空行 ⟺ cumEnd(j) ≥ 3000，其中 cumEnd(j) = run 起点到行 j 末（含其换行）的累计字符。判定**在行 j 完成时即可定案**（不依赖下一行存在——这是前缀单调的关键：若等下一行存在才判，已放行的换行会被回改成空行）。

**单调性证明**：行 j 分类（isPlainParagraphLine 全部 startsWith 判定）与 cumEnd(j) 在行 j 完成时刻即固定 → 边界升级决策单调不翻转 → 已放行前缀永不被改写。行首分类随行生长稳定：startsWith 判定只看行首字符，首字符到达即定型（有序列表「1」→「1. 」的行内演化只影响该行自身的 run 归属尾部，不回改已完成边界）。

**语义变更（诚实记录，测试要改）**：现状「run ≥3000 全部空行化」→ 新「3000 字以内的头部边界保持单换行、越过 3000 的边界才升级」。效果：≤3000+行字符的段落完全不拆（与现状 <3000 一致）；>3000 段落呈现「头部一块 + 尾部逐行」的**稳定接缝**。理由：a) 拆分目的（MarkdownChunking 分片）只需尾部可拆，头部 ≤3000 单块 ≈ CHUNK_MIN 同量级；b) 接缝在流式/完结两侧一致出现 = 不产生跳变（一致性优先于均匀性）。影响所有 flavor 的完结渲染（含 >3000 用户消息段落，罕见）——#437 放行批次 release note 需知会。

### 3.3 逐变换放行单调性矩阵（为什么安全）

| 变换 | 回改点 | 落点 | 单调性依据 |
|---|---|---|---|
| CRLF→LF | \r→\n 坐标收缩 | 全文 | 归一化坐标系内 \r 从不入放行流；截断处 \r→\n 与续达后一致（「a\r」→「a\n」是「a\r\nx」→「a\nx」的前缀）|
| 表格前空行 | 文字行与表头间插换行 | 扣留区边界 | \| 是活动标记 → 表头/分隔/正文行全扣留（gate :115-140）；插入点在已放行前缀之后；先例即 #437 阶段 C |
| 数学降级块级 | 开闭 $$ 行 → tex 围栏 | 扣留区 | 双美元行 gate 已扣（:385）；**中间内容行是纯文字会被放行——但变换不改动内容行本身**（appendBlockMath 只替换定界符行+行首行尾补换行），放行的内容字节不变 |
| 数学降级行内 | \\(..\\) → 反引号代码 | 扣留区 | 反斜杠是活动标记 → 行扣留；变换产物含反引号仍是活动标记 → 毕业前持续扣留 |
| 任务标记 | ☐☑✅ → - [ ]/- [x] | 扣留区 | 三字符皆活动标记（:39 与 :36-38 注释即为此设计）；~~产物含 [] 仍扣留~~ **#471④-a 修订（验收轮）**：归一化前移后原整块扣留理由消失，gate 新增任务项行分支——复选框+空白前缀齐备即定案（`]( `/`][` 续接被构造排除），完整行整行放行、未完行条目文字渐进直出（行内安全帽从复选框后起扫；锚定真实行首，行续段可恢复）；含 $$/\[ 条目行仍扣留。真机 E2E 证伪 v1 行首守卫（未完行先放 "- " 后冻结到空行——4 条一次性吐出的直接机理），v2 收敛 |
| 位置制空行化 | ≥3000 边界升级 | 新达内容 | cumEnd 行完成即定案（§3.2）；升级发生在下一行首字符到达的 delta 里，插入点在已放行前缀之后 |

**兜底**：任何未预见的回改放行区 → 包装器差分发现非前缀 → #472 宽限窗 300ms 冻结 → 超窗 resetKey 整树重建（SMP :229-254）——降级为一次重建闪，正确性不破。

### 3.4 pilot 接线（StreamingMarkdownPilot.kt）

    @Composable
    internal fun rememberPilotStreamingMarkdownState(markdown: String, freeze: Boolean = false): PilotStreamingState {
        // #471③ 归一化前移：终帧=流式帧（spec §3）
        val normalized = remember(markdown) { normalizeForStreaming(markdown) }
        ... // 函数体内 markdown 全部换 normalized（prev/released/held/logGate 坐标皆归一化坐标）

heldTail（降亮区）随之显示归一化文本（- [ ] 预览、tex 围栏行）——WYSIWYG 改善，HeldTailReveal 零改动。KDoc :66-68 冲突①段落重写为新语义。主线程成本：快路径哨兵下 ~7 次 native 扫描（130K 最坏 ≈0.2-0.7ms/批，48ms 预算内）；E2E 验证步骤见 §6。

### 3.5 gate 表格注入退役（SafePrefixGate.kt）

归一化前置后 ensureBlankLineBeforeGfmTables 已在 gate 入口前插入空行 → injectTableBlankLines（:415-444）与 prevLineBeforeIsTextRow（:470-479）恒空转 → **删除**，releaseDelta（:408-413）退化为 snapshot.substring(from, r)。「state.content 与快照长度解耦」语义（:403-404 注释）随注入退役而消失（归一化坐标自然一致）。相关 gate 测试（注入正例）迁移/删除。

### 3.6 三小项裁定（#471 卡附注）

- **CRLF 表格**：随 CRLF→LF 入核心，自动关。
- **tight→loose 列表**：非文本变换（CommonMark 语义：列表内任一空行使全列表转松散，流式到达空行时全列表行距变化 = 数据驱动的正确重排）。文本统一后流式/完结同文本同 AST，理论收敛；E2E 证实后在卡注记，若增量解析器仍分歧则另立观察。
- **setext 升格**：维持 accepted-gap（gate :29-30 既定，模型以 ATX 为主）。可选增强（stage-2，默认不做）：纯文字行放行前窥下一行首字符，为 = 或 - 时扣留至下行完整再整块放行（闭合 setext 块整体定案，无需任何文本变换）。

### 3.7 明确不动

用户消息路径（isUser=true 仅多一个单换行空行化）· RenderReadiness.preParse（调用共享函数自动一致）· asyncTerminal/LRU 缓存（内容键失配自然重解析）· libStreaming fallback（:698 已在流式侧调 normalizeForRender，随位置制自动一致）· HeldTailReveal · InlineSpanSafety · 高度引擎/帽/ledger（#470 裁决独立）。

## 4. 测试计划（TDD）

1. **核心性质测试（新增，本批灵魂）**：NormalizationStreamingMonotonicityTest——fixture 语料（数学块/行内公式/无空行表格/任务标记/>3000 段落/CRLF/围栏/setext/混合）× 逐字符增长模拟：对每个快照前缀，gate 逐批放行得 r，断言 normalize(更长快照).startsWith(normalize(当前快照).take(r)) 且全程非前缀计数 = 0。附 seeded 随机游走。
2. **splitOversizedParagraphsByPosition**：重写 SplitOversizedParagraphsTest——头部 ≤3000 不拆 / 尾部渐进拆 / 围栏·表格·列表不拆 / 行完成即定案（截断增长前缀稳定）/ 幂等。NormalizeSentinelEquivalenceTest:86-94 端到端同步改。
3. **normalizeForRender == normalizeForStreaming**（isUser=false，混合 fixture 逐字节）+ 用户单换行交换序等价 fixture。
4. **gate**：注入正例测试迁移；releaseDelta 退化后坐标一致性。
5. **既有全量回归**：./gradlew :app:testDevDebugUnitTest --rerun 全绿（3700 基线）。

## 5. 实施步骤（新会话按序执行）

1. **commit A（纯函数+TDD）**：normalizeMarkdownCore / normalizeForStreaming / splitOversizedParagraphsByPosition（删旧 splitOversizedParagraphs）+ §4.1-4.3 测试。feat: #471③ 归一化核心前移——流式/完结同源纯函数+位置制空行化(TDD)
2. **commit B（接线+退役）**：pilot ingest normalized（§3.4）+ gate 注入退役（§3.5）+ 受影响 gate/pilot 测试 + KDoc 重写。feat: #471③ pilot ingest 归一化接线+gate 表格注入退役——终帧=流式帧
3. **commit C（E2E+收口）**：真机验证（§6）→ journal 证据 → #471 状态流转 → 铁律素材注记。

每步 compileDevDebugKotlin 通过即 commit；ChatScreen.kt 零触碰（无编辑协议约束）；gradle 禁并发。

## 6. E2E 验证矩阵（真机，dev flavor）

| # | 场景 | 通过判据 |
|---|---|---|
| E1 | 真实模型轮：含 ☐/✅ 任务+$$公式+无空行表格+长段 | 完结帧 RESIZE 序列 Δ≈0（对照修复前同内容 -8544/+7236 族）；完结前后截图内容一致 |
| E2 | 纯文本轮（茶文化类，>2048 字符走 asyncTerminal 换装）| 换装帧无塌缩/弹开（对照 ±24px 残余应→~0）|
| E3 | >3000 单段轮 | 流中与完结行距一致（接缝两侧一致出现）；无完结重排 |
| E4 | 回归：普通流式节奏 | MDPilot gate release 日志节奏正常；无 flap suppress/resetKey 风暴 |
| E5 | 性能 | 流式期帧预算无劣化（logGate 耗时观察，必要时 framestats）|

仪器：logcat MDPilot/RESIZE/RESERVE/A11yDiag path= 探针 + 截图 diff。环境：v2 4096（Basic Auth）或 v1 4199 免费档；./scripts/debug-entry.sh 入口。

## 7. 风险登记

| 风险 | 缓解 |
|---|---|
| 未预见的非前缀（单调性破口）| §3.3 兜底链 + §4.1 性质测试穷举 fixture；出现 = 一次重建闪，非错乱 |
| 主线程归一化成本（数学/表格密集长文）| 哨兵快路径；E5 验证；恶化则挪 background（后续优化卡）|
| 位置制接缝观感（3000 字界）| §3.2 诚实记录；一致性>均匀性；用户复验点 |
| 所有 flavor 完结渲染变化（>3000 段）| release note 知会；#437 放行批一并交代 |
| #483 揭示层依赖 | spec 已核：围栏平衡/表格表态机在归一化文本上语义不变（tex 围栏反而更早平衡）；实现时在 #483 spec 补一行注记 |

## 8. 本批可关卡片清单（用户问询项）

| 卡 | 处置 | 依据 |
|---|---|---|
| **#471 整卡** | **验收后关卡** | ①#422 根灭、②#472 已验收、④裁决 c 转 watch、③=本批；三小项：CRLF 随核心关、tight→loose 文本统一后理论收敛（E2E 证实注记）、setext 维持 accepted-gap（stage-2 设计在 §3.6 备查）|
| #437（不直接关）| 铁律收编素材 +1 域 | 「归一化同源铁律：流式与完结必须走同一归一化函数，禁止任何单侧文本变换」入 sse-scroll-stability-iron-laws.md（R-7 收口五域+本域=六域）|
| #471④ watch（不关）| 残余收窄注记 | retry 重建帧若复发，残余只剩 staged/换装差（-1330px ③族消失）|
| #470（不关）| 无影响 | 帽负向语义是独立裁决；本批减少的是完结侧一次性负差，不减流中有机回缩 |
| #483（不关）| 依赖注记 | §7 风险表；gate 输入从原文变归一化文本，揭示层语义不变 |

## 9. 验收清单（用户）

1. 流式输出含公式/任务复选框/表格的轮次：**流式中就看到最终形态**（tex 围栏块、真复选框），完结瞬间无任何跳变；
2. 长文本轮（>2048）完结换装无塌缩弹开；
3. 超长段落（>3000）流式中与完结后行距一致；
4. 日常流式节奏/滚动无回归。
