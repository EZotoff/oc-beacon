# 主对话流代码块语法高亮——可行性调研（2026-09-30）

> **目标**：评估 ChatScreen 主对话流中 Markdown 围栏代码块（```fenced```）引入语法高亮的可行性，给出明确判定、推荐路线与实施要点。对应 backlog **#488**（markdown 能力补齐批次）的「代码语法高亮」子项——本文即该卡片点名的「纯 Kotlin 方案评估」输入。
> **方法**：一手取证——① 本仓库源码（MarkdownContent.kt / StreamingMarkdownPilot.kt / SafePrefixGate.kt / HighlightBuilder.kt / build.gradle.kts / proguard-rules.pro）；② 本地 Gradle 缓存中已下载的 `multiplatform-markdown-renderer-android-0.45.0.aar` 反编译（javap，核对 MarkdownCodeKt / MarkdownCodeTopBarKt / MarkdownComponentsKt 字节码）；③ Mikepenz 官方仓库 develop/v0.45.0 源码与 README/releases/issues（GitHub API）、Maven Central 元数据直查、SnipMeDev/Highlights 官方仓库源码。未构建、未装机——「预测行为」均标注待真机证实。
> **约束**：纯调研，不改任何代码。
> **关联**：backlog #488；`docs/specs/2026-09-30-streaming-char-reveal-design.md`（排除代码块逐字动画时已预留「语法高亮每字重着色」考量）；`AGENTS.md` SSE 滚动稳定性铁律；`docs/ui-conventions.md` Material 3 First。

---

## 执行摘要（TL;DR）

**判定：有条件可行，且条件比预期宽松得多——推荐路线是引入 Mikepenz 官方同族扩展模块 `com.mikepenz:multiplatform-markdown-renderer-code:0.45.0`。**

三个决定性事实：

1. **版本零升级**。仓库已 pin 的 0.45.0 就是 Maven Central 当前最新版（metadata `latest=0.45.0`；releases 页最新 v0.45.0，2026-08-28）。`-code` 模块同版本号发布，其 POM 依赖 = core 0.45.0 + `dev.snipme:highlights-jvm:1.1.0` + kotlin-stdlib 2.4.10——**与仓库现状逐项相同**，无任何 breaking change、无版本跳跃。
2. **底层高亮库已在 APK 里**。`-code` 模块的语法引擎 `dev.snipme:highlights`（纯 Kotlin KMP）正是 FileViewer 源码视图已在用的库（app/build.gradle.kts:247，同为 1.1.0），R8 keep 规则也已就位（proguard-rules.pro:43）。引入 `-code` 的净增量只有一个 **17.3 KB 的 AAR**（com.mikepenz 同族扩展，非新第三方 UI 库）。
3. **fence 语言标识已被解析，只差消费**。0.45.0 的 `MarkdownCodeFence` 内部经 `MarkdownTokenTypes.FENCE_LANG` 提取语言并一路传到渲染块（见 §2.3），当前仅用于 a11y 描述；`-code` 模块恰好把这个 `language` 参数接到 `SyntaxLanguage.getByName()` 上——接入点是官方设计的正门（`markdownComponents(codeFence = ...)`）。

主要条件（风险与对策见 §4）：① 流式期间高亮作业按「code 字符串变化」整块重启（`produceState(key1=code)` + `awaitDispose` 取消），大代码块在快速流下可能停留纯色直到流暂停/EOF——降级优雅（先纯色后着色），不崩溃不卡主线程；② 高亮色板默认来自 `SyntaxThemes`（Darcula 等 6 预设），需自建 `SyntaxTheme` 映射 M3 令牌 + 处理用户气泡/AMOLED 变体；③ 历史 issue #415（AnnotatedString 反向区间崩溃，已关）提示 span 区间需防御式处理——仓库 FileViewer 的 `HighlightBuilder` 已有现成的防御写法可移植。

---

## 1. 背景与问题

主对话流 assistant/user 消息经 Mikepenz `multiplatform-markdown-renderer` 渲染 Markdown。围栏代码块当前走**库默认渲染器**：纯色背景 + 等宽单色文本，无语法高亮（backlog #488 定罪三项 markdown 能力缺口之一；app/build.gradle.kts:249 注释亦明示「Markdown 代码块使用 mikepenz 内置的默认渲染器，而非本库[highlights]」）。

问题：当前技术体系（Kotlin 2.4.10 / AGP 9.3.2 / Compose BOM 2026.08.00 / mikepenz 0.45.0 / 流式 #265+#437+#487 管线）下能否支持代码块语法高亮？

### 1.1 版本基线（仓库现状）

| 项 | 版本 | 出处 |
|---|---|---|
| Mikepenz markdown renderer | **0.45.0**（core + m3 + coil3 三模块） | app/build.gradle.kts:242-246 |
| 底层解析器 org.jetbrains:markdown | 0.7.9（core POM compile 依赖） | 本地 Gradle 缓存 POM |
| dev.snipme:highlights（FileViewer 用） | **1.1.0** | app/build.gradle.kts:247-249 |
| Kotlin / compose plugin | 2.4.10 | 根 build.gradle.kts:4-6 |
| AGP | 9.3.2 | 根 build.gradle.kts:3 |
| Compose BOM | 2026.08.00（material3 1.4.0 / core 1.12.0） | app/build.gradle.kts:193 |
| kotlinx-coroutines | 1.11.0 | app/build.gradle.kts:240 |
| minSdk / targetSdk / compileSdk | 26 / 36 / 37 | app/build.gradle.kts:20-26 |
| JDK | 21（jvmToolchain） | app/build.gradle.kts:161 |

无 `gradle/libs.versions.toml`（单模块传统依赖声明，settings.gradle.kts 仅 `:app` + `:lint-checks`）。

## 2. 现状盘点（渲染路径 · 代码块 UI · 语言标识去向 · 流式管线）

### 2.1 渲染路径

```
MessageCardAssistant.kt:679 / :951（分片路径）
  → MarkdownContent(...)                     ui/screens/chat/markdown/MarkdownContent.kt:350
      四条渲染分支（MarkdownContent.kt:655/718/774/807）：
      a) preParsedState（分片完结渲染，Markdown(state=…) 重载）
      b) #265 pilot：StreamingMarkdownState 前缀差分 append（流式）
      c) rememberAsyncMarkdownState（>2048 字符完结，后台解析）
      d) rememberMarkdownState(content, retainState=true)（库流式/同步路径）
  → 四条分支共用同一 components/colors/typography 对象
      → Markdown() @ com.mikepenz.markdown.m3
```

`MarkdownContent` 用 `markdownComponents(...)` 覆写了 text/paragraph/heading1-6/table/checkbox（MarkdownContent.kt:524-637），**未覆写 `codeFence`/`codeBlock`** → 代码块走库默认组件。代码块配色经 `DefaultMarkdownColors(codeBackground=…)`（MarkdownContent.kt:423-431）与 `DefaultMarkdownTypography(code=…)`（MarkdownContent.kt:479-483）注入，代码字体为 `CodeTypography`（FontFamily.Monospace / 13sp / lh20，ui/theme/Type.kt:119-125）。

### 2.2 当前代码块 UI（0.45.0 默认，源码级）

反编译本地缓存 AAR（`multiplatform-markdown-renderer-android-0.45.0.aar`）+ 官方 v0.45.0 源码核对，默认 `MarkdownCodeFence → MarkdownCode → MarkdownCodeBackground` 的形态：

- `Box` + `background(codeBackground)` + `RoundedCornerShape(codeBackgroundCornerSize)` + `fillMaxWidth().padding(vertical=8dp)`，内容 `MarkdownBasicText(code, style=typography.code)` + `horizontalScroll(rememberScrollState())`（官方 repo v0.45.0 `MarkdownCode.kt:43-66`；本地 AAR 字节码 `MarkdownCodeKt` 签名一致）。
- **无复制按钮、无语言标签**：`showHeader` 默认 `false`（`MarkdownCode.kt:44`）。0.45.0 已内置 `MarkdownCodeTopBar(language, code)`（语言标签 + 复制按钮 + a11y 标注，本地 AAR `MarkdownCodeTopBarKt` 字节码确认含 ClipboardManager lambda），开启即得——这是高亮之外的免费增量。
- app 侧配色：assistant 卡 `codeBlockBg=surfaceContainer` / AMOLED `surfaceContainerHighest` / user 气泡 `primary`，前景对应 `onSurface`/`onPrimary`（MarkdownContent.kt:396-406）。

### 2.3 语言标识（fence info string）去向：**已解析、传到位、只差消费**

0.45.0 `MarkdownCodeFence` 源码（官方 repo v0.45.0 `MarkdownCode.kt:73-94`，本地 AAR 字节码同证）：

```kotlin
val language = node.findChildOfType(MarkdownTokenTypes.FENCE_LANG)?.getTextInNode(content)?.toString()
// …start/end 偏移截取 code 文本 + replaceIndent()…
block(content.subSequence(start, end).toString().replaceIndent(), language, style)
```

即 ```kotlin 的 `kotlin` 被提取为 `language: String?` 传入渲染块，默认仅用于 a11y `contentDescription("code block with language X")`（`MarkdownCodeBackground`）。**语言标识从未被丢弃**——`-code` 模块正是接管这个参数的官方消费方。流式侧：SafePrefixGate #487 明确「未完开栏行扣留——半行闭栏误判块态+**语言标注未定**」（SafePrefixGate.kt:23-25），开栏行完整即放 → 语言在首行内容放出前已定案，高亮语言选择不会被流式截断抖动。

### 2.4 流式管线约束（高亮必须共存的机制）

- **48ms token 批处理 → SafePrefixGate 行级放行 → StreamingMarkdownState 前缀差分 append**（AGENTS.md 铁律；StreamingMarkdownPilot.kt:50-72）：快照与库状态 StringBuilder 做前缀差分，仅 delta 进 `append()`；解析下沉 `org.jetbrains:markdown 0.7.9` 的 StreamingMarkdownFile，只重解析不稳定尾部，**稳定块 ASTNode 实例跨 append 复用**（StreamingMarkdownPilot.kt:53-56）。
- **#487 开放围栏行级放行**（commit b8916722；SafePrefixGate.kt:20-26、115-149）：开栏行完整即放、块内完整行整行放、未完行按纯文字增量放、闭栏形完整行即放即闭。**流式期间代码内容以「行/行内前缀」粒度进入渲染树**——真机 E2E 已判「代码像正文一样逐行流式输出、高度严格单调」（docs/journal/2026-09-30-487-fence-streaming.md）。
- **后果**：流式中 CODE_FENCE 组件每次 append 都会重组，`code` 字符串（截取子序列）逐批变长。引入高亮后，每次 `code` 变化触发的是**该代码块 Text 的 AnnotatedString 重建 + 重测**（高度不变，见 §4.2），而**不是**整篇 Markdown 重解析（库的 stable/unstable 分裂保证）。
- 完结换装：`pilotTerminalHold`（#472）保持 pilot 终帧直到 async 终态就绪（MarkdownContent.kt:702-719、StreamingMarkdownPilot.kt:151-164）——高亮的「纯色→着色」切换若发生在完结帧附近，会叠加在这条换装路径上（§4.2 风险②）。

### 2.5 UI 依赖禁令的语义边界

`docs/ui-conventions.md:35`（Material 3 First 节）原文：**「禁止引入额外 UI 依赖库（如 Accompanist），除非有充分的理由并经过讨论。」**

- 禁令的目标类是「UI 依赖库」——新引入的 UI 组件/框架层（Accompanist 为例），其关切与同节前几条一致：绕开 M3 原生组件与令牌体系。
- `multiplatform-markdown-renderer-code` 是**已在用的 com.mikepenz markdown renderer 的官方同族扩展模块**（同 groupId、同版本列车、`api(projects.multiplatformMarkdownRenderer)` 编译），渲染原语仍是 Compose/M3（MarkdownBasicText/Box/background）；其语法引擎 `dev.snipme:highlights` 是**已在依赖树里的现有库**（FileViewer 用），且是纯 Kotlin 非绘制库。
- 结论：**不落入禁令字面与意图的目标类**（「额外」不成立——零新第三方实体）。但按禁令附则「除非有充分理由并经过讨论」，落地前应把本调研呈用户裁决（#488 卡片已预留该讨论位）。

### 2.6 重复检查

- backlog：**#488**（backlog.md:94-97，2026-09-30 commit 459515f7 立）「markdown 能力补齐批次：块内HTML隐形+**代码语法高亮**+真数学渲染+setext stage-2」，卡片点名「高亮评估 league/prism 类纯 Kotlin 方案（M3 禁引入额外 UI 依赖库红线内评估）」——**本文即该子项的评估输入，非重复登记**。
- docs/specs / docs/research：grep 高亮/highlight/syntax 无既有代码高亮条目（specs 命中均为流式 reveal「高亮」措辞与 BM25 搜索高亮，docs/specs/2026-09-30-streaming-char-reveal-design.md:74 是唯一相关预留——逐字动画排除代码块的理由之一即「规避语法高亮每字重着色」，与本调研衔接自洽）。
- FileViewer 的 `dev.snipme:highlights` 用法（ui/screens/viewer/HighlightBuilder.kt:9-52）不是重复，是**可复用资产**：行级 AnnotatedString 构建 + 区间防御（§4.4）。

## 3. 一手资料调研（Mikepenz -code 模块 · 底层高亮库 · 兼容矩阵）

### 3.1 模块存在性与坐标（Maven Central 直查）

- `com.mikepenz:multiplatform-markdown-renderer-code`：metadata `latest=0.45.0`，版本序列自 0.27.0 起（高亮支持「introduced with 0.27.0」，README）。
- Android 变体 `multiplatform-markdown-renderer-code-android:0.45.0.aar` 存在（HTTP 200），**下载体积 17,350 字节 ≈ 17 KB**。
- 0.45.0 POM 依赖：`multiplatform-markdown-renderer-android:0.45.0` + `dev.snipme:highlights-jvm:1.1.0` + `kotlin-stdlib:2.4.10`——与仓库现状（§1.1）**逐项一致，零版本差**。

### 3.2 官方接入方式（README + sample 源码）

README（github.com/mikepenz/multiplatform-markdown-renderer）：

```kotlin
Markdown(MARKDOWN, components = markdownComponents(
    codeBlock = highlightedCodeBlock,
    codeFence = highlightedCodeFence,   // 预设默认主题，自动读 fence 语言
))
```

进阶（自定义主题 + 顶栏）：sample/shared/.../MarkDownPage.kt（develop 分支实测拉取）：

```kotlin
val isDarkTheme = isSystemInDarkTheme()
val highlightsBuilder = remember(isDarkTheme) {
    Highlights.Builder().theme(SyntaxThemes.atom(darkMode = isDarkTheme))
}
Markdown(markdownState = …, components = markdownComponents(
    codeBlock = { MarkdownHighlightedCodeBlock(it.content, it.node, highlightsBuilder, showHeader = true) },
    codeFence = { MarkdownHighlightedCodeFence(it.content, it.node, highlightsBuilder, showHeader = true) },
    …))
```

### 3.3 -code 模块源码级机制（v0.45.0 tag，GitHub API 拉取全文）

`MarkdownHighlightedCode.kt`（multiplatform-markdown-renderer-code/src/commonMain/kotlin/com/mikepenz/markdown/compose/elements/）：

- **接线**：`MarkdownHighlightedCodeFence` 包装核心 `MarkdownCodeFence(content, node, style) { code, language, style -> MarkdownHighlightedCode(…) }`——复用 §2.3 的语言提取与代码截取，壳完全同默认组件（同样的 `MarkdownCodeBackground` + `horizontalScroll`），仅把纯文本换成着色 AnnotatedString。
- **异步高亮**：`produceHighlightsState(code, language, builder, immediate)`：
  - 默认路径 `produceState(initialValue = AnnotatedString(code), key1 = code)` + `launch(Dispatchers.Default)` + `awaitDispose { job.cancel() }`——**高亮全程 Default 线程**，初值 = 未着色纯文本（不空窗）；
  - `immediate`（默认 `LocalInspectionMode.current`）走 `remember(code)` 同步路径（PR #500「immediate for code highlighting, test」，v0.39.2，为测试/预览同步着色）。
- **语言解析**：`SyntaxLanguage.getByName(language)`，未知名/无语言 → null → 不设 language（`SyntaxLanguage.DEFAULT`，仅基础着色/不着色）。
- **输出形态**：`buildHighlightedAnnotatedString` = `append(code)` + 每高亮项 `addStyle(SpanStyle(color=Color(rgb)) / SpanStyle(fontWeight=Bold), start, end)`——**纯颜色/字重 span，不改段落结构与字符内容**（布局中性论证见 §4.2）。
- 默认主题：`rememberHighlightsBuilder()` = `remember(isSystemInDarkTheme()) { Highlights.Builder().theme(SyntaxThemes.default(darkMode)) }`（Darcula）。

### 3.4 底层高亮库 dev.snipme:highlights（SnipMeDev/Highlights）

- **性质**：Kotlin Multiplatform 语法高亮引擎，纯 Kotlin 无平台代码（README：「Written in pure Kotlin, so available for many platforms」），Apache-2.0，195 stars，**最近 push 2026-08-13（活跃维护中，非 archived）**；Maven Central 最新即 1.1.0（= 仓库已用版本，无升级诱因）。
- **支持语言**（SyntaxLanguage 枚举）：C / C++ / C# / CoffeeScript / Dart / Go / Java / JavaScript / Kotlin / Perl / PHP / Python / Ruby / Rust / Shell / Swift / TypeScript（+DEFAULT）。**不含** HTML/XML/JSON/YAML/SQL/Markdown——fence 标这些语言时静默回落 DEFAULT（`getByName` 返回 null）。仓库 HighlightBuilder.kt:54-76 的扩展名映射与该集合一一对应（现成对照表）。
- **主题定制**：`SyntaxTheme` 是公开 data class，仅 9 个 ARGB Int 角色（code/keyword/string/literal/comment/metadata/multilineComment/punctuation/mark）+ `simple()/basic()` 工厂（SyntaxTheme.kt）——**映射宿主 Material3 令牌零障碍**：`SyntaxTheme(key, code = onSurface.toArgb(), keyword = primary.toArgb(), …)` 自建即可；预设 6 套（darcula[默认]/monokai/notepad/matrix/pastel/atomone）。README 无性能/体积专项声明（KMP 纯 Kotlin，无原生负载）。
- **仓库现状**：FileViewer 已用 `Highlights.Builder().code().language().theme(SyntaxThemes.default(isDark)).build()` + 手写 span 构建（HighlightBuilder.kt:20-52）——同一 API 面。

### 3.5 版本兼容矩阵

| 项 | -code 模块 0.45.0 要求 | 仓库现状 | 差距 |
|---|---|---|---|
| mikepenz core | 0.45.0 | 0.45.0（build.gradle.kts:243） | **无** |
| dev.snipme:highlights | 1.1.0 | 1.1.0（build.gradle.kts:247） | **无** |
| kotlin-stdlib | 2.4.10 | Kotlin 2.4.10 | **无** |
| Compose | JetBrains Compose 编译（core 同源） | BOM 2026.08.00 + core 0.45.0 已共存 | **无**（-code 仅用 runtime/ui/foundation，`compileOnly` 声明，与 core 同列车） |
| R8 | — | `com.mikepenz.markdown.**`/`dev.snipme.highlights.**` keep 已在（proguard-rules.pro:39,43） | **无** |

**升级路径：不存在**——0.45.0 即最新（releases 页最新 v0.45.0 2026-08-28；v0.44.0 起无 breaking change 记录，MIGRATION.md 0.45.0 节仅 bug fix）。跨版本 breaking change 不适用；若未来升 0.46+，关注 MIGRATION.md（历史上 0.40.x 曾改 inline image 语义、0.34 曾改 API 面）。

### 3.6 流式性能已知问题（官方 issue/PR 全查）

- **#315**（closed）「incremental parsing and render markdown with streams」——LLM token 流渲染性能的根 issue，「poor streaming performance」主诉。修复链：**PR #501**（v0.39.2）`LaunchedEffect(input)` → `snapshotFlow{input}.conflate()` 防 parse thrashing；**v0.42.0 引入 `StreamingMarkdownState`**（增量解析）——即本仓库 pilot 已用的机制。**该性能问题的修复在主解析层，仓库已受益**。
- **#415**（closed）「UI crash on specific code highlighting」——`AnnotatedString$Range`「Reversed range is not supported」崩溃于 `MarkdownHighlightedCodeKt`（桌面 AWT 栈）。已关闭；现版源码 `addStyle(start=location.start, end=location.end)` 无显式防御，而仓库 FileViewer 的同型代码有（`if (start >= maxIndex || end <= start) return@forEach`，HighlightBuilder.kt:39-40）——**采纳官方组件时应保留防御或自建组件移植该防御**（§5）。
- **#477**（closed）：codeFence 背景色自定义诉求——现由 `LocalMarkdownColors.codeBackground` 满足（仓库已用，§2.2）。
- `-code` 模块自身的 `produceHighlightsState` **无 conflate**：`key1=code` 变更即取消重启整块高亮（§4.2 风险①）——无专项 issue，属设计余量而非常发事故（awaitDispose 取消保证不叠加、Default 线程保证不卡主线程）。

### 3.7 替代路线简评（备而不用）

| 路线 | 评估 | 结论 |
|---|---|---|
| **A. Mikepenz `-code` 模块**（推荐） | 同族官方扩展、底层库已在 APK、异步高亮现成、fence 语言自动接线、17KB | ✅ 首选 |
| B. Prism4j（Java）+ 手写 span 映射 | 语法文件全但 Java 系（非纯 Kotlin）、需自建 commonmark AST→Prism token 桥、新增第三方依赖+R8 规则、维护停滞 | ❌ 违 #488「纯 Kotlin 方案」偏好且成本高 |
| C. 自写轻量关键词高亮器 | 零依赖、M3 令牌直达；但字符串/注释/上下文感知的正误率高，重造轮子，长期维护负担 | ❌ 仅当 A 被否决时的兜底 |
| D. WebView + highlight.js | 与 Compose 管线割裂、高度引擎/流式配对全部失效 | ❌ 直接排除（违反滚动铁律） |

注：#488 卡片写的「league/prism 类」——`dev.snipme:highlights` 本身就是卡片要找的「纯 Kotlin 方案」，且已在依赖树中，比另寻 league/prism 更优。

## 4. 约束与风险评估

### 4.1 UI 依赖禁令（§2.5）——低风险

不落入禁令目标类（零新第三方实体）；需用户裁决留档（#488 讨论位）。着色方案自建 `SyntaxTheme` 映射 M3 令牌，不破坏 Theme Token System（ui-conventions.md「颜色用 colorScheme 语义色」）。

### 4.2 流式滚动铁律交互——中风险，两条

**① 流式期高亮重启节律**。#487 行级放行下，每次 gate 放行 → `code` 变化 → `produceState` 重启（旧作业 `awaitDispose` 取消，新作业 Default 线程**从头重高亮整块**）。后果推演（待真机证实）：
- 小/中块（常态，≤百行）：单次高亮毫秒级，48ms 批间即完成 → 流式中即可见着色；
- 大块（数百行）快速流入：高亮耗时 > 批间隔 → 作业反复被取消，**停留纯色直到流暂停/EOF 才着色**——降级优雅（初值就是纯文本 AnnotatedString，无空窗/无闪烁/无崩溃），但 CPU 在 Default 线程做 O(N²) 级重复功（每行追加重高亮全块）；
- 高度引擎（#435 配对）：着色 span **不改字符内容与字号/字体**（`ColorTypography` 不动），等宽字体下 Bold 字重同宽（JetBrains Mono 等等宽家族 advance 统一）→ 重测高度不变 → `MDResize d=0`，不干扰 StreamingGrowLedger 配对。残余风险：exotic 等宽字体 Bold 变宽则单块重排一次——真机首验项。
- 该风险即 char-reveal spec（2026-09-30 §74）预留的「每字重着色」顾虑的行级版——#487 后粒度是行批不是字，重启频率受 48ms flush 天然节流。

**② 完结换装叠加**。#472 无缝换装依赖「同文本换渲染器视觉无事发生」；高亮引入后 pilot 终帧（可能纯色）→ 完结渲染（着色）存在一次**颜色 pop**。规避：完结路径与流式路径用同一 `highlightsBuilder`/同一组件覆写（两路径共用 `components` 对象，天然同源），并接受「EOF 后一次着色」或对完结帧延迟着色（体验决策，留给实施）。

### 4.3 主题令牌映射——中工作量

`-code` 默认 `rememberHighlightsBuilder` 只键于 `isSystemInDarkTheme()`，不感知宿主动态色/AMOLED/用户气泡。要点：
- 自建 `SyntaxTheme(code=onSurface, keyword=primary, string=tertiary, literal=secondary, comment=outline, …)`，`remember(colorScheme, isAmoled)` 键控；加入 `MarkdownContent` 的 components remember 键列表（现键 `density, isUser, linkListener, linkColor, textColor`，MarkdownContent.kt:524——主题切换不重建会残留旧色，同文件 522-523 行已有先例注释）。
- **用户消息（isUser）建议豁免**：user 气泡代码背景是 `primary`、前景 `onPrimary`（MarkdownContent.kt:397-405），预设高亮色板在其上不可读；只对 assistant 开高亮（与 pilot 准入 `streamingPilotEligible` 排除 isUser 同构，StreamingMarkdownPilot.kt:145-149）。

### 4.4 span 区间防御——低成本必做

#415 崩溃类（Reversed range）：官方 `addStyle(start, end)` 无防御；移植 FileViewer 的防御三行（HighlightBuilder.kt:39-40 的 `coerceIn`/`return@forEach`）或直接自建组件包装。

### 4.5 R8 / 体积——无风险

keep 规则已覆盖两组包名（proguard-rules.pro:38-43）；净增量 = 17KB AAR 的 handful of composables（底层 highlights 已因 FileViewer 在 APK，无二次计入）。Release R8 构建预期零改动通过（待常规验证）。

### 4.6 语言覆盖缺口——已知边界

fence 标 `json`/`yaml`/`xml`/`sql`/`html` 等不支持语言 → 静默纯色（现行为）。可接受（不劣于现状）；若要覆盖需换/加引擎（越出本调研范围）。

## 5. 可行性结论

**有条件可行。** 技术体系无任何阻塞：版本零升级（0.45.0 即最新且 -code 同版本发布）、依赖零新增实体（highlights 1.1.0 已在 APK）、接入点官方正门（`markdownComponents(codeFence/codeBlock=…)`，fence 语言自动提取）、R8 与体积无感、流式管线的重解析层不受影响（stable/unstable 分裂 + 行级放行粒度）。

**推荐路线 A：引入 `com.mikepenz:multiplatform-markdown-renderer-code:0.45.0`，在 `MarkdownContent` 覆写 `codeFence`（可一并 `codeBlock`）为高亮组件**，条件：
1. 仅 assistant 消息启用（user 气泡豁免，§4.3）；
2. 自建 M3 令牌映射的 `SyntaxTheme`（不直接用 Darcula 预设），键控动态色/AMOLED；
3. 组件内保留 span 区间防御（§4.4）；
4. 落地前经用户裁决（UI 依赖禁令附则 + #488 讨论位）；
5. 真机首验清单：流式大代码块着色时机与 CPU、完结换装颜色 pop 观感、Bold 字重对等宽 advance 的影响（§4.2）。

## 6. 实施要点草案（给未来实施者，不展开成 spec）

1. **依赖**：`app/build.gradle.kts` markdown 块加一行 `implementation("com.mikepenz:multiplatform-markdown-renderer-code:$markdownRendererVersion")`（同步改 build.gradle.kts:249 的注释——它现在明确写着「Markdown 代码块…而非本库」）。
2. **组件接线**（`MarkdownContent.kt:524` 的 `markdownComponents(...)`）：
   ```kotlin
   codeFence = { model ->
       MarkdownHighlightedCodeFence(
           content = model.content, node = model.node,
           highlightsBuilder = codeHighlightsBuilder,   // 自建，见 3
           showHeader = <可选：语言标签+复制按钮，0.45.0 内置>,
       )
   },
   codeBlock = { …同构 MarkdownHighlightedCodeBlock… },
   ```
   `components` 的 remember 键追加高亮主题依赖（颜色角色），否则主题切换残留旧色（同 522-523 行先例）。
3. **主题**：`remember(colorScheme 角色, isAmoled) { Highlights.Builder().theme(customSyntaxTheme) }`；`SyntaxTheme` 9 角色从 colorScheme 取 ARGB（`toArgb()`）。user 消息传「不着色」路径（如保持默认 codeFence）。
4. **防御**：如直接用官方组件，评估包一层或 fork `buildHighlightedAnnotatedString` 加区间守卫（对照 HighlightBuilder.kt:37-41）；顺带可把 `end+1` 语义（FileViewer 版）与官方 `end` 语义的差异核对一次。
5. **流式观察项**（journal 记录用）：MDPilot append 节律不变（高亮不进 gate）；Default 线程高亮作业频率（可临时 AppLogger 打点）；大块（>200 行）流式期是否纯色到 EOF；完结帧颜色 pop；HFLICK/MDResize 零异常。
6. **回归面**：`docs/regression-guide.md` 的 markdown 渲染域能力；Paparazzi 快照（官方 sample 有 highlightedcode 快照先例）可选。
7. **验证**：按 `docs/verification.md` V1-V6；真机入口 `./scripts/debug-entry.sh`；发「Write a Kotlin file about 50 lines」复用 #487 E2E 场景对照高度单调性。

---

### 引用清单（外部一手资料）

- Mikepenz repo README（模块清单/接入示例/highlights 依赖）：https://github.com/mikepenz/multiplatform-markdown-renderer
- Releases（v0.45.0 最新、v0.42.0 StreamingMarkdownState、v0.39.2 PR#500/#501）：https://github.com/mikepenz/multiplatform-markdown-renderer/releases
- -code 模块源码（v0.45.0 tag 与 develop 等同核对）：`multiplatform-markdown-renderer-code/src/commonMain/kotlin/com/mikepenz/markdown/compose/elements/MarkdownHighlightedCode.kt`
- core `MarkdownCode.kt`（v0.45.0，FENCE_LANG 提取/MarkdownCodeBackground/showHeader）：同 repo `multiplatform-markdown-renderer/src/commonMain/kotlin/com/mikepenz/markdown/compose/elements/MarkdownCode.kt`
- Maven Central：`com/mikepenz/multiplatform-markdown-renderer-code/maven-metadata.xml`（latest=0.45.0）；`…-code-android/0.45.0`（AAR 17,350B + POM 依赖）
- issue #315 / #415 / #477，PR #500 / #501：https://github.com/mikepenz/multiplatform-markdown-renderer/issues/315 等
- SnipMeDev/Highlights（KMP/Apache-2.0/语言表/SyntaxTheme 模型）：https://github.com/SnipMeDev/Highlights
- sample 接线：`sample/shared/src/commonMain/kotlin/com/mikepenz/markdown/sample/MarkDownPage.kt`
