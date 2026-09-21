# 2026-09-21 流式 Markdown 渲染崩溃取证与防护（STREAMING_MD_PILOT 暂停）

## 现象

dev 构建（0.3.0 / code 1789770597）单日 3 次进程崩溃，用户提交两份 diagnostics
（12:08 / 12:18 / 18:54 各一次）。栈完全一致：

```
java.lang.StringIndexOutOfBoundsException: begin 0, end 26|58|2, length 0
  at org.intellij.markdown.ast.ASTUtilKt.getTextInNode(ASTUtil.kt:18)
  at com.mikepenz.markdown.utils.ExtensionsKt.getUnescapedTextInNode(Extensions.kt:107)
  at com.mikepenz.markdown.annotator.AnnotatedStringKtxKt.buildMarkdownAnnotatedString(:323/:248/:117)
  at ...ClickableMarkdownKt.buildClickableMarkdown(ClickableMarkdown.kt:87)
  at ...MarkdownContentKt.MarkdownContent_...$lambda$3$2(MarkdownContent.kt:466)   ← paragraph 组件
  thread=main
```

崩溃均发生在 MDPilot append 后 ≤1.2s（流式打字期）。

## 取证链

- 崩溃形态 = 模型对失配：`MarkdownComponentModel(content=String, node=ASTNode)` 中
  content 快照长度 0，节点区间非空（[0,26]/[0,58]/[0,2]）。`getTextInNode` 对
  `content.subSequence(start, end)` 无界检查。
- 栈行号与 mikepenz **0.45.0** tag 源码逐行吻合（Extensions.kt:107 等），确认二进制。
  `org.jetbrains:markdown` 解析为 **0.7.9**（gradle cache 实证；上游 9 月已发至
  0.7.14，连续修复 streaming parser 边界缺陷）。
- 渲染管线：SSE 48ms flush → StreamingMarkdownPilot 前缀差分 append →
  `StreamingMarkdownStateImpl`（活 StringBuilder + StreamingMarkdownFile 稳定/尾部分析）
  → `Markdown(streamingMarkdownState)` → `MarkdownElementInternal` 的
  `remember(node, content, typography) { MarkdownComponentModel(content.toString(), …) }`。
- 库缓存先验不健全：content 键为同一 StringBuilder 实例（identity equals，永不失效），
  模型为冻结快照——正确性完全依赖「offset 变化 ⇒ 节点新实例」。至构成 stale 渲染
  风险；("", node) 对的精确产生路径未能静态导出。
- 静态审计：app 侧全部构造点（preParse/chunkSuccessSlot/AsyncMarkdownStateImpl）均产出
  一致模型对；`markdownStateOverride` 为死参数。
- 实证 fuzz：对 0.7.9 原 JAR 以随机分片（1-13ch/步 × 300 试次 × 3 文档：标题/表格/
  代码/列表/emoji 代理对）+ 库缓存语义模拟（identity map → 首见快照）压测——
  **0 违例**。解析器 + append-only 状态机内部自洽。
- 残余嫌疑域（无法静态证明）：Compose 槽位复用 / 列表预组合与试点 `key(resetKey)`
  状态置换的交互（本仓 2026-08-27 GapComposer 崩溃同族先例）。

## 处置（本批落地）

1. **防护**：`markdownNodeInBounds`（递归校验节点区间 ⊆ [0, content.length]）+ 
   `buildClickableMarkdown` 入口双重防御（预检 + SIOOBE 兜底 catch），越界降级纯
   文本渲染并输出 **MDGuard** 取证日志（content 长度/节点类型/区间/子节点数）。
   heading1 路径（h1Text）同款防御。单测 `MarkdownBoundsGuardTest`（6 例，
   含 content=""+非空树、后代越界、代理对）。
2. **止血**：dev flavor `STREAMING_MD_PILOT=false`（#265 spec 预设回退路径）。
   崩溃路径即刻关闭；MDGuard 日志在遗留路径（完结态/历史消息）继续取证。
3. **后续**：MDGuard 日志定位真实来源后评估重开试点；届时同步升
   `org.jetbrains:markdown` ≥0.7.14（5 个 streaming 修复）并向 mikepenz 上报
   `remember(node, content, typography)` 活 StringBuilder 缓存问题。

## 验证

- `:app:compileDevDebugKotlin` 通过；`MarkdownBoundsGuardTest` 6/6 绿。
- 未验证真机：防护行为依赖运行时失配触发（MDGuard 日志为准）。
