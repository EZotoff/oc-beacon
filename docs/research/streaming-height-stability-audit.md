# SSE 流式期间高度稳定性系统性调研(2026-09-30)

> 触发:用户复验 #463/#466 通过后报告「SSE 输出时似乎高度会发生变化」,
> 要求系统性检查各卡片/分割线各状态与正文 markdown 块是否引起高度变化。
> 方法:三维度静态审计(卡片状态机/Markdown 块/高度引擎配对覆盖域)
> + 引擎域人工复核,全部结论有代码依据;动态取证待复现窗。

## 一、结论速览

1. **卡片自身状态切换几乎零高度**——ReasoningBlock 思考中→完毕、工具卡
   Running→Completed(折叠态)、EventCard failed、尾部统计栏切换(28dp 地板)
   全部是同槽等高替换,高度维度可排除。
2. **可感知高度变化来自四族**:流式表格族(P0,最重)、硬切首现族、
   收缩无配对缺口、完结瞬间族。全部为无动画硬切(无 animateContentSize)。
3. **引擎配对覆盖增长不覆盖收缩**:帽轨(流式 item)收缩不回改=空白残留;
   ledger 轨(压缩卡/工具横幅)收缩只 rebase=视口自由落。

## 二、流式进行中的高度变化源(按可感知度降序)

### P0 表格族(流式表格期间高频发生)
| 源 | 机制 | 方向 | 依据 |
|---|---|---|---|
| 列宽随行流入重排 | MeasureCache/rows 键含**整条消息全文**,每 append 失效重建;新宽单元格改变全表列宽→已上屏行重换行 | 双向 | MarkdownTable.kt:189,361-414 |
| >20 行 stagedLimit 每 append 重置 | remember(content,tableNode){1} 键每拍失效→塌回 8 行再逐帧重建 | 剧烈回缩再跳回 | MarkdownTable.kt:101,226-232,765-770 |
| 两拍收敛 containerWidth | 首拍 cap=120dp 夹窄,次拍放宽 | 通常回缩 | MarkdownTable.kt:250,258,390-414 |

### P1 结构升格/互换族(特定内容触发)
| 源 | 机制 | 方向 | 依据 |
|---|---|---|---|
| Setext 升格 | 文字行下紧贴 `---`→前行升 H2;gate 自认缺口 | 增长 | SafePrefixGate.kt:29,144-150 |
| tight→loose 列表 | 项间空行毕业后整段合 loose→已上屏项加 padding | 增长 | SafePrefixGate.kt:58-70;MarkdownContent.kt:552-558 |
| 内嵌 question 检测翻转 | Markdown 正文↔折叠提问卡整树互换(单调一次) | 内容相关最大 | PartContent.kt:142-143 |
| CRLF 表格不识别 | gate 不剥 \r→整表扣到 EOF,完结一次性成表 | 完结大额增长 | SafePrefixGate.kt:347,406-409 |

### P2 硬切首现族(每次新内容块,布局事实)
新工具卡/question 完成卡/Agent 行首现(固定高+8dp 间距)、**StepDivider +9dp/新 step**
(MessageCardAssistant.kt:1336-1343)、产出行(仅完结)。视口由帽轨配对(稳定),
但内容从无到有=布局内跳变,硬切无动画。流式观感的组成部分,是否算「问题」待用户裁决。

### P3 引擎配对缺口(收缩域,主会话复核)
| 源 | 机制 | 视觉 | 依据 |
|---|---|---|---|
| 帽收缩不回改 | reserveReleasePlan 对 trueHeight<=reserved 返回 null,帽单调只增 | 流式内容回缩(表格列放宽/setext 前的重排)时 item 保持旧高=**空白残留**,直到换流式项 reset | ScrollCompensation.kt:338,279-288 |
| ledger 收缩不配对 | note 对 d<0 只 rebase | 压缩卡/工具横幅回缩→上方内容下坠 Δ(无补偿) | ScrollCompensation.kt:157 |

## 三、完结瞬间的高度变化源(一次性)
1. **StepGroup 平铺↔折叠整树互换**(多消息 turn,数千 px 级;探针注释自认
   「结构性高度跳变源」;暖账本逐像素兜底,冷账本 24dp 桩帧)
   ——MessageCardAssistant.kt:428-479,1425-1447
2. **>2048 字符 Loading 塌缩**(pilot dispose→async Loading≈0 高→Success 全高,
   #428 同族 +268px)——MarkdownContent.kt:603-715
3. 完结归一化重排(数学/任务列表标记/长段空行化变换只发生在完结)
   ——MarkdownMathFallback.kt;NormalizeTaskListMarkers.kt
4. 表格 containerWidth 复 0 两拍收敛重演 + 尾部/产出行挂载

## 四、点名问题直答(用户原问)
- 卡片各状态:**折叠态切换零高度**,可排除
- 分割线:+9dp 固定/新 step,硬切,帽配对内
- markdown 块:未闭合代码块**无**段落↔代码翻转(解析器即时成块+gate 零输出);
  表格/列表/setext 为真实抖动源;代码高亮不适用(库无高亮)

## 五、修复方向建议(按收益,未实施)
1. 表格键改造:stagedLimit/MeasureCache/NaturalWidths 键去全文 content
   (改表格自身文本/node 深比较)——消灭 P0-2/P0-3
2. containerWidth 首拍 BoxWithConstraints 内联宽——消灭两拍
3. 收缩配对:帽允许收敛(trueHeight<reserved 时同步缩帽,视口同步)——
   需高度引擎域设计,勿破坏「已上屏永不回改」的既有裁决语义(需专项)
4. 完结 >2048 Loading 帧:pilot 终帧同步换入/缓存预热
5. setext:文字行后扣一行待判定

## 六、动态取证待办
复现窗:用户观察时记录(a)内容类型(表格/列表/纯文本)(b)构型(贴底/读历史)
(c)视口动还是内容动;探针 tag:VTRACE/RESERVE/SGR-435/ScrollDiag/CONTENT-BLINK。
