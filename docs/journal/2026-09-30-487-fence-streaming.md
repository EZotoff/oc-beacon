# 487-fence-streaming（2026-09-30）

> 状态：进行中
> 关联：（spec 路径，若有）·（issue 编号，若有）
> 来源：用户反馈 / grilling / E2E / 顺带发现

<!-- 过程中的取证/验证证据直接写本文件；backlog.md 只留 ≤3 行卡片。 -->

## E2E 真机终判（2026-09-30 21:12，houji v2 longcat，代码块轮）

- 发送「Write a Kotlin file about 50 lines」→ 50s 流式窗口仪器判读：MDPilot 43 批全量放行（每批 gate release=N **held=0**、releasedTotal==snapshotTotal——旧语义下代码块期 held 会累积数百字符）；MDResize 高度序列 96→3108 **严格单调**（连续 d=60 增量=一行代码一行高地流出，正是行级放行的视觉形态）
- 完结换装干净：A11yDiag path=render src=syncSmall len=260 Success → path=preParsed chunked=false len=1207 Success（<2048 同步终态，Loading=0）；RESETKEY=0 / nonPrefix=0 / FATAL=0；HFLICK 4 条均轮界正常增删；d=- 扫描命中全为 MIUI 输入事件（deviceId=-1），高度域零负向
- 结论：#487 流式代码块行级放行真机验证通过——代码像正文一样逐行流式输出，等待用户视觉验收

## 闭合符号构造深度普查（2026-09-30，用户指令「都得加上」逐类核查）

- **围栏代码块**：✅ 唯一合格者，#487 已做。合格条件四要素齐备：闭合符号 ∧ 高度完美单调（字面追加零回流）∧ 渲染可见 ∧ 无归属歧义
- **数学块 $$…$$ / \[…\]**：❌ 结构性排除——appendBlockMath 闭合时改写（```tex 重排 + inner.trim() + 行中定界符补换行 MarkdownMathFallback.kt:151），已放行字面量与闭合形态无前缀关系（如 `a $$x` vs `a\n\`\`\`tex\nx\n\`\`\`）；「开时改写统一」设想被同一行补换行规则击穿。gate 扣留是正确设计非欠账
- **HTML 块**：⚠️ 排除但揭示显示缺口——解析侧与围栏同构（探针：未闭合 <div>=单一 HTML_BLOCK 延伸 EOF、增长结构稳定、空行终止 type-6）；渲染侧 mikepenz v0.45.0 基础+m3 AAR 全量二进制 grep 零 HTML 处理 → 块内 HTML 渲染为空，流式放行与否不可见。整消息 HTML 已有预览通道（looksLikeHtmlPayload→ChatScreen/ErrorPayloadContent），块内混排 HTML 隐形 → 登记 markdown 能力补齐批次
- **缩进代码块（4空格）**：❌ 歧义致命——4 空格行可能是列表懒延续（段落续行），gate 无法局部判定归属（需列表上下文状态机）；模型输出以围栏为主，保守扣留承重
- **setext 标题**：❌ 升级翻转已放行行排版（非单调），维持 accepted-gap（spec §3.6 stage-2 备查）
- **LaTeX 环境 \begin{align}**：无变换→字面文本流（反斜杠硬停造成空行量化），属「真数学渲染」能力域非流式粒度域
- **表格/列表/引用/任务项/行内构造**：✅ 均已流式（#441/#472/#471④）
- 结论：合格集={围栏}，无漏网之鱼；#487 即该类的完备实现

## 已完结卡片迁入（2026-09-30）

### **#487 流式代码块行级放行——开放围栏零输出改行级/token级（#443 家族后继，用户设计裁决 B）** `streaming`
  - 用户提案经拷问收敛（2026-09-30）：代码块属完美稳定增长块（字面追加零回流），不需要整块等闭合——探针实证 intellij-markdown 对未闭合围栏按 CommonMark 延伸至 EOF 自动闭合（4/4 PASS），合成闭栏冗余且字面实现破前缀不变量（#471③ 雷）已弃
  - 设计：gate 开放围栏行级放行——开行完整即放（用户裁决B：实现简单优先，接受空盒）/内部完整行整放/未完行纯文字增量/闭栏形完整行（同字符run≥开行长+仅尾空白）即放即闭；无块内光标；数学/HTML 不碰
  - 验收：TDD（gate 分支+围栏嵌套+性质测试扩）+真机 E2E 长代码块 MDResize 全单调零 RESETKEY/nonPrefix
  - 2026-09-30 实现+E2E 完结转待验证：SafePrefixGate 围栏分支重写（开行完整即放/内部整行/未完行纯文字增量/闭栏形 run≥开行长即放即闭）+fenceOpenAt 描述符（修长度盲配 latent bug）；TDD 8 新例+单调性质扩例，全量 3750 绿；真机 E2E 长代码块流式 gate release=N held=0 全批守恒、MDResize 96→3108 严格单调、零 RESETKEY/nonPrefix/FATAL、完结 preParsed Success——剩用户真机观感验收（dev 包已带最新构建，测试会话有现成代码块轮次）
  - 迁入依据：2026-09-30 用户真机观感验收通过（『我看了没什么问题』）（backlog.sh migrate 2026-09-30）
