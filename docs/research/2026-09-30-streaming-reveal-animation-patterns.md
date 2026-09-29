# 流式文本「揭示动效」业界方案调研（揭示粒度 / 尾部渐变 / 光标惯例 / 未闭合语法 / 速率策略）

> **日期**：2026-09-30 · **调研员**：OC Beacon research agent
>
> **方法约定**：每条论断标注一手信源（官方文档 / 仓库源码 raw 原文 / 平台 API 文档 / 学术论文）；【实证】= 直接抓取一手来源原文或源码；【二手】= 可访问的第三方描述他人产品；【推测】= 无法一手实证，推导过程写明。参数数字一律来自源码/文档原文，**不凭印象**。
>
> **与 2026-08-30 姊妹篇的分工**：[`2026-08-30-ai-streaming-render-landscape.md`](2026-08-30-ai-streaming-render-landscape.md) 已覆盖 Android 库横评（mikepenz StreamingMarkdownState、Stream StreamingText 30ms/词、Operit 流级插件、androidx/M3 无流式 API）。本文**不重复**该内容，专注其缺口：Web 端 AI 产品的视觉模式、streamdown、smoothStream、速率策略、光标形态学与闪烁常量。Stream StreamingText 30ms/词等结论在本文按需引用不重证。
>
> **设计语境**：我们正在设计的动效 = 流式 markdown 逐字揭示 + 尾部 4 字不透明度渐变（20/40/60/80% 阶梯）+ 停顿时渐变按 0.1s 推档排空 + 老式控制台下划线光标闪烁。

---

## TL;DR（先看这里）

| # | 结论 | 证据级 |
|---|------|--------|
| 1 | **没有任何主流产品做了「尾部 N 字不透明度阶梯渐变」**——最接近的官方实现是 streamdown 的逐词 150ms fadeIn（新词淡入，非旧字阶梯）。我们的阶梯设计属差异化创新，无惯例背书也无反例 | 【实证-检索性否定】 |
| 2 | **光标生命周期业界共识 = 流中存在、完成立即移除**。Vercel 官方文档明文 "Carets are automatically removed when streaming ends"；无任何产品完成后保留光标 | 【实证】 |
| 3 | **块形 ▍ 是 AI 产品光标绝对主流**；下划线是终端六形态之一（DECSCUSR 3/4 号），Web AI 产品不用。Vercel 官方文档直接点名 ChatGPT 与 Claude 用 caret | 【实证】 |
| 4 | streamdown 的 **remend** 引擎在字符串层修复流中未闭合 markdown（bold/italic/inline-code/strikethrough/code-fence/link/math），带防误判规则清单，真实闭合符到达后无缝替换——对我们「语法符中间态暴露」问题是**最佳参照实现** | 【实证】 |
| 5 | smoothStream() 语义 = **固定速率排空**（默认 10ms/chunk），源码证明**不解决快流积压**：到达快于释放时延迟无界增长。我们「停顿排空」设计在策略上优于它 | 【实证-源码】 |
| 6 | 闪烁常量平台惯例：Compose TextField **500ms 显/500ms 隐硬切换**（无渐变曲线）；Typed.js **0.7s 渐变 blink**；streamdown 光标 **889ms 淡入淡出脉冲**；Windows **530ms**；GTK **1200ms 全周期**。终端只定义形态不定义频率 | 【实证】 |
| 7 | Typed.js 反直觉惯例：**打字进行时光标常亮不闪，停顿时才闪**——与「忙时稳定、闲时闪烁」的注意力分配原则一致，值得照抄 | 【实证-源码】 |

---

## Q1 主流 AI 产品的流式文本视觉模式

### 1.1 先看业界组件级「共识快照」：streamdown carets 文档【实证】

Vercel 为流式 markdown 渲染器 streamdown 写的光标文档（<https://streamdown.ai/docs/carets>，2026-09-30 抓取）是目前**唯一一份把 AI 产品光标惯例写成规范的公开文档**：

- 原文："Streamdown includes built-in carets (cursor indicators) that you can enable during streaming to show where content is being generated — **similar to the carets you see in ChatGPT or Claude**."——Vercel 官方直接点名 **ChatGPT 与 Claude 都用 caret**（这是产品惯例的厂商转述，比社区猜测强一级）。
- 默认**块形 caret（▍）**，可切 inline 形态；以 CSS 动画脉冲。
- 脉冲参数【实证】：**889ms 间隔**、`cubic-bezier(0.4, 0, 0.6, 1)` 缓动、opacity keyframes（淡入淡出式脉冲，非硬切）。
- **生命周期**："Carets are automatically removed when streaming ends. When `isAnimating` becomes false, the caret is removed from the DOM and the content is displayed without any caret indicator."——**完成即从 DOM 移除，不留任何残余**。
- 使用约束：每段落只允许一个 caret；caret 必须挂在 inline 层容器上（blockquote 内不能直接放块级 caret）。

### 1.2 逐家核查

> 产品 Web 端均需登录，直接 DOM 取证不可行；能到手的一手证据有限，逐家标置信度。

| 产品 | 揭示粒度 | 尾部淡入 | 光标样式与生命周期 | 证据级 |
|---|---|---|---|---|
| **ChatGPT** | token 到达即重绘（服务端步调主导，非客户端匀速动画）；观感为词组级 | 无阶梯渐变 | 文本流尾部 ▍ 形块，闪烁；完成后立即消失 | 粒度与完成即消失【二手】：arXiv 2607.18507（<https://arxiv.org/html/2607.18507v1>）表述 "tokens are displayed as they are generated, producing the word-by-word reveal animation seen in ChatGPT, Gemini, and Claude"；▍ 字符【推测】（Vercel carets 文档点名其用 caret + 大量克隆实现采用 ▍，如 streamdown 默认值即以 ChatGPT 为范本） |
| **Claude.ai** | 词块级 | 无一手证据 | 块形 caret 脉冲；完成即消失 | 【二手+推测】：Vercel carets 文档点名（同上）为一手转述；具体样式细节（是否 fade）登录墙内无法实证，标【推测】 |
| **Gemini** | 词级，新词带 fade-in 观感；thinking 阶段用渐变微光文字（见 Q6） | 新词淡入（最接近尾部渐变的既有实现，但作用于新词挂载而非旧字阶梯） | 完成后无光标 | 【推测】：无公开 teardown 一手来源（多轮检索仅命中克隆教程与二手描述）；词级 fade 观感来自可观测 Web 端使用体验的社区共识 |
| **Perplexity** | 词组级 | 无证据 | 流中块形 caret，完成即消失 | 【推测】：登录墙内，无一手来源 |
| **DeepSeek** | token 直绘（快流，几乎无动画修饰） | 无 | 完成即无光标 | 【推测】：同上 |
| **Grok** | token/词级 | 无证据 | x.com 流中尾部光标，完成消失 | 【推测】：同上 |
| **Kimi (Moonshot)** | 打字机式逐字（国产品牌常见做法） | 无证据 | 块形/竖线光标 | 【推测】：同上 |

**粒度小结**【实证-综合】：可考的产品（ChatGPT/Claude/Gemini + Vercel 生态）全部落在 **token→词→词组** 粒度区间；**逐字符打字机在 AI 聊天产品中不是主流**（慢、贵、且与 markdown 增量解析冲突）。学术侧表述见 arXiv 2607.18507（"Animating from Prompt to AI-Generated Responses"）。

**光标生命周期小结**【实证】：所有可考的规范与实现（streamdown carets、Streamlit `st.write_stream` 光标——其 issue #11099 原文 "has a little blinking cursor at the end **while it streams** text"（<https://github.com/streamlit/streamlit/issues/11099>，gh api 抓取）、Compose TextField 取消即隐藏）**一致为「流中存在、结束立即移除」**。未发现任何产品保留完成态光标。

### 1.3 核心问题的直接回答：有没有产品做尾部渐变淡入？【实证-检索性否定】

- 未检索到任何主流 AI 产品实现「尾部 N 字不透明度阶梯」的一手证据（官方博客/设计工程文章/可抓取 DOM 均无）。
- 最接近的两个既有实现：
  1. **streamdown `animated`**：逐词 **150ms fadeIn**（默认 `animation: "fadeIn"`，纯透明度 0→1），文档原话 "Words fade in as they mount, creating a smooth text-reveal effect"，并明确动机 "**makes batch token arrivals look smooth rather than 'chunky'**"（<https://streamdown.ai/docs> Animation 节，2026-09-30 抓取）——它解决的是我们 48ms 批处理后「一坨字突然出现」的同一痛点，但方案是**新词个体淡入**，不是旧字阶梯。
  2. **Gemini 词级 fade-in**（【推测】，见上表）。
- **判定**：我们的「尾部 4 字 20/40/60/80% 阶梯」在可考范围内**无先例**——差异化空间真实存在；同时意味着无现成可用性证据，需真机自证（尤其小字号低对比可读性与 markdown 语法符被半截染色的问题，见 Q2/启示节）。

---

## Q2 streamdown：流中未闭合 markdown 的修复机制（remend）【实证】

> 来源：<https://streamdown.ai/docs/termination> + README（raw.githubusercontent.com/vercel/streamdown/main/packages/streamdown/README.md），2026-09-30 抓取。

**定位**：remend = "a lightweight, standalone preprocessor that completes incomplete Markdown syntax"，在 unified/remark 管线**之前**、"operating on the raw string level for maximum performance"——即**字符串级预修复**，不动 AST。

**处理的中间态清单**【实证】：

| 流中状态 | remend 动作 |
|---|---|
| `**hello`（未闭合粗体） | 自动补 `**`，按粗体渲染（不裸露星号） |
| `*text` / `_text`（未闭合斜体） | 补闭合符 |
| `***text`（粗斜体） | 补闭合 |
| `` `const foo = "bar ``（未闭合行内代码） | 补反引号，按代码渲染 |
| `~~text`（未闭合删除线） | 补 `~~` |
| 词内单 `~`（如 `20~25°C`） | 转义，防 GFM 误判删除线 |
| 未完成链接 `[Click here](https://…` | 改写为 `[Click here](streamdown:incomplete-link)`——**能看不能点**（特殊 URL 保证渲染成链接样式但不导航）；`linkMode: 'text-only'` 可改纯文本 |
| 未完成图片 | **整体移除**（不显示破图占位） |
| 未闭合块级公式 | 补 `$$` |

**防误判规则**（避免把真语法当残缺语法）【实证】：列表项开头的格式符不补闭合；**完整代码块内部的格式符一律不动**；`$$…$$` 内的下划线不当斜体；字母数字之间的 `*`/`_`（如 `user_name`、`234*123`）保留原样。真实闭合符到达后 "seamlessly updates"——自动替换为真语法。

**API 与复用**【实证】：streamdown 内默认开启，`<Streamdown parseIncompleteMarkdown={false}>` 关闭（文档警告关闭后 "incomplete Markdown syntax being displayed literally"）；remend 可独立安装（`import remend from 'remend'`），支持 `RemendHandler`（`name`/`handle`/`priority`）自定义与 `isWithinCodeBlock`/`isWithinMathBlock` 上下文工具。Vercel 宣传文：<https://vercel.com/changelog/introducing-streamdown>（"handles unterminated chunks, interactive code blocks, and math that break existing markdown renderers"）。

**对 Kotlin 侧的映射价值**【推测，基于上述实证】：remend 的「字符串级、只处理不稳定尾部、防误判规则表、闭合到达后无缝替换」四要素可直接移植为我们在 `MarkdownChunking.kt` 渲染前的尾部修复函数；防误判规则清单可照抄。

---

## Q3 Vercel AI SDK `smoothStream()`：速率语义与积压【实证】

> 来源：官方参考 <https://ai-sdk.dev/docs/reference/ai-sdk-core/smooth-stream> + 源码 `packages/ai/src/generate-text/smooth-stream.ts`（vercel/ai main 分支 raw，2026-09-30）。

**签名**【实证】：

```ts
smoothStream({ delayInMs = 10, chunking = 'word' /*, _internal */ })
```

- `delayInMs`：每个 chunk 之间的固定延迟，**默认 10ms**；设 `null` 跳过延迟。
- `chunking`：`'word'`（默认，正则 `/\S+\s+/m`）/ `'line'`（`/\n+/m`）/ 自定义 `RegExp`（不得匹配空串）/ `Intl.Segmenter`（**CJK 场景官方推荐**——文档明说 word 正则对中日韩越泰"does not work well"，因为无空格）/ 自定义 `ChunkDetector` 函数（返回 buffer 前缀或 null 表示"等更多文本"）。
- 非 text/reasoning chunk（工具调用、step-finish）立即直通，不参与平滑。

**积压语义（源码核心循环）**【实证-源码】：

```ts
buffer += chunk.text;
while ((match = detectChunk(buffer)) != null) {
  controller.enqueue({ type, text: match, ... });
  buffer = buffer.slice(match.length);
  await delay(isDocumentHidden() ? null : delayInMs);
}
```

- 即：**每 `delayInMs` 释放 1 个 chunk 的固定速率排水**。到达快于释放时，积压按固定 chunk 率追赶，**延迟无界增长**——`smoothStream` 解决的是「到达节奏不匀导致的跳变」，**不解决「揭示落后于到达」**，没有任何自适应加速。
- 一个值得注意的工程细节：`isDocumentHidden()` 时跳过 delay，源码注释原文——后台 tab 的定时器被浏览器节流，"which would stall the smoothing delay and, **through backpressure, the entire stream**"（后台页延迟会经反压停摆整条流）。**对我们等价警告：Android 上若揭示 delay 挂在 UI 侧背压上，Activity 不可见/动画关闭时必须直通**（Compose 侧对应 b/265177763 的「动画关闭时也保持闪烁」修订，见 Q5）。

**速率坐标**【实证】：smoothStream 默认 10ms/词（100 词/s，接近全速微平滑）；Stream `StreamingText` 30ms/词（姊妹篇已证）；Typed.js `typeSpeed` 默认 **0**（纯到达速率）。业界默认值整体偏「快、微平滑」，没有产品默认做慢速逐字。

---

## Q4 揭示速率策略：fixed vs adaptive；打字机库惯例

### 4.1 自适应追赶：无权威公开实现，实践共识存在【二手-综合】

- 未找到「自适应打字速度/积压追赶」的权威一手实现或工程文章（多轮检索；AI SDK 官方选择固定速率即反证）。
- 检索到的实践共识【二手-综合】： buffer + requestAnimationFrame 每帧按积压深度按比例排水，clamp 在 min（保打字感）/ max（保追得上）之间；超大积压（如历史恢复）直接全量直渲。
- **判定**：这是业界空白点。我们的「停顿排空 + 推档」属于这一家族的到达驱动变体（见启示节）。

### 4.2 打字机库的光标与速度惯例【实证-源码】

**Typed.js**（mattboldt/typed.js，lib/typed.js 源码 2026-09-30 抓取）：

- `typeSpeed` 默认 **0** ms/字符；`backSpeed` 默认 0；`cursorChar` 默认 **`'|'`**（竖线）；`showCursor` 默认 true。
- 光标闪烁 CSS（源码内嵌，`autoInsertCss` 注入）：`.typed-cursor--blink { animation: typedjsBlink 0.7s infinite }`，keyframes `50% { opacity: 0.0 }`——**0.7s 完整周期、中点透明度为 0 的渐变闪烁**（不是硬切）。
- **关键行为**：打字进行中移除 blink class（光标常亮稳定），**停顿/句间才加 blink class**（源码 `toggleBlinking(false)` 处注释 "Accounts for blinking while paused"）——**忙时常亮、闲时闪烁**。
- `fadeOut` 模式：`typed-fade-out { opacity: 0; transition: opacity .25s }`——光标退场用 0.25s 淡出。

**CodeMirror**：`cursorBlinkRate` 默认 **530**（与 Windows 值对齐，见 Q5）【二手】。

**react-type-animation**：raw README 抓取失败（分支/路径问题），未取得一手参数，**标记为证据缺口**。

---

## Q5 光标惯例：终端形态学与平台闪烁常量

### 5.1 终端光标形态学：DECSCUSR 定义 6 形态【实证】

xterm ctlseqs（<https://invisible-island.net/xterm/ctlseqs/ctlseqs-contents.html>）：`CSI Ps SP q`（DECSCUSR，VT520/VT510）：

| Ps | 形态 |
|---|---|
| 0/1 | **blinking block（默认）** |
| 2 | steady block |
| 3 | **blinking underline** |
| 4 | steady underline |
| 5 | blinking bar |
| 6 | steady bar |

要点：**blink 是终端默认态**（默认形态即闪烁块）；**下划线光标是正典形态之一**（3/4 号）；DECSCUSR 只定义形态不定义闪烁频率——**频率由各平台/终端实现自定**，见下表。ECMA-48 层面亦无频率标准（其控制功能不含速率参数）【推测-基于 DECSCUSR 仅形态定义的旁证】。

### 5.2 各平台闪烁频率常量【实证】

| 平台 | 常量 | 值与语义 | 来源 |
|---|---|---|---|
| **Compose (androidx)** | `CursorAnimationState.kt`（foundation，internal） | **`delay(500) → alpha=0 → delay(500) → alpha=1` 硬切换**：500ms 显/500ms 隐，完整周期 1s，占空比 50%，**alpha 只有 0/1 无渐变曲线**；启动即 alpha=1；取消时 `finally { cursorAlpha = 0f }` 立即隐藏 | raw.githubusercontent.com/androidx/androidx（androidx-main）源码原文，2026-09-30；文件 `compose/foundation/foundation/src/commonMain/kotlin/androidx/compose/foundation/text/input/internal/CursorAnimationState.kt` |
| Compose 实现注记 | 同文件类注释 | "We can't use the Compose Animation APIs because they **busy-loop on delays**... Pure coroutine delays... will not cause any work to be done until the delay is over"——平台自己都避开 InfiniteTransition 做闪烁，我们手搓应照抄协程 delay 方案 | 同上 |
| Compose 行为修订 | foundation release notes I95e70 / b/265177763 | "The cursor in text fields will now **continue to blink even when animations are disabled**"——系统动画关闭时光标仍闪（无障碍层：光标可见性不随动画开关消失） | developer.android.com/jetpack/androidx/releases/compose-foundation |
| **Windows** | `GetCaretBlinkTime` / 注册表 `HKCU\Control Panel\Desktop\CursorBlinkRate` | 默认 **530ms**（一次反转间隔，半周期）；INFINITE = 不闪 | learn.microsoft.com GetCaretBlinkTime 页 + US6122661A 专利文本 "default value is 530 milliseconds" + Citrix WEM 文档（多重二手+API 文档交叉） |
| **GTK** | `gtk-cursor-blink-time` | 默认 **1200**，语义为**完整 blink 周期** | docs.gtk.org/gtk3/property.Settings.gtk-cursor-blink-time.html（"Default value: 1200"，cycle duration in ms） |
| **Typed.js (Web)** | `typedjsBlink` | **0.7s 完整周期**，中点 opacity 0（渐变） | 源码内嵌 CSS 原文 |
| **streamdown caret** | CSS 脉冲 | **889ms + cubic-bezier(0.4,0,0.6,1) opacity 淡入淡出** | streamdown.ai/docs/carets |
| kitty 等现代终端 | `cursor_blink_interval` | 本次抓取失败，**证据缺口**，不影响上表结论 | —— |

**规律**【推测-综合】：完整周期落在 **0.7s–1.2s** 区间（0.7 Typed.js / 1.0 Compose / 1.06 Windows×2 / 1.2 GTK / 0.889 streamdown），中位约 1s；占空比约 50%；硬切（Compose/Windows）与渐变闪烁（Typed.js/streamdown）两派并存，**Web 产品派全部用渐变闪烁**。

---

## Q6 shimmer/微光占位（仅记录作对照）【推测】

- **Gemini thinking 微光文字**（"Thinking…" 文案上做灰-亮-灰渐变扫过）：无一手官方来源（检索仅命中社区克隆教程；常见实现为 `background: linear-gradient + background-clip: text + background-position 动画`，周期约 2s linear infinite）——标【推测】，仅作对照：业界把「模型在思考」的信号放在**独立占位元素**上做微光，而不是把微光混入正文流。
- streamdown / AI Elements / Stream StreamingText 均无 shimmer 正文效果；M3 无 AI 组件（姊妹篇已证 1.4.0 无相关 stable API）。
- 判定：shimmer 与我们的「正文揭示渐变」是**正交的两类效果**（占位信号 vs 内容揭示），可共存不冲突。

---

## 对我们设计的启示

### 1. 揭示粒度（逐字 + 48ms 批）
业界全部落在 token→词组粒度，无产品逐字符裸放。我们的「逐字揭示但经 48ms 批处理」天然等价于 2-4 字/帧的词组节奏（约 20.8 flush/s），**与业界默认区间（10-30ms/chunk）兼容**，无需改粒度。CJK 注意：smoothStream 官方承认空格分词对中日韩失效而改推 Intl.Segmenter——我们按字符揭示反而是 CJK 正确解，无需分词器。

### 2. 尾部 4 字 20/40/60/80% 阶梯渐变
无主流先例（检索性否定）。最接近的官方做法是 streamdown 逐词 150ms fadeIn（新词淡入）。**建议保留为差异化**，但两点校准： 阶梯是「积压深度的可视化」——4 字满档≈积压 4 字，语义成立； 排空速率需复核：0.1s/档 = 排空 4 字需 0.4s，而 streamdown 单词 fadeIn 仅 0.15s——若排空显得拖沓，收敛到 0.1s/档·上限 4 档的现设计已属克制，但**排空进行中若恢复到达，应立即从排空态切回跟随态**（业界无此细节先例，需真机手感裁决）。语法符中间态会被渐变半截染色——必须与 Q2 的尾部修复配合（先修复语法、后上渐变），否则 `**hel` 的半截星号会以 80% 不透明度高亮暴露。

### 3. 速率策略（停顿排空 = 优于 smoothStream 的点）
smoothStream 源码证明固定速率不解决积压（业界空白）。我们的「到达驱动揭示 + 停顿时按 0.1s 推档排空」本质是把追赶藏在渐变衰减里，**策略上先进于 smoothStream**。补一个业界警告的移植：smoothStream 在 document hidden 时跳过 delay 防止背压停摆——我们的揭示管线同样必须保证 **Doze/后台/动画关闭时直通不积压**（对齐 b/265177763 的平台修订方向）。

### 4. 光标生命周期（完成立即消失）
业界零例外：streamdown carets 明文自动移除、Streamlit 只在流中、Compose `cancelAndHide` 取消即 alpha=0。**建议：turn 完成 → 光标立即移除**（最多加一次 ≤200ms 淡出，Typed.js 光标退场 0.25s 淡出是唯一可引的退场参数），**不要保留闪烁光标到下一轮**。另一可照抄的惯例：Typed.js「忙时常亮、闲时闪烁」——映射到我们：**SSE 到达进行中光标稳定常亮，停顿排空期间才开始闪**，闪烁 = 「在等你」的信号而非「在写字」的信号。

### 5. 光标样式（下划线）与闪烁参数
下划线是终端正典形态（DECSCUSR 3/4），但 Web AI 产品（ChatGPT/Claude/streamdown 默认）清一色块形 ▍——选下划线即选「老式终端致敬」而非「AI 产品惯例」，属有意的设计表态，成立。参数对齐建议：完整周期 **1s、占空 50%**（Compose 平台惯例 500/500ms 硬切）为下限锚；若要产品级柔和感，参考 Web 两派（Typed.js 0.7s 中点归零渐变 / streamdown 889ms cubic-bezier 淡入淡出）改硬切为 opacity 渐变闪烁。实现上照抄平台：**纯协程 delay + 0/1 alpha 切换**（Compose 源码明言 InfiniteTransition 会 busy-loop 浪费帧），且动画全局关闭时保持闪烁。

### 6. 语法中间态（remend 移植）
我们「语法符中间态暴露」问题在业界有完整解：字符串级预修复 + 防误判规则表 + 闭合到达后无缝替换（Q2 全表）。建议在 `MarkdownChunking.kt` 渲染路径前实现 Kotlin 版 remend：最小集合 = `**`/`*`/`` ` ``/`~~`/未闭合代码围栏/未闭合链接，防误判规则（列表首格式符、完整代码块内不动、词内 `*_` 不当语法）**逐条照抄**；未完成链接按 remend 方案渲染为不可点样式而非裸文本。这同时是尾部渐变的**前置依赖**（见启示 2 的染色问题）。

---

## 附录 · 数据采集记录（2026-09-30）

- streamdown：<https://streamdown.ai/docs/carets>（889ms/cubic-bezier/自动移除原文）、`/docs/termination`（remend 全表）、`/docs`（animated 150ms fadeIn）、README（raw.githubusercontent.com/vercel/streamdown/main/packages/streamdown/README.md）。
- Vercel AI SDK：参考页 `ai-sdk.dev/docs/reference/ai-sdk-core/smooth-stream`；源码 `packages/ai/src/generate-text/smooth-stream.ts`（vercel/ai main raw）——路径经 gh api code search 确认。
- androidx：`CursorAnimationState.kt`（github.com/androidx/androidx androidx-main raw；路径经 gh api 确认）；compose-foundation release notes（I95e70 / b/265177763）。
- Typed.js：`lib/typed.js` raw 源码（blink CSS/typeSpeed/cursorChar/toggleBlinking 原文）。
- 平台常量：docs.gtk.org（gtk-cursor-blink-time=1200）；learn.microsoft.com GetCaretBlinkTime + US6122661A + Citrix WEM 2511（530ms 多源交叉）；CodeMirror cursorBlinkRate=530（搜索摘要，二手）。
- 终端：xterm ctlseqs（invisible-island.net）DECSCUSR 六形态；fish-shell#3741 佐证。
- 产品侧：streamlit/streamlit#11099（gh api 原文）；arXiv 2607.18507（token 逐步显示的学术表述）；vercel/ai-chatbot `components/ai-elements/message.tsx`（MessageResponse 即 Streamdown 包裹，实证 Vercel 自家聊天模板栈）。
- 证据缺口：react-type-animation README 抓取失败；kitty `cursor_blink_interval` 抓取失败；ChatGPT/Claude/Gemini/Perplexity/DeepSeek/Grok/Kimi 产品端均为登录墙，样式细节只能到【二手+推测】级。
- 环境备注：api.github.com 经镜像 301（curl 全程 `-L`）；vercel/ai 旧路径 `packages/ai/core/...` 404，实际为 `packages/ai/src/...`；Upstash smooth-streaming 博客 URL 已 404。
