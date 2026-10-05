# 主对话流 Markdown 代码块语法高亮——实施影响面分析（2026-09-30）

> **目标**：把「主对话流围栏代码块语法高亮」从可行性结论（上轮调研）细化为可开工的实施地图——改动文件全集（file:line 级）、波及面盘点、批次划分、风险矩阵、工作量汇总。对应 backlog **#488**「代码语法高亮」子项的实施前置。
> **方法**：一手取证——① 本仓库源码通读（MarkdownContent.kt 全文 / StreamingMarkdownPilot.kt / SafePrefixGate.kt / HighlightBuilder.kt / Theme 族 / Settings 族 / 测试树 / proguard / lint-baseline / backlog / #487 journal）；② Mikepenz `-code` 模块 v0.45.0 tag 源码直拉（GitHub raw）核对 API 可见性；③ core 0.45.0 本地 Gradle 缓存 AAR 反编译（javap：MarkdownCodeKt / MarkdownComponentsKt / MarkdownA11yLabels / ComposeLocalKt）；④ dev.snipme:highlights 1.1.0 本地 jar 反编译（SyntaxTheme 构造器）+ 官方 README/issue/test 语义核对（PhraseLocation end 语义）。未构建、未装机——预测行为标注待真机证实。
> **约束**：纯调研，不改代码、不写 backlog、不构建。
> **关联**：[`2026-09-30-code-syntax-highlighting-feasibility.md`](2026-09-30-code-syntax-highlighting-feasibility.md)（可行性，结论：有条件可行）、backlog **#488**（backlog.md:89-95）、`docs/journal/2026-09-30-487-fence-streaming.md`（E2E 判据来源）、`AGENTS.md` SSE 滚动铁律。

---

## 执行摘要（TL;DR）

1. **上轮推荐的「引入 -code 依赖」被本轮可见性证据修正为「零新依赖 + 仓库内自建组件」**。`-code` v0.45.0 的三个内部函数 `buildHighlightedAnnotatedString` / `produceHighlightsState` / `rememberHighlightsBuilder` 全部 **private**（GitHub v0.45.0 tag 源码直证）——而本需求的三处定制（#415 类 span 区间防御、M3 令牌主题、AppLogger 打点）全部落在 private 区，包装 public 入口无法注入任何一处。fork 不可避免；而 fork 所需的全部上游 API 在**已依赖的 core**（`MarkdownCodeFence`/`MarkdownCodeBlock`/`MarkdownCodeBackground` + `LocalMarkdownDimens`/`LocalMarkdownPadding` 均 public，AAR javap 确认）+ **已在依赖树的 highlights**（build.gradle.kts:250）→ `-code` 的边际价值归零，不加它连 UI 依赖禁令的用户裁决面都消失（零新依赖实体）。fork 体量：**单文件 ~120 行**。
2. **user 气泡豁免是伪问题**：用户消息根本不进 `MarkdownContent`——PartContent.kt:144-154 用户消息走纯 `Text`（2026-08-15 裁决「所见即所得」），全部 8 个调用点要么显式 `isUser=false` 要么分支保证 false。上轮调研的「user 气泡预设色板不可读」担忧在当前代码结构下**自动满足，零成本**。
3. **`PhraseLocation.end` 是 exclusive**（官方 README `PhraseLocation(13, 25)` 标注 "ExampleClass" 即索引 13..24 + NumericLiteralLocator 测试 `PhraseLocation(0,5)` 对应 "1e+10"）——官方 -code 的 `addStyle(start, end)` 语义正确；**FileViewer HighlightBuilder.kt:39 的 `end + 1` 是多染一字符的偏差写法**（顺带发现的存量问题，另行登记候选）。且上游 issue #75（MultilineCommentLocator 产出 start>end 反向区间）证明**反向区间在野外真实存在**——防御三行必做（对照 HighlightBuilder 骨架、按 exclusive 语义修正）。
4. **showHeader 的 i18n 缺口比上轮预期小**：0.45.0 TopBar 可见文案只有 `language.uppercase()`（语言名，普适）+ "⧉" 符号，无硬编码英文；缺口仅在 a11y 朗读标签（`LocalMarkdownA11yLabels`，public CompositionLocal，app 可覆写本地化；当前 app 未覆写，整库 a11y 默认英文）。首期仍建议不开（纯着色零文案零 i18n），批 3 开启时同批补 a11y 本地化。
5. **总评：小切口多波及**。必改 2 文件 + 新增 1 文件 + 测试 2 文件；但波及 8 个调用面（assistant 双路径/Reasoning 流式/工具卡×2/通知卡/压缩卡/预览对话框）与流式、滚动、主题三套机制的回归验证。

---

## 1. 改动文件全集（Part 1）

### 1.0 前置决策：自建组件（fork 内核），不加 `-code` 依赖

**可见性矩阵**（`-code` v0.45.0 源码 + core 0.45.0 AAR javap）：

| 符号 | 所在模块 | 可见性 | 对本需求的意义 |
|---|---|---|---|
| `MarkdownHighlightedCodeFence/Block/Code` | -code | public | 官方接入正门，但内部不可定制 |
| `buildHighlightedAnnotatedString`（含 addStyle 无守卫） | -code | **private** | 防御 + 打点落点 —— 不可注入 |
| `produceHighlightsState`（produceState key1=code） | -code | **private** | 流式重启节律 —— 不可调参 |
| `rememberHighlightsBuilder`（键于 isSystemInDarkTheme） | -code | **private** | M3 令牌主题落点 —— 不可注入 |
| `MarkdownCodeFence(content,node,style){code,language,style->…}` | core | **public** | fork 的骨架入口（语言提取/代码截取/replaceIndent 全在内部，复用） |
| `MarkdownCodeBlock` / `MarkdownCodeBackground` | core | **public** | 同上（视觉壳完全同库默认） |
| `LocalMarkdownDimens` / `LocalMarkdownPadding` / `LocalMarkdownColors` | core | **public** | 复刻默认壳的圆角/内边距/背景（与现有 codeBackground 注入一致） |
| `SyntaxTheme(key, code, keyword, …, mark)` 9×Int | highlights | public（本地 jar javap：`SyntaxTheme(String,int×9)`） | M3 令牌映射的载体 |
| `MarkdownCodeTopBar` | core | internal | 顶栏不可直接复用；showHeader 走库内路径或自绘 |
| `LocalMarkdownA11yLabels` + `MarkdownA11yLabels` | core | public（data class 7 字段） | showHeader 的 a11y 本地化通道 |

**推论**：定制点全在 -code 的 private 区 → 要么「用官方组件 + 接受无防御/无主题/无打点」（ unacceptable：#415 崩溃类 + Darcula 预设违反 Theme Token System），要么 fork。fork 后 `-code` 唯一剩余价值是省 ~40 行 public 壳——不值 17KB AAR + 一条依赖。**结论：不引入 -code，在 `ui/screens/chat/markdown/` 自建组件文件，调 core public API + highlights**。这同时让 UI 依赖禁令（ui-conventions.md:35）的「除非有充分理由并经过讨论」裁决面完全消失（零新依赖），比上轮调研的合规路径更干净。

与上轮调研的关系：上轮 §6.4 已预留「fork `buildHighlightedAnnotatedString` 加区间守卫」选项；本轮把该选项升级为**默认路线**（可见性证据使其从「或」变「必」）。

### 1.1 必改文件

#### a) `app/build.gradle.kts`（1 处，纯注释）

| 位置 | 改动 | 为什么 |
|---|---|---|
| :248-249 | 更新注释：「FileViewer 源码视图的语法高亮（dev.snipme/highlights）」+ 删除/改写「注意：Markdown 代码块使用 mikepenz 内置的默认渲染器，而非本库」 | 该注释（backlog #488 也引用它作为「无高亮」定罪证据）在实施后变为不实陈述。**不加任何依赖行**——highlights 已在（:250），自建路线零依赖变化 |

#### b) `app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/markdown/MarkdownContent.kt`（3 处，~35-45 行）

现状锚点（本轮通读全文确认）：

- **颜色分叉**（:391-416）：`inlineCodeFg`/`codeBlockBg`/`codeBlockFg`/`inlineCodeBg`/`linkColor` 已按 `isAmoled`/`isUser` 三叉——但见 §1.2：isUser=true 分支是潜伏防御代码（永不触达）。
- **components 对象**（:524-638）：`remember(density, isUser, linkListener, linkColor, textColor)` 键**已含 isUser 与两个颜色角色**；覆写了 text/paragraph/heading1-6/table/checkbox（:526-637），**未覆写 codeFence/codeBlock**（走库默认）。
- **:521-523 既有先例注释**：「components 闭包捕获 linkColor/typography/textColor。键必须包含它们：主题切换时颜色变化 → 重建闭包…否则切换主题后文字颜色停留在旧主题」——高亮 builder 必须沿用同一纪律。
- **四条渲染分支共用 components**：① preParsedState 分片/全量（:655-689，`Markdown(state=…)` at :665/:677）；② #265 pilot 流式（:718-758，`Markdown(streamingMarkdownState=…)` at :733）；③ 非流式长文本 async/sync（:702-709/:774-793）；④ 库同步路径（:789-792）。末态 `Markdown(markdownState=…)` at :807-816。

改动点位：

| 位置 | 改动内容 | 为什么 |
|---|---|---|
| :524 前（新增 ~10 行） | `val codeHighlightTheme = rememberCodeSyntaxTheme(isAmoled)`（或等价：从 `MaterialTheme.colorScheme` 取 9 角色建 `SyntaxTheme`，键控相关颜色角色）+ `val codeHighlightsBuilder = remember(theme…) { Highlights.Builder().theme(…) }` | 主题单源构建；remember 键 = 参与映射的 colorScheme 角色（主题/动态色/AMOLED 切换 → 新实例） |
| :524 | remember 键追加 `codeHighlightsBuilder`（或其派生色） | :521-523 先例同款——否则主题切换后 codeFence 闭包仍捕获旧 builder，代码块残留旧主题色 |
| :637 前追加两个覆写（~8 行） | `codeFence = { model -> SafeHighlightedCodeFence(model.content, model.node, model.typography.code, builder, …) }`、`codeBlock = { …同构… }` | 官方正门接入位；`model.typography.code` 即 :479-483 注入的 CodeTypography.copy（色/字号/行高全沿用现有令牌） |

**四条渲染分支逐条推演**（高亮后行为）：

| 分支 | code 输入特征 | 高亮行为 | 备注 |
|---|---|---|---|
| ① preParsed（完结分片 :655/:677、巨型段 :951） | 静态（AST+content 定案） | produceState(code) 单次 Default 线程高亮 → 一次重组换 AnnotatedString | 完结态主场景；初值纯文本不空窗 |
| ② pilot 流式（:733） | code 逐批（行级放行，#487）变长 | 每 `code` 变化 → produceState(key1=code) 重启（旧作业 awaitDispose 取消）；小中块 48ms 批间完成即见着色；大块快速流反复取消 → 纯色到流暂停/EOF | 流式主场景；§4 风险①② |
| ③ async/sync（完结 >2048/:774-793） | 静态 | 同① | — |
| ④ 库同步（:789，≤2048 完结与短消息） | 静态（remember(content)） | 同① | #487 E2E 里 len=260→1207 即此路径 |

#### c) 新文件 `ui/screens/chat/markdown/CodeSyntaxTheme.kt`（建议路径与理由）

**主题令牌组织现状**（ui/theme/ 目录通读）：`Theme.kt`（OpenCodeTheme :117，dynamicColor/amoledDark 参数；AMOLED 只覆盖 surface 族 :96-104/:127-141，accent 角色不变）、`Color.kt`（status/diff/agent 等**非 token 静色先例**——无 M3 语义对应的颜色直接 `val Xxx = Color(0x…)`）、`Alpha.kt`（AlphaTokens :18-24）、`Type.kt`（CodeTypography :119-125，FontFamily.Monospace/13sp/lh20）。

**建议**：新建 `CodeSyntaxTheme.kt`（而非塞进 Theme.kt）——① 映射函数签名是 `ColorScheme → SyntaxTheme`（dev.snipme 类型），放进 ui/theme 会引入 chat 渲染依赖方向问题；② 放 `ui/screens/chat/markdown/` 与 SafePrefixGate/StreamingMarkdownPilot 等域文件同列（该目录 21 个文件全是 markdown 域逻辑+测试一一对应）。`SyntaxTheme` 9 角色到 colorScheme 语义色映射提案：

| SyntaxTheme 角色 | M3 映射提案 | 依据 / 备注 |
|---|---|---|
| `code`（默认前景） | `onSurface` | = 现有 codeBlockFg else 分支（MarkdownContent.kt:405）；AMOLED 分支同为 onSurface（:403）→ 映射统一 |
| `keyword`（val/fun/class/if） | `primary` | 最强强调位（与链接同色系，视觉可接受） |
| `string` | `tertiary` | Material You 第三色调 |
| `literal`（数字/true/false） | `secondary` | 第二色调 |
| `comment` | `onSurfaceVariant` | 灰阶降权——**注意** `ColorHighlight→SpanStyle(color=Color(it.rgb).copy(alpha=1f))` 强制 alpha=1，不能靠透明度降权，只能换色相近的令牌 |
| `multilineComment` | `onSurfaceVariant` | 同 comment |
| `metadata`（注解 @X） | `tertiary`（初值，批 2 视觉试验） | **无显然 M3 对应**——自定义候选 |
| `punctuation` | `onSurfaceVariant` | 标点降权 |
| `mark`（高亮标记） | `tertiary` | **无显然 M3 对应**——自定义候选 |

性质：纯函数（ColorScheme in → SyntaxTheme out），**可 JVM 单测**；AMOLED 免特判（accent 角色不被 AMOLED 覆盖，Theme.kt:96-141）；动态色自动适配（primary/secondary/tertiary 来自 dynamic scheme，三色调区分度由系统保证）。

#### d) 新文件 `ui/screens/chat/markdown/HighlightedCode.kt`（fork -code 内核 + 防御）

内容 = `-code` v0.45.0 `MarkdownHighlightedCode.kt` 的仓库化改写（上游全文已拉取核对）：

```
SafeHighlightedCodeFence / SafeHighlightedCodeBlock   (~20 行，调 core public MarkdownCodeFence/Block)
SafeHighlightedCode（composable 主体）                (~35 行：LocalMarkdownColors/Dimens/Padding 壳，
                                                       produceState + Dispatchers.Default + awaitDispose）
safeBuildHighlightedAnnotatedString（防御版）          (~35 行：Highlights.Builder…build().getHighlights()
                                                       + 区间守卫 + addStyle）
（可选）高亮作业 AppLogger 打点                        (~5 行，对照 StreamingMarkdownPilot.kt:370-393 先例)
```

**防御写法（对照与修正）**——FileViewer `HighlightBuilder.kt:37-41` vs 本 fork 应有形态：

```kotlin
// FileViewer 现状（viewer 域，勿照抄）：end+1 是多染一字符的偏差（见下）
val end = (h.location.end + 1).coerceAtMost(maxIndex)
if (start >= maxIndex || end <= start) return@forEach
// 本 fork（exclusive 语义 + 反向/越界守卫）：
val end = h.location.end.coerceAtMost(maxIndex)   // PhraseLocation.end 已是 exclusive（§1.0 证据）
if (start >= maxIndex || end <= start) return@forEach
```

**`end` 语义判定证据**（上轮遗留核对项，本轮闭合）：① 官方 README `emphasis(PhraseLocation(13, 25)) // ExampleClass`——"public class " 占 0..12，"ExampleClass" 索引 13..24，end=25 ⇒ exclusive；② NumericLiteralLocator 测试 `PhraseLocation(0, 5)` 对应 "1e+10"（5 字符）⇒ exclusive。故官方 -code 的 `addStyle(start, end)` 语义正确，FileViewer 的 `+1` 多染一字符（存量视觉小瑕，**建议另行登记 backlog 卡片**，本调研只记录不改）。**反向区间真实存在**：SnipMeDev/Highlights issue #75（MultilineCommentLocator 对 `*/path/*` 产出 start>end）——`end <= start` 守卫必做，这是 #415 崩溃类的上游根源。

**「跨 screen 复用 HighlightBuilder.kt vs 聊天域自建」评估**：不建议复用。① 同层跨域依赖：viewer → chat（或反向）的 UI 层横向引用违反 Clean Architecture 分层直觉（两域并列于 ui/screens/ 下，无共享上游）；② 语义已分叉：`rememberLanguage(filePath)`（扩展名映射）是 viewer 特有，且 end+1 偏差需修正——改它波及 FileViewer 回归（FileType/FileViewerViewModel 测试树），不改它聊天域带病；③ 演进方向不同：聊天域要 produceState 异步 + 主题键控 + 打点，viewer 域是同步行级构建。**架构代价结论：聊天域自建 ~35 行构建函数，两域各自演进；未来若第三处需要，再提取到 ui/theme 或独立 ui/common（届时三域共识的形状才清晰）**。

**版本漂移防线（若 0.45.0 后续升级）**：文件头注释标注 fork 基线（`forked from -code v0.45.0 MarkdownHighlightedCode.kt @ tag v0.45.0`），升级 core 时 diff 官方同文件。

#### e) showHeader 选项（语言标签 + 复制按钮）——批 3 决策

0.45.0 `MarkdownCodeTopBar`（core，internal）源码事实：可见文案 = `language?.uppercase()`（语言名普适）+ 复制按钮符号 "⧉"；**无 "Copy"/"Copied" 硬编码英文字符串、无复制后反馈**；a11y 标签（copyCode/codeLanguage/codeFallbackLanguage/codeBlock 等 7 字段）经 `LocalMarkdownA11yLabels` 注入，app 未覆写时为库默认英文。

- **i18n 缺口重估**：可见层零缺口；a11y 层 = 7 字段 × 15 语言（若做 TalkBack 本地化）。比上轮「showHeader = i18n 缺口」的保守预设乐观。
- **建议**：首期（批 1）不开 showHeader——纯着色零文案零 a11y 变更零 i18n 面，最小切片；批 3 若开启，走库内 `showHeader=true` 路径（自建组件透传参数到 public `MarkdownCodeBackground`，:1.0 矩阵）+ 同批提供 `LocalMarkdownA11yLabels` 本地化（CompositionLocalProvider 在 MarkdownContent 或主题层提供，7 字段 × 15 语言）。**不建议自绘顶栏**（internal TopBar 无法复用，自绘 = 重写剪贴板/圆角/a11y 全套，工作量 ≥ 开销收益）。

#### f) 可选 Settings 开关（总开关 / 亮暗高亮切换）——建议首期不做

若做，文件清单（对照 amoledDark 开关既有链路 SettingsDataStore.kt:43/:156-157/:201/:383）：

| 文件 | 改动 |
|---|---|
| `domain/model/AppSettings.kt` | +1 字段 |
| `data/repository/SettingsDataStore.kt`（409 行） | key + Flow + setter + 序列化 4 点 |
| `domain/repository/SettingsRepository.kt` + `data/repository/SettingsRepositoryImpl.kt` | 接口 + 实现 |
| `ui/screens/settings/SettingsViewModel.kt`（228 行） | 暴露 Flow + 更新方法 |
| `ui/screens/settings/sections/AppearanceSection.kt`（amoledDark 先例 :35） | 开关 UI |
| `res/values/strings.xml` + 14 个 `values-*/strings.xml` | 文案 ×15 语言（现有 912 条/语言基线） |
| `MarkdownContent.kt` | 读开关（经 CompositionLocal 或参数下传） |

工作量 **M**。**建议首期不做**的理由：高亮纯增益、降级优雅（不支持语言静默纯色 = 现状）；无隐私/成本/审美分歧面大到一个开关的必要；真机观察（批 2）若发现大块流式 CPU 不可接受，开关是现成退路——届时再做不迟（届时一并考虑「亮色暗色独立色板」是否真有需求，SyntaxTheme 纯函数替换即得）。

#### g) 测试新增落点

现状：`app/src/test/kotlin/.../chat/markdown/` 21 个文件 2419 行（SafePrefixGateTest/InlineSpanSafetyTest/StreamingPilotEligibilityTest/NormalizationStreamingMonotonicityTest 等——**纯 JVM 单测**，无 Robolectric/Paparazzi；`grep paparazzi app/build.gradle.kts` 零命中）。androidTest：`chat/ChatMessageRenderingTest.kt`、`chat/ChatScrollStabilityTest.kt` 等 Compose 仪器测试（Hilt TestRunner）。

| 新测试文件 | 测什么 | 形态 |
|---|---|---|
| `CodeSyntaxThemeTest.kt` | ColorScheme→SyntaxTheme 9 角色映射纯函数：明/暗/动态色输入、AMOLED 免特判、alpha 保全（toArgb 不丢 alpha） | JVM 纯函数，TDD 可先行 |
| `HighlightedCodeSpanSafetyTest.kt` | 区间防御：反向区间（issue #75 形态 start>end）、end 越界、start≥end、end==length（exclusive 边界）、合法区间逐个保留、**end exclusive 语义钉死**（"1e+10"→(0,5) 全染 5 字符） | JVM 纯函数（safeBuildHighlightedAnnotatedString 不依赖 Compose） |
| （可选）androidTest 扩例 | `ChatMessageRenderingTest` 加「含 kotlin 围栏代码块消息渲染含着色 span」断言 | 仪器 |

组件壳（SafeHighlightedCode composable）保持薄，逻辑全部下沉到上述两个可单测函数——与该目录现有「纯逻辑 + 单测一一对应」的组织惯例一致。

### 1.2 MarkdownContent 全部调用方盘点（影响面核心）

`grep MarkdownContent(` 全量 8 个调用点（7 文件）+ 1 个显式不走的通道：

| 调用点 | 场景 | isUser | 高亮波及 | 需要豁免？ |
|---|---|---|---|---|
| `MessageCardAssistant.kt:679` | assistant 完结分片主体（preParsed+blockRange） | false | **是（预期主场景）** | 否 |
| `MessageCardAssistant.kt:951` | 巨型段分片（Giant segment） | false | 是 | 否 |
| `PartContent.kt:157` | assistant 常规 Part.Text（pilot 流式/async/sync 三态入口） | false（:144-154 isUser=true 早退走纯 Text） | **是（流式主场景）** | 否 |
| `ReasoningBlock.kt:339` | 思考文本（**流式时 asyncParse=!isStreaming → 走 pilot**） | false | 是——**流式思考内的代码块同样承受高亮重启节律** | 否（思考文本常含代码，高亮是增益） |
| `TaskToolCard.kt:237` | 任务工具输出（分片静态） | false | 是（工具输出常含代码，增益） | 否 |
| `SearchToolCard.kt:133` | 搜索工具结果 | false | 是 | 否 |
| `SyntheticNotificationCard.kt:150` | 合成通知展开正文（asyncParse） | false | 是 | 否 |
| `CompactionCard.kt:183` | 压缩摘要展开（asyncParse） | false | 是（低频） | 否 |
| `MarkdownPreviewDialog.kt:136` | Markdown 预览对话框 RENDERED 模式（asyncParse） | false | 是 | 否 |
| （HTML 整消息预览通道） | `looksLikeHtmlPayload` → WebView（MarkdownContent.kt:62-69 判定，渲染走 ChatScreen 侧） | — | **否**（不经 MarkdownContent） | — |

**结论**：components 是 MarkdownContent 内部单例（:524），覆写 codeFence 后**全部 8 个调用点自动获得高亮**——它们清一色是 assistant 风格内容（agent 输出/工具输出/思考/预览），语义上均应高亮，**无需任何豁免动作**；但每个面都要进批 1 回归清单（§3）。user 气泡（MessageCardUser.kt:123/:420-422 纯 Text 渲染）结构性不触达——MarkdownContent.kt:391-416 的 isUser 色叉与 :524 键中的 isUser 均为潜伏防御，保持不动（未来若恢复用户消息 Markdown 渲染再处理豁免，届时在高亮组件入口按 isUser 短路即可）。

---

## 2. 波及面盘点（Part 2：不改但行为受影响/需回归验证）

### a) 流式管线

- **SafePrefixGate / StreamingMarkdownPilot 零改动**：高亮完全在渲染组件层（codeFence），gate 的放行决策（SafePrefixGate.kt:88-149 围栏分支）、前缀差分 append（StreamingMarkdownPilot.kt:50-72）、#487 行级放行语义不感知高亮存在。代码块文本仍以行/行内前缀粒度进入渲染树——变的是该文本的 span 着色，不是放行节律。
- **produceState 重启行为**：每 append 批 code 变化 → 高亮作业整块重启（Default 线程 + awaitDispose 取消，不叠加不卡主线程）；48ms 批处理天然节流重启频率（≤20/s）。小中块（≤百行，单次毫秒级）流式中即可见着色；大块快速流 = 纯色到流暂停/EOF——降级优雅（初值即纯文本 AnnotatedString，无空窗无闪烁），代价是 Default 线程 O(N²) 级重复功（每行追加重高亮全块）→ §4 风险⑦ + §2.j 观察方案。
- **#472 完结换装交互**：pilot 终帧（可能纯色）→ 完结渲染（着色）存在一次**颜色 pop**。两路径共用同一 components/builder（同源）→ pop 仅为「无色→有色」，不伴随文本重排（#471③ 终帧=流式帧逐字节一致）。缓解梯度：批 1 接受（EOF 后一次性，观感待验）→ 批 2 若明显，完结路径切 `immediate=true` 同步高亮（chunk 内 code 通常 <2000 字符，主线程 1-3ms 有界，对照 #428 同步解析先例 MarkdownContent.kt:774-785）。

### b) 滚动 / 高度引擎

- **着色 span 布局中性**：`ColorHighlight→SpanStyle(color=…)` 不改字符内容/字号/字体/行高；ColorTypography（Type.kt:119-125）与 DefaultMarkdownTypography.code（MarkdownContent.kt:479-483）不动 → 重测高度不变 → `MDResize d=0`，不干扰 StreamingGrowLedger/#435 配对。
- **唯一例外：`BoldHighlight→SpanStyle(fontWeight=Bold)` 的 advance 风险**——等宽字体家族 Bold 与 Normal 通常同 advance（`FontFamily.Monospace` 系统等宽），但**exotic 等宽字体 Bold 变宽则单块重排一次**。这是「MDResize d=0」论证的前提缺口 → **真机首验项**（§3 批 1 验证门槛；若真发生，缓解 = fork 版剔除 BoldHighlight 映射（只保 ColorHighlight），一行改动）。
- **SSE 滚动铁律逐条核对**（AGENTS.md）：① `rememberMarkdownState(retainState=true)`/StreamingMarkdownState 路径不动 ✅；② 48ms 批处理不动（高亮不进 gate/不进 flush）✅；③ 流式增长配对只应用于流式 turn——高亮不改配对语义 ✅；④ autoScroll 双键重估不动 ✅；⑤ Ktor OkHttp 无关 ✅。**四条全绿，零违反**。

### c) 主题切换 / 配置变更

- 切换面：动态色开关（Theme.kt:123-129）、AMOLED 切换（:127/:146）、dark/light（:123）都会改变 colorScheme 实例 → `codeHighlightTheme` remember 键变化 → 新 builder → components remember 键（含 builder，§1.1.b）变化 → codeFence 闭包重建 → 已渲染代码块的 AnnotatedString 在下一重组以新色重建。机制与 :521-523 既有先例（linkColor 残留旧色修复）完全同构——**键纪律写对即无残留**；批 1 真机验证项（切换主题后滚动到历史代码块看色值）。
- AMOLED 深度：accent 角色不被 AMOLED 覆盖（Theme.kt:96-104 仅 surface 族）→ SyntaxTheme 映射自动成立；但 `codeBlockBg` 在 AMOLED 下是 surfaceContainerHighest（MarkdownContent.kt:398）——primary/tertiary 等在更深背景上的对比度需批 2 真机目检（动态色设备 ×2 种背景）。

### d) R8 / ProGuard

- 自建路线：**零新包名**——新代码全在 `dev.leonardo.ocbeacon.**`（无 keep 需求）；highlights keep 已在（proguard-rules.pro:43）。release 构建预期零改动通过。
- 对照：若走 -code 依赖路线，其包名 `com.mikepenz.markdown.compose.elements` 落在 :39 `-keep class com.mikepenz.markdown.** { *; }` 通配内——两条路 R8 均无动作。

### e) 依赖树

- 自建路线：**POM 零变化**——不引入 -code，其传递依赖（core 0.45.0 + highlights-jvm 1.1.0 + kotlin-stdlib 2.4.10）全部已在树中且版本一致（上轮调研 §3.5 已逐项核对）。APK 体积净增 ≈ 新增 2 个 Kotlin 文件的 dex（~几 KB），对比 -code 路线的 17.3KB AAR。
- 无新第三方实体 → 无 BOM/版本列车冲突面。

### f) Lint

- `app/lint-baseline.xml` 仅 2 条 markdown 相关豁免（:1545/:1556，均 MarkdownTable.kt）——与新代码无交集；新文件预期零豁免（`abortOnError` 只拦新增失败）。
- `lint-checks` 自定义规则（ServerTypeWhitelist/ServerTypeUiBoundary/TokenBypass/SpacingTokenBypass，lint-checks/src/main/kotlin/.../lintrules/）**不会触及**新代码——新组件全用 colorScheme 语义色 + AlphaTokens + remember 正规写法，本就是这些规则鼓励的方向。

### g) 文档面

| 文档 | 动作 | 内容 |
|---|---|---|
| `docs/regression-guide.md` | **建议补**（1-2 行） | 域 6「Markdown 渲染」（:133）判据追加「代码块语法高亮着色正确/流式纯色→着色时机/主题切换无残留」 |
| `docs/ui-conventions.md` | **建议补**（1 小节） | Theme Token System（:37）下记 SyntaxTheme 9 角色→colorScheme 映射决策表（§1.1.c 的表）——属「颜色用 colorScheme 语义色」规则的延伸记录 |
| `AGENTS.md` | **不动**（预期） | 无新依赖体系、SSE 铁律零变化；高亮属 markdown 域内部实现，未达 AGENTS.md 收录门槛（对照 agents-file-design.md 决策流程） |

### h) i18n

- 纯着色方案（批 1/2）：**零文案零 i18n 面**——无 stringResource、无硬编码可见文本（着色只是 SpanStyle）。
- 一旦开 showHeader（批 3）：可见层零英文（语言名/符号）；a11y 层若做本地化 = `LocalMarkdownA11yLabels` 7 字段 × `values/` + 14 个 `values-*/`（对照 docs/i18n-guide.md 工作流：改英文源 → 翻译 14 语言 → 跑检查脚本，CI 发版自动检查）。若做 Settings 开关（批 3 可选）：+2-3 条文案 ×15 语言。

### i) 无障碍

- 着色不改语义树结构：库默认 `MarkdownCodeBackground` 的 contentDescription（"code block [with language X]"，经 LocalMarkdownA11yLabels）在 fork 路径中**原样保留**（壳完全复用 core public 组件）——TalkBack 行为不变。着色仅增视觉冗余编码；色盲考量一句话：keyword/string/literal 三色区分在色盲视角下退化为亮度/饱和度差——可接受（着色是增强信息而非唯一信息，代码语义由文本本身承载）。

### j) 性能观测（若做打点）

- 落点：自建组件 `SafeHighlightedCode` 内（对照 #487 journal 的探针手法 + StreamingMarkdownPilot.kt:370-393 的 AppLogger.i 先例）——`AppLogger.i("CodeHL", "len=… lang=… ms=…")`，DEBUG 门控或常开轻量；AGENTS.md 要求新日志用 AppLogger（会出现在 Diagnostics 屏）。
- 大块流式 O(N²) 量化观察方案（批 2）：真机发「Write a Kotlin file about 300+ lines」→ logcat 过滤 CodeHL 统计（次数/耗时/len 序列）+ `simpleperf`/CPU profiler 看 Default 线程占比 + 对照 #487 判据仪器（MDPilot/MDResize 探针）确认滚动域零劣化。

---

## 3. 实施批次划分（Part 3）

| 批次 | 内容 | 涉及文件 | 验证门槛（docs/verification.md V1-V6） |
|---|---|---|---|
| **批 1 最小着色** | 自建组件（assistant-only 天然成立、无 header、无开关）+ M3 令牌主题 + 防御 + 单测 + 注释/文档小补 | 新增 `CodeSyntaxTheme.kt` + `HighlightedCode.kt`（或合一）+ 2 测试文件；改 `MarkdownContent.kt`（§1.1.b 三点位）+ `build.gradle.kts:248-249` 注释 + regression-guide/ui-conventions 各 1 处 | **V1**：`compileDevDebugKotlin` + `testDevDebugUnitTest --rerun`（新测试 + 全量 3750 基线不破）；**V3 真机**：`./scripts/debug-entry.sh` 入口，复用 #487 E2E 场景「Write a Kotlin file about 50 lines」——MDResize 严格单调、RESETKEY=0/nonPrefix=0 判据全保 + 新增判据（流式中着色时机/完结 pop 观感/Bold advance 无单块重排）+ §1.2 八调用面回归（工具卡/思考/压缩卡/预览对话框各发一例）+ 主题三切换（动态色/AMOLED/明暗）无残留旧色；**V4**：对照 regression-guide 域 6；**V5**：lint 零新增 |
| **批 2 观察与精修** | 大块流式 CPU/纯色时长量化（§2.j）；metadata/mark 角色视觉试验；AMOLED/动态色对比度目检；完结 pop 若明显 → 完结路径 immediate 同步高亮实验；Bold advance 若真变宽 → 剔除 BoldHighlight 映射 | 可能微调 `CodeSyntaxTheme.kt`/`HighlightedCode.kt`；journal 记录取证 | 观察批：journal 按 #487 手法记仪器证据；改动则重跑批 1 真机判据子集；用户观感验收（V6 类：着色时机/观感属主观拍板面，列清单请用户过目） |
| **批 3 可选增量** | ① showHeader（语言标签+复制按钮，走库内 showHeader 透传）+ LocalMarkdownA11yLabels 本地化（7×15 语言）；② 或 Settings 总开关（§1.1.f 清单，仅当批 2 发现需要）；③ 语言覆盖缺口评估（json/yaml 等静默纯色是否可接受/需换引擎——越出本批则立卡） | +AppearanceSection/Settings 族 5 文件 + strings ×15；或 MarkdownContent a11y Provider | i18n 检查脚本过 + TalkBack 抽查（a11y 标签）+ 批 1 真机判据回归子集 |

批次理由：批 1 是「最小切片」——单文件组件 + 纯函数主题，无文案无设置无依赖变化，回滚 = 删 2 新文件 + revert MarkdownContent 三点位（互不缠绕）；批 2 把上轮调研标注的三个「待真机证实」项（§4.2 ①② + Bold advance）变成仪器证据再决定精修方向；批 3 的三项独立可选，按批 2 结论触发。

---

## 4. 风险矩阵（Part 4）

| # | 风险 | 触发条件 | 观测手段 | 影响 | 回滚难度 |
|---|---|---|---|---|---|
| ① | 流式大块纯色到 EOF（高亮作业反复取消，着色迟到） | 数百行代码块 + 快速流（高亮耗时 > 48ms 批间隔） | logcat CodeHL 打点（len/ms 序列）；真机 300+ 行场景 | 中（体验：迟到但不缺失；CPU 见⑦） | 低（接受现状或流式期禁用高亮——组件加 streaming 门控一行） |
| ② | 完结换装颜色 pop（纯色→着色一次跳变） | pilot 终帧未着色 + 完结帧着色，且用户注视中 | 真机 E2E 完结窗观察；#487 journal A11yDiag path 探针对照 | 中（#472 无缝语义的视觉减损） | 低（完结路径 immediate 同步高亮，批 2 实验） |
| ③ | Bold advance 变宽 → 单块重排一次（MDResize d≠0） | exotic 等宽字体 Bold 与 Normal advance 不等 + 代码含 BoldHighlight（关键字） | 真机首验：代码块流式/完结 MDResize 是否出现与内容增长无关的 d | 低-中（单块一次重排，不破坏配对——配对按增长源 +Δ 规则仍成立） | 极低（剔除 BoldHighlight 映射一行） |
| ④ | #415 类反向区间崩溃（addStyle Reversed range） | highlights locator 产出 start>end（issue #75 形态：`*/path/*` 多行注释）或 end 越界 | 单测钉死（SpanSafetyTest 反向/越界用例）；真机难构造 → 靠防御+测试 | 高（崩溃）但已被防御消解 → 实际低 | 防御本身零回滚面（守卫只跳过非法 span） |
| ⑤ | 主题切换残留旧色 | components remember 键漏 builder/主题角色（违反 :521-523 纪律） | 真机三切换（动态色/AMOLED/明暗）后看历史代码块色值 | 中（视觉 stale） | 低（补键即修） |
| ⑥ | user 气泡误启用高亮 | 未来恢复「用户消息渲染 Markdown」（PartContent isUser 分支 reopen）而忘记高亮豁免 | 结构性现状为零风险；reopen 时 code review 项 | 低（当前结构不可能触发） | —（现状零动作） |
| ⑦ | 大块流式 Default 线程 O(N²) 重复功（CPU/电量） | 同①触发条件 | simpleperf/profiler Default 线程占比 + CodeHL 打点量化；批 2 专项 | 低-中（不卡主线程，后台浪费） | 低（流式期禁用高亮/加长度阈值 500 行以上跳过） |
| ⑧ | fork 版本漂移（core 升级后官方 -code 修复未跟进，仓库 fork 分叉腐化） | 未来 mikepenz 升 0.46+ 且改动 MarkdownCode/高亮相关 | 文件头 fork 基线注释 + 升级时 diff 官方同文件（本文件 §1.0 矩阵留档） | 低-中（长期维护） | —（纪律项） |

（若走 -code 依赖路线，另有⑨「依赖与 core 版本列车耦合」——自建路线该风险不存在。）

---

## 5. 工作量与文件计数汇总（Part 5）

- **必改文件 N=2**：
  1. `app/build.gradle.kts`（:248-249 注释，2 行）
  2. `app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/markdown/MarkdownContent.kt`（builder 构建 + remember 键 + codeFence/codeBlock 覆写，~35-45 行）
- **可选文件 M≈9**（全部批 3，默认不做）：AppSettings / SettingsDataStore / SettingsRepository / SettingsRepositoryImpl / SettingsViewModel / AppearanceSection（开关 6 文件）+ values/strings.xml + 14 × values-*/strings.xml（i18n，按 15 目录计）；文档 2（regression-guide、ui-conventions——已计入批 1 建议项，若视为必改则 N=4）
- **新增文件 K=2-3**：`CodeSyntaxTheme.kt`（映射纯函数，~50 行）+ `HighlightedCode.kt`（fork 组件 + 防御 + 打点，~120 行；两文件可合一）+ 测试 `CodeSyntaxThemeTest.kt` / `HighlightedCodeSpanSafetyTest.kt`（~120 行）

| 批次 | 工作量 | 一句话依据 |
|---|---|---|
| 批 1 最小着色 | **S**（1 个会话内可完成） | 2 新文件 ~170 行 + 3 点位接线 + 2 纯函数测试文件，全部有先例可抄（-code 源码/HighlightBuilder/:521-523 纪律） |
| 批 2 观察与精修 | **S-M**（半天-1 天） | 主要是真机观察与记录；最多两处小改（immediate 实验/角色微调） |
| 批 3 可选增量 | **M**（1-2 天，若含 15 语言 a11y/开关文案） | 大头是 i18n 15 目录翻译 + 检查脚本；代码面小 |

**总评：小切口多波及**——核心改动只有 2 个必改文件 + 2 个新文件（~220 行实现 + ~120 行测试），但「components 单例共享」使高亮一次性波及 8 个调用面（assistant 双路径、流式 Reasoning、工具卡×2、通知卡、压缩卡、预览对话框），且与流式管线（重启节律/完结换装）、高度引擎（Bold advance）、主题体系（remember 键/AMOLED/动态色）三套承重机制交叉——批 1 的真机验证门槛因此面广而不可省。

---

### 引用清单（外部一手资料）

- -code v0.45.0 源码（可见性矩阵依据）：https://raw.githubusercontent.com/mikepenz/multiplatform-markdown-renderer/v0.45.0/multiplatform-markdown-renderer-code/src/commonMain/kotlin/com/mikepenz/markdown/compose/elements/MarkdownHighlightedCode.kt
- core v0.45.0 `MarkdownCode.kt` / `MarkdownCodeTopBar.kt`（同 repo 同 tag 路径 `multiplatform-markdown-renderer/src/commonMain/...`；TopBar 文案与 a11y 事实）
- core 0.45.0 AAR 反编译（MarkdownCodeFence/Block/Background public、LocalMarkdownDimens/Padding/Colors public、MarkdownA11yLabels 7 字段、markdownComponents 23 参数）：`~/.gradle/caches/modules-2/files-2.1/com.mikepenz/multiplatform-markdown-renderer-android/0.45.0/`
- highlights 1.1.0 jar 反编译（SyntaxTheme(String, int×9) 构造器、Highlights.Builder API）：`~/.gradle/caches/modules-2/files-2.1/dev.snipme/highlights-jvm/1.1.0/`
- PhraseLocation end exclusive 语义：https://github.com/SnipMeDev/Highlights#readme（`emphasis(PhraseLocation(13, 25)) // ExampleClass`）+ NumericLiteralLocator 测试（0.7.1...0.8.0 compare `PhraseLocation(0, 5)` ↔ "1e+10"）
- 反向区间野外存在：https://github.com/SnipMeDev/Highlights/issues/75
- 上轮调研（版本矩阵/issue #315/#415/#477/PR #500/#501/接入示例）：[`2026-09-30-code-syntax-highlighting-feasibility.md`](2026-09-30-code-syntax-highlighting-feasibility.md) 引用清单
