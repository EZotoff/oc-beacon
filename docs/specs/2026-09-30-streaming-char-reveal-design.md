# 流式逐字揭示动效设计（char reveal + 尾部渐变 + 终端光标）

> 日期：2026-09-30
> 状态：已确认（grilling 十题全定案，2026-09-30 用户逐题裁决），待实现（P3）
> 位置约定：active spec 置于 docs/specs/；实现并验收后移至 docs/archive/specs/ 并更新本行状态
> 涉及模块：ui/screens/chat/markdown/（StreamingMarkdownPilot / SafePrefixGate / InlineSpanSafety / HeldTailReveal / MarkdownContent）、data/repository/handler/MessageEventHandler、ui/screens/chat/components/ScrollCompensation、theme/Motion、app/build.gradle.kts
> 关联 backlog：#483
> 调研依据：docs/research/2026-09-30-streaming-reveal-animation-patterns.md（业界动效模式）、docs/research/2026-08-30-ai-streaming-render-landscape.md（Android 流式渲染横评）、docs/research/sse-scroll-stability-iron-laws.md（铁律）

## 1. 概述

现状：流式输出以批处理 flush 产物为粒度整块蹦出（"世界"一次出现），观感生硬。本设计在**不动传输/批处理管线**的前提下，把用户可见的文本揭示改为**逐字形簇**节奏，并为前沿增加**尾部 4 字不透明度阶梯渐变**与**老式终端下划线光标**，最终形态：连续打字感 → 停顿时渐变排空、光标闪烁 → 完结干净收尾。

三件事各有一个「业界无先例」标签，均经调研确认属差异化设计，需真机自证：

1. 尾部 N 字不透明度阶梯（业界最接近仅 streamdown 150ms/词 fadeIn；无产品做阶梯）；
2. 下划线光标（块形 ▍ 才是 AI 产品主流；下划线是 DECSCUSR 3/4 终端正典，属有意的设计表态）；
3. 自适应揭示速率（业界仅固定速率排空：smoothStream 10ms/词、Stream 30ms/词，均无积压解法）。

## 2. 架构定位（红线，违反即回归）

```
SSE → MessageEventHandler 批处理（100ms flush，#437；注释沿用"48ms"旧名）
    → Part.Text 全串快照
    → SafePrefixGate（已存在）两级闸：行级状态机 + InlineSpanSafety.safeCut，
      只放行「语法安全前缀」，未闭合构造（**bo、未闭围栏、半截表格）扣留在 heldTail
    → 【新增：揭示层】消费闸放行文本，按字形簇节流放字（本 spec 全部动效逻辑所在）
    → StreamingMarkdownState.append（每次 append 库内只重解析不稳定尾部）
    → Markdown()（pilot 路径）
```

- **单一动画驱动红线**：揭示层是表现层唯一动画源（消费批处理缓冲），禁止在批处理之上再叠独立 typewriter（2026-08-30 调研已定罪「双重动画」模式）。
- **overlay 光标红线**：光标只能走独立 overlay（复用 HeldTailReveal 基建：`cursorOffsetFromLayout` 纯函数 + InfiniteTransition）。**禁用 Text inline content 做光标**——本项目血案先例：动画每帧放大为整段 Text 重排+重绘（贴底跟随每帧 trav 13ms 的根因），已定罪禁用。
- **不触碰**：批处理间隔、`scheduleFlush` 不取消定时器、高度引擎配对规则（StreamingGrowLedger / PreRenderCoordinator flush 单点）。揭示层产生的任何容器高度增长必须经高度引擎统一规则配对。
- SafePrefixGate 扣留期间揭示自然暂停，非异常路径；闸已保证渐变前沿几乎总是纯文本（语法中间态问题被现有闸解决，无需 remend 式预修复）。

## 3. 揭示机制

- **粒度**：字形簇（grapheme cluster）——CJK 单字、英文字母、emoji 组合、变音符各算一个字。切分用 `android.icu.text.BreakIterator`（框架自带零依赖，minSdk 26 满足；仓库尚无使用先例）；单测经 seam 接口注入朴素实现（代理对感知），与 `cursorOffsetFromLayout → HeldTailCursorTest` 纯函数缝同一惯例。已知降级：旧设备（<API 28）bundled ICU 可能拆开 ZWJ 组合 emoji（最坏分两步揭示），可接受。
- **自适应速率**（Q4 裁决 b）：`揭示速率 = max(基础 40 字/s, 实际到达速率)`。慢流呈打字感，快流永不积压（文本不落后于真实内容）。基础值 ≈ Stream 30ms/词的 CJK 等价，业界校准。
- **append 频率上限（资源硬帽，深挖修正）**：视觉速率无帽，但 `StreamingMarkdownState.append` 调用频率帽在 **≤40/s（tick 25ms 批量）**——快流时每次 append 含多字（如 200 字/s → 每 tick 5 字）。超高速流下 25ms 一步多字与人眼逐字感知不可分，既保「不积压」又防 append 风暴把库内不稳定尾重解析×帧率。
- **资源约束**（用户裁决，硬性）：自适应规则限简单算术（滑动窗口/EMA 级别）；揭示 tick 复用现有帧/flush 节拍；不引入轮询协程群、不做每帧整段重排。append 频率上限以帧为界（≤ ~60/s，实际远低）。
- **立即揭示豁免**（smoothStream hidden-tab 教训移植）：应用退后台、用户中断、停止生成、pilot 不可用（fallback 路径）→ 剩余积压**立即全部揭示**，光标立即消失。背压不允许停摆管线，动效只允许在用户看得见时存在。
- **豁免不覆盖传输层 stall（2026-09-30 看门狗交互澄清）**：`StreamStallWatchdog`（#441/#467，45s 字节级强杀→重连+backfill）属传输层兜底——强杀/重连期间 turn **并未终结**，揭示层不得把连接状态接入豁免触发器，正确行为是维持卡壳呈现（渐变推空+光标闪烁）等 backfill 恢复；恢复后若重放导致文本**非前缀重写**（pilot `resetKey` 重建路径），揭示进度**即时重置为满**（不补动画），光标行为延续。
- **离屏销毁与多 Part（深挖补充）**：LazyColumn 滚出视口会销毁流式 item 的组合——揭示进度 `revealedCount` 用 `rememberSaveable` 保全，回视口**快速追平**（不入动画）；多 Part 消息中非当前活跃的 Text Part（后续 Part 已出现或已完结）同样快速排空自身积压——防止「半截文字挂在上面、新 Part 在下面流」的破相。

## 4. 尾部渐变（Q1/Q2 裁决）

- **阶梯**：最新已揭示字起往左 20% / 40% / 60% / 80%，窗口左缘以外一律 100%；已揭示字数 <4 时按自然阶梯（2 个字 = 20%/40%），不足档位不补位（Q2=a）。
- **双驱动**（Q1=b）：位置推进（新字到达全员升一档）与时间推进（每 0.1s 一档）取先到者——任何字最多 0.4s 完成渐入，慢速流不出现「暗等」。档间做 0.1s 平滑插值（阶梯保留复古感，瞬切显卡顿）。
- **实现落点（深挖修正，弃 AnnotatedString span 路线）**：渐变走**绘制层叠加**——前沿段落的 `TextLayoutResult` 已有现成捕获（`MarkdownContent.kt` 段落组件 `onTextLayout`、`ClickableMarkdown.kt` 的 `layoutResultProvider` 暴露模式），`getBoundingBox(offset)` 取尾 4 字形簇各自包围盒，`drawBehind`/overlay 矩形以卡片底色 + (1-α) 绘制覆盖实现视觉减淡。理由：alpha 若烤进 SpanStyle，卡壳推档与档间插值阶段需每帧重建 AnnotatedString + 重排前沿段落——恰是要避开的坑；draw-phase 读动画值（Compose deferred read）**零重组零重排**，动画只失效绘制。链色字/行内代码 chip 在 0.4s 窗内的瞬时观感偏差为已知取舍（视觉等价于朝卡片底色减淡）。
- **可读性自证**：无业界先例，真机验收必查（见 §9 人工清单）。

## 5. 卡壳与完结（Q3/Q7 裁决）

- **卡壳判定**：揭示前沿 ≥300ms 无进展（debug 系统属性可调）**且**仍在流式中。测「揭示前沿」而非 SSE 到达（积压放字时用户看到连续打字，不该触发）。SafePrefixGate 扣留视同卡壳——语义正确：屏幕确实没变化。与传输层 `StreamStallWatchdog`（45s）层级清晰无交互：300ms 表现层停顿→视觉反馈，45s 传输层死连→强杀重连，阈值差 150 倍；真死连在视觉上表现为长时间卡壳态（光标持续闪烁），是正确的可感知信号。
- **卡壳表现**（用户原始设计）：渐变按 0.1s/档往后推（倒一 20→40→60→80→100），窗口全亮后只剩光标闪烁；内容恢复时渐变自然回归。
- **完结 ≠ 卡壳**（Q3 裁决）：turn 正常完结走**快速全亮**（渐变一步/≤0.2s 收敛到 100%），不走 0.4s 排空——完结是正常结束不是卡住。
- **完结后光标**（Q7=c）：**立即移除**（可带 ≤0.3s 淡出防突兀）。业界零例外「流中存在、完成立即移除」（Vercel carets 文档明文）；语义自洽——终端光标常驻是因 shell 还在等输入，AI 消息说完就是说完。

## 6. 光标（Q9/Q10 裁决）

- **形态**：下划线条（终端正典），overlay Box 绘制（换皮 HeldTailReveal 现有呼吸方块），不参与文本排版、不改字体度量、**不动行高**。
- **行尾规则**（Q9=a）：前沿字恰在行末时，光标**独行**换到下一行行首、字留原行（VT100 wraparound 正宗行为）。实现（深挖修正，弃「预留槽位」方案）：前沿段落永远是消息最后一个可见节点，其下方是卡片空白区——光标 overlay 直接**溢出绘制**到 `lastLineBottom` 位置（overlay 不裁剪；`cursorOffsetFromLayout` 纯函数已有 maxWidth 回绕保护先例），**零布局操纵**；后续字符到来时文本自然占用该行（与今天 append 换行完全同路径，高度引擎既有配对覆盖），光标跳回字后。高度只在真实内容增长时增长——**零额外跳动由构造保证**，非槽位预留。**禁止**为「字+光标整体换行」重新引入 inline content（血案红线）。
- **光标所有权**：`STABLE_REVEAL_PILOT` 的呼吸光标（HeldTailReveal）与本动效光标互斥——char-reveal 开启时接管光标（同一 overlay 基建换皮），禁止双光标同屏。
- **忙闲行为**（Q10，Typed.js 惯例）：流式进行中光标**常亮**（跟随前沿移动）；停顿/卡壳才**闪烁**——流中「移动光标+推进渐变」只剩一组动画，闪烁只在安静时出现。
- **闪烁参数**：500ms 显 / 500ms 隐**硬切**（alpha 0/1），协程 `delay` 实现——照抄 Compose `CursorAnimationState` 方案（源码明言避开 Compose Animation API 防 busy-loop）；业界周期区间 0.7–1.2s、占空 ~50%，取平台常量下限。可选无障碍注：系统「移除动画」开启时以常亮代替闪烁。

## 7. 作用范围（Q6 裁决）

- **仅 `Part.Text`**（正文 markdown）；`Part.Reasoning`（thinking，有独立脉冲语言）、工具横幅一律排除。
- **包含**：段落 / 标题 / 列表 / 引用 / 行内代码 / 链接文字。
- **排除**：表格（用户定）；围栏代码块——闸整块扣到闭栏后一次性放行，对「闸整块放行的大块」跳过逐字动画（同时规避语法高亮每字重着色与长代码过慢），与表格同一处理路径，实现成本零。
- **排除项检测（深挖补充，零 gate 改动）**：揭示层在 delta 流上做廉价状态机——围栏：放行文本内 ``` 恒平衡（gate 构造保证），含围栏标记的 delta 从首个 ``` 起立即放行；表格：见表头分隔行（`---|---` 形态）进入表态，空行退出，表态内 delta 立即放行。启发式规则全部单测覆盖（误报面：普通文本含 `|` 不会因分隔行前置条件而误入表态）。

## 8. 开关与调参（Q8 裁决）

- `BuildConfig` flavor 级开关（沿 `STREAMING_MD_PILOT` 先例）：**dev=true / beta=false / stable=false**；回退 = 一行置 false。关闭时渲染路径与现状零差异。
- debug 系统属性调参（沿 `debug.ocbeacon.streamflush` 先例）：揭示基础速率、卡壳阈值等，真机现场调参不重装。

## 9. 验收清单

**仪器可断言（V1–V5，AI 真机验证即收）**：

- 逐字粒度可见（高速录屏逐帧：无多字同帧蹦出；快流下允许到达速率揭示）；
- 渐变阶梯值与推档节奏（像素取色 / 埋点：20/40/60/80、0.1s/档、双驱动上限 0.4s）；
- 卡壳 → 渐变排空 → 光标闪烁转化链；完结 → 快速全亮 → 光标 ≤0.3s 消失；
- 中断 / 退后台 → 积压立即揭示、光标消失；
- 行尾换行：光标独行换行、后续字符填入预留行、**容器高度零跳动**（高度引擎断言）；
- 铁律回归：无闪烁、无贴底震荡、无高度振荡（SSE 滚动稳定性清单逐条）；
- 性能对照：揭示 tick 不引入每帧整段重排（trav 指标不劣化）；**append 频率 ≤40/s**（tick 批量断言）；渐变推档/插值阶段零 AnnotatedString 重建（绘制层失效断言）；开关关闭 = 现状零差异。

**人工清单（V6，需用户验证）**：

- 渐变可读性 / 长文阅读眼疲劳体感（差异化创新无业界先例，主观拍板）；
- 复古终端氛围是否达意（下划线光标 + 打字节奏的整体观感）。

## 10. 明确不做

- 不做 remend 式语法预修复（SafePrefixGate 扣留式已覆盖；两者混用徒增状态机）；
- 不动传输层 / 批处理 / 高度引擎既有规则；
- 不做逐词/逐 token 揭示（CJK 下逐字形簇即正确解，smoothStream 官方承认 word 切分对 CJK 失效）；
- 不引入任何新 UI 依赖库（M3 无公开光标组件，overlay 手搓，成本已证极低）。
