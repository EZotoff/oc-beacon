# #488②③ 实现方案——代码语法高亮 + 真数学渲染（2026-09-30 阶段三调研）

> 状态：**待用户裁决**（两个裁决点见文末）。本批已落地的是 ①块内 HTML 原文呈现
> （custom 钩子覆写）与 ④setext 维持 accepted-gap（卡片原文即备查，无实现需求）。

## ② 代码语法高亮

### 现状
code fence 走库默认 renderer（无高亮，等宽纯文本）；`app/build.gradle.kts:249`
注释明示。mikepenz base/m3 AAR 零高亮组件（二进制 grep 实证）。

### 推荐方案 A：官方 companion 模块
- **依赖**：`com.mikepenz:multiplatform-markdown-renderer-code:0.45.0`（与当前
  核心库同版本线，maven 实证存在）——它携带高亮版 code 组件（codeFence/codeBlock
  覆写装配）
- **引擎**：`dev.snipme:highlights`（纯 Kotlin KMM 语法分析引擎，**非 UI 库**——
  不触碰「禁引入额外 UI 依赖库」红线；最新 1.1.0）
- **语言面（17）**：C/C++/Dart/Java/Kotlin/Rust/C#/CoffeeScript/JS/Perl/Python/
  Ruby/Shell/Swift/TypeScript/Go/PHP——覆盖 AI 编程对话主流语言；未覆盖语言
  回退纯文本（引擎按语言枚举分派，缺省无高亮不报错）
- **特性契合**：主题（可映射 M3 令牌）、结果缓存+**增量变更支持**（契合流式
  48ms 批 append 语义）、sync/async 双模式（长代码块走 async 防主线程卡顿）

### 接线预估（方案 A 落地时）
- gradle 加两依赖（code-android + highlights 经传递）
- MarkdownContent components 的 codeFence/codeBlock 当前未覆写（走库默认）——
  companion 提供 `codeComponents(...)` 工厂或直接用其高亮组件替换这两个钩子；
  codeBlockBg/codeBlockFg 现有配色传入
- **风险点**（接线批要防）：
  1. 流式 snapshot 失配（#437 崩溃先例）：companion 组件的 AST 区间读取需
     逐帧核对，必要时套同款越界 clamp
  2. 流式增量高亮与 #487 围栏行级放行的交互：放行粒度变细后高亮缓存命中
     率——highlights 增量 API 面向行编辑模型，流式 append 需实测
  3. 依赖体积：highlights ~几百 KB 级（纯 Kotlin，无原生部分）

### 备选 B：自写轻量 lexer
零依赖、完全可控；但 17 语言的关键词表/字符串规则维护成本高，且质量难敌
highlights（其含 C 系/函数式语言的分析器）。**仅当裁决否掉新依赖时**收窄到
top-5 语言（kotlin/python/js/go/shell）手写。

## ③ 真数学渲染

### 现状
`transformMathFallback`（MarkdownMathFallback.kt）把 `$$…$`/`\[…\]`/`\(\…\)`
提取为 code span/围栏文本降级；`\begin{}` 无变换字面流。

### 候选评估
| 方案 | 评估 |
|------|------|
| a) WebView + KaTeX | 每公式一个 WebView：重量级、与 Compose 混排的高度/滚动/触摸链路割裂（嵌套滚动手势域 #474 教训），批渲染性能差——**不推荐** |
| b) 纯 Compose 数学排版库 | 生态无成熟 KaTeX 级库（KMM 侧尤甚）——**现在不接**，留观察 |
| c) **维持降级 + 轻量着色/标注升级（推荐）** | 现状结构不变；两小步：①降级 span 若接了 ②highlights 可给伪 `tex` 着色（无该语言则跳过）或手写 \命令/花括号/上下标的轻着色；②降级块加「公式」标签徽标，明示这是数学降级呈现（用户可感知局限） |

方案 c 零新依赖、零渲染架构变更；真排版等 mikepenz 官方 math 模块或 KMM
生态成熟（卡片保持远期开放）。

## 裁决点（开工前需用户拍板）
1. **②走方案 A（新增 renderer-code + highlights 两依赖）还是方案 B（自写 top-5 lexer 零依赖）？**
   推荐 A（官方同源、17 语言、增量缓存）。
2. **③确认走方案 c（降级+着色/标注升级）？** 还是维持现状不动（连标注也不加）？
