# OC Beacon — 需求与问题总览

本文档是唯一的**未决工作项清单**：只保留尚未完结的需求与问题卡片。条目完结（用户验收 `[x]`）后**当场迁出**——记录连同证据移入 `docs/journal/` 对应批次文件，本文件不保留完结记录；历史查询走 journal 与 git。

**卡片格式**：标题（含全局编号）+ Tag + 状态 checkbox + **≤3 行**摘要 + 链接。需求全文、实现要点、验证证据一律写在链接目标（spec / journal）中，不内联。登记新批次用 `./scripts/backlog-new-batch.sh "<批次名>"`（自动建 journal 文件）；改动后跑 `./scripts/backlog-check.sh` 校验机械不变量。**放置规则（check 脚本强制）**：卡片一律写在下方对应 **Pn 节内**（按优先级定义归位；一节内新卡置顶）；头部编号行与优先级定义表之间**不放任何卡片**（仅允许编号勘误等注释）。**P4 格式增补**：P4 卡必含「**前提**：…」行——说清实现前提是什么、当前为何不可实现（外部硬阻碍所在）。**术语句**：卡片标题与摘要用词遵循 [CONTEXT.md](CONTEXT.md) 术语表（堆积消息/子智能体/轮次/撤销/中断…）；「待处理」保留给权限/问题（状态词待验证/待办/待裁决不受影响）；Tag 英文与 #N 编号不受中文术语约束；API 英文原词（cursor/fork）合法，_Avoid_ 仅限中文对应词。

**编号**：全局递增，不回收。下一编号：**#537**（2026-10-09 #536 spill 提示行降级实现——上游固定拼写文案匹）。

**操作纪律（2026-09-09 用户定规，账本事故后）**：卡片区**禁止手工直编**——登记/明细追加/状态流转/完结迁移一律经 `./scripts/backlog.sh`（add/note/status/migrate；真实 backlog 变更后自动跑 check）；journal 新节追加用 `backlog.sh journal append`（append-only）或编辑工具定位插入，**禁止全量覆写重写 journal**（2026-09-09 演示批覆写丢章事故定规）。**裁决优先级（2026-09-09 用户定规）**：同一问题域存在多项历史裁决时**以最新裁决为准**；新裁决落地时须回写旧裁决域卡片的注记（#350 为先例）。**反馈归卡（2026-09-12 用户定规）**：用户对某张卡片的反馈/裁决一律经 `backlog.sh note <N>` 记入**该卡片**明细，**不另开新卡**承载反馈；仅当反馈引出**新的独立缺陷**时才另立卡片，并在两卡明细互相引用（#401→#408 为先例）。**工作流脚本类直接修（2026-09-29 用户定规）**：项目工作流/脚本层的修复（`scripts/` 流程脚本等不进 APK 的项目设施）**不立卡**——发现即直接修+自测，证据记入当批 journal；#480 为末代先例（已立卡的按原流程走完迁移）。

> 编号勘误（2026-08-23 合并时）：terminology 分支先行占用的 #194–#199 与主工作区 #194（FAB）撞号，合并时 terminology 侧六卡顺移 +5 → #200–#205；文档内旧引用已同步改。

**优先级定义**：

| 等级 | 含义 | 示例 |
|------|------|------|
| **P0** | 影响主要流程体验或核心业务场景的 bug | 聊天页面崩溃、SSE 断连无法恢复 |
| **P1** | 主要业务流程的需求功能点 | 会话搜索、消息转发 |
| **P2** | 优化专项、锦上添花功能、不影响体验的小 bug | 动画微调、文案优化 |
| **P3** | 观察项 / 依赖外部条件的低价值改进 | 偶发自愈的异常观察、环境因素类缓解 |
| **P4** | **外部前提阻塞**：功能/工作方向明确，但实现前提在 app 之外（服务器能力缺失 / 上游未合 / 用户流程门槛），前提满足前不可动工——**卡内必含「前提」行**（前提是什么、现在为何做不了）；前提变化时重验归位 | 服务器未暴露的事件聚合、上游 PR 候选清单 |

**修复方针**（2026-09-03 用户定规）：bug 类条目**根因修复优先**——交付的修复必须消除触发链的根因层（架构 / 生命周期 / 状态机 / 协议缺陷），不以表象层兜底单独交差（「缓存兜底显示」「重试遮罩」「吞异常」等手段不得作为修复本体）。兜底类缓解仅在同时满足以下条件时接受：①对应根因修复卡已登记并被引用；②兜底卡摘要显式标注「过渡措施」并关联根因卡编号。分层不明确或拿不准时，先向用户呈现根因分析与分层方案，裁决后再落卡/动工。

**验证方针**（2026-09-03 用户定规）：**绝大多数情况（含 UIUX/交互类改动）都应通过真机端到端测试验证并关闭**，`[~]` 不按「是否 UIUX」划分，按「是否存在可注入/可观测的自动化仪器」划分。可用仪器（持续积累）：uiautomator dump/tap（导航+断言，注意 LazyColumn 视口冻结——dump 前先滚顶）、logcat/Room 日志直查、`/proc/net` socket 观测、debug 注入工具（#305 `--ez debug_simulate_timeout` 先例）、curl 模拟服务器侧事件（POST 建会话/订阅 SSE 抓帧）、网络扰动（nc 黑洞 + `adb reverse` 重定向 + `adb kill-server` 断既有连接）、`pm install` 静默装包 + `debug-entry.sh` 确定起点。**仅以下情形才需要人工介入**：①真手指连续手势/体感类——注入手势是平台批处理伪影，无法模拟真手指位移流（#245 两轮证伪）；②需用户凭据/跨设备操作（GitHub 密码/2FA、扫码等）；③数日级真实使用观察（不可加速，如挂机断链观察）；④主观体验拍板类（「好不好用」的最终确认）。判定次序：先穷举仪器→构造红回路→E2E 验证关闭；真找不到仪器才登记人工项并写明卡内为何仪器不可行。

**状态流转**：代码写好但未验证不等于完成！要求完成需求、自行验证、用户验收通过之后才算完结；完结即迁移（见首段）。

| 状态 | checkbox | 含义与流转规则 |
|------|----------|------|
| **进行中** | `[ ]` | 需求已登记或正在开发。开发完成后跑通自动化验证（编译/单测/i18n/assemble）并自行完成可覆盖的验证后 → 转「待验证」 |
| **待验证** | `[~]` | 代码完成、自动化验证通过，但**用户人工/真机验收未完成**。后续 Agent 看到 `[~]`：向用户给出验证清单并请其执行；通过 → 转「已完成」并**当场迁移**；发现问题 → 改回 `[ ]` 进入修复 |
| **已完成** | `[x]` | 仅迁移瞬间存在的过渡态——迁移完成后本文件不含任何 `[x]` 顶层条目（check 脚本强制） |

**Tag 标签体系**：标记相关领域便于批量排查；现有 Tag 不足以描述则新增。

| Tag | 说明 |
|-----|------|
| `crash` | 崩溃 / 闪退 |
| `ui` | 界面显示、组件缺失、布局问题 |
| `data` | 数据展示不准确、数据源疑问 |
| `sse` | SSE 连接、事件推送相关 |
| `session` | 会话管理相关 |
| `permission` | 权限请求、审批相关 |
| `security` | 安全与隐私（明文凭据、泄漏、合规） |
| `refactor` | 重构、死代码清理、分层修复 |

**Spec**：满足「有非显然取舍需留档」或「跨会话实现需完整上下文」其一 → 在 `docs/specs/` 写 `YYYY-MM-DD-<名称>-design.md`（spec 是权威，卡片只留摘要+链接）；实现并用户验收后移入 `docs/archive/specs/`，同步更新 spec 头部状态行与卡片引用路径。**归档 spec 定期清理零外部引用者**（git history 永久可找回）。简单需求不写 spec。

**Journal**：每个工作批次一个 `docs/journal/YYYY-MM-DD-<英文kebab名>.md`，**开工时创建**，过程中取证/验证证据直接写入 journal（卡片全程保持 ≤3 行）；完结条目当场迁入，原文保留不压缩不删改。可复用的蒸馏结论提炼进 `docs/research/`，journal 只记执行与证据。**新 journal 术语三原则**：①叙述段用 CONTEXT.md 规范名；②证据引用豁免（logcat 行、SSE 事件名、i18n key、标识符原样保留）；③规范名首现带英文原词，编号遵循 [numbering-charter](docs/numbering-charter.md)。

---

## P0 — 主流程阻塞

## P1 — 核心功能需求


## P2 — 优化与锦上添花

- [ ] **#536 spill 提示行降级实现——上游固定拼写文案匹配（#332 裁决降级）** `dsh` `sse` `ui`
  - 参照上游 dsh-spill-policy 固定拼写（hasSpillNotice/formatSpillNotice，notice.d.ts/notice.js 浏览器安全零依赖）做溢出提示行文案匹配渲染，不自造正则
  - 用户裁决 2026-10-09：实现处须详细注释——约定来源+上游自认不可认证性（Known Limitations 首条）——防后人误当结构化契约
  - 排程 B1.3（与 #352/#288 同 DSH 域批）；上游结构化信号落地后切换，长期观察锚=#332；证据 docs/research/2026-10-09-p4-upstream-preconditions-check.md §②

- [ ] **#535 P3 观察卡狩猎批——soak 长时测试主动定罪（#486/#464/#454/#477）** `test` `soak`
  - 2026-10-09 用户裁决改裁：四观察卡从「等复现」转 soak 长时主动狩猎。仪器腿=#486 确定性用例判死活（Fake 层构造断连+滚出视口+重连三联；构造出塌缩→顺势 LRU 账本根修，scroll 域编辑协议）+#454 单元红用例
  - soak 腿（夜跑）=#464 tap 效果断言+失配现场抓取（dumpsys input/logcat/meminfo；dump+截图双通道防 #477 干扰）+#477 盲 dump 检测器（ground truth=mock 语料/Room 直查）+#454 换行注入循环
  - 设计与成本：docs/research/2026-10-09-p3-watchcards-testability.md（全复用 driver/mockllm/androidTest fakes；soak 基线 201 轮 PASS 红线不得破坏；批2 顺带白捡 #488 CodeHL 量化数据）

- [ ] **#288 workflow 阶段卡（tool-workflow agent-start/end 聚合渲染）** `dsh` `ui`
  - **前提**：dsh 服务器在任何客户端面暴露 tool-workflow 运行事件——events.mux 实况 / session.history journal / session.projection / session/jobs 四面实测皆无（Web 端 workflow 树为 client-ui 本地组件，同一事件源）；服务器升级暴露后重验再启聚合器
  - Task 3b 落地 workflow run-start/run-end 降级单卡（同 runId 原位更新 running→终态）；agent-start/end 维持 Ignored（防逐成员刷卡）
  - 方向：run 级聚合器（成员 label/outcome/phase 折叠进阶段卡，参照官方 tool-workflow 装配）；验证=真机 workflow 运行会话卡片分阶段展示
  - **2026-09-01 活体四面包夹（走查 #9 定性）**：events.mux 实况帧（两次 WS tap + 现跑 workflow 对照，仅 tool/code-dispatch* 渲染伴生）、session.history journal（39 页全翻 0 行，fresh run 亦不入）、session/projection（仅 permissions）、session/jobs（仅 bash 后台任务）四面皆无 → app 侧映射链（DshEventMapper:469 + DshMessageAssembler）为休眠代码路径，非缺陷；走查期「18 事件在 a6c4」不复现（疑当时另有来源/版本窗口）。重开丢卡=结构性（无服务器数据源），DSH synthetic 消息零持久化同因
  - **2026-09-02 复验（差距调研独立交叉确认）**：`docs/research/dsh-gap-2026-09-01/` 四路证据（fe 源码/Android 清点/Web 实测/服务端 api-gap）再次确认服务器事件面无 tool-workflow 运行事件——门维持关闭；聚合器设计参照 fe-inventory §2.17 client-ui-workflow-run
  - 2026-09-29 前提重验:0.1.7-rc.2 dist 仍无 tool-workflow 运行事件——前提不变,维持 P4
  - 2026-10-09 前提满足解封（P4→P2）：现网 0.2.0-rc.2 运行安装内发射代码齐备（dsh-tool-workflow lib/index.js:98/109/117/124 四事件 append，顶层调用默认记录无开关）+ session/follow 流原始事件帧（SessionEventEntry）透传在。149 journal（138M/7 工作区）+演示容器 10 journal 全解码 0 真实样本=顶层 workflow 从未跑过（宽松命中 5 处全为开发会话 grep DshEventMapper.kt 假阳性）——非版本缺口。2026-09-01「四面皆无」系当时 live 版本旧于 0.1.2-alpha.2（npm bisect：发射代码 08-30 起）。开工第一步=现网真跑一次顶层 workflow 冒烟取样本（follow/journal grep tool-workflow/）；聚合器消费 journal 原始事件帧非消息投影。证据：docs/research/2026-10-09-p4-upstream-preconditions-check.md
  - 2026-10-09 排程裁决：B1 第 2 项（B1.2）；第一步现网顶层 workflow 冒烟为关卡——不过则退回 P4 登记新证据（止损门）
  - 2026-10-09 冒烟关卡 PASS（B1.2 前置达成）：现网 headless 真跑 workflow 取到完整四事件样本（journal 解码实证，载荷事实与样本路径见 journal 2026-10-09-b1 批）——聚合器开工，agent-start/end 关联键=(runId,seq)，end 序与 start 序无关须容忍乱序回填

## P3 — 观察与低价值改进

- [ ] **#522 B案升 stable 后余留装备清扫——JankHoldGate A/B 开关 + STREAM_DELTA_BUS 旗标退役（#499 余留范围）** `chore` `refactor`
  - 前置：STREAM_SHARD_PILOT / STREAM_DELTA_BUS 升 stable 并观察一批——当前 stable 均 false，JankHold 冻结链在 stable 仍是承重活体（B案开=旁路），现退役会回归 stable 流式滚动组合风暴。
  - 清扫面：JankHoldGate system-prop A/B、ChatScreen jkHold 冻结链（含 B3 埋点）、STREAM_DELTA_BUS 旗标与旁路分支；probe 按 keep-probes 裁决保留。#499 于 2026-10-06 完成范围内部迁移，余留拆本卡。
  - 2026-10-06 承接评估：前置未达维持——stable 双旗标（STREAM_SHARD_PILOT/STREAM_DELTA_BUS）仍 false（build.gradle.kts stable 块），JankHold 冻结链在 stable 仍是承重活体，现清扫=回归 stable 流式滚动组合风暴。beta 侧两旗标已升 true（2026-10-06 裁决），观察窗进行中；待 stable 随批提升后再开工本卡。
  - 2026-10-09 R1 排程裁决 ok：stable 随批发版带双旗标（STREAM_SHARD_PILOT/STREAM_DELTA_BUS，build.gradle.kts stable 块现值 false 实证）提升→stable soak 观察一批→B4 清扫本卡；若 B2 狩猎批修了 #486（同 scroll 域）则 B4 必须隔批串行

- [ ] **#488 markdown 能力补齐批次：块内HTML隐形+代码语法高亮+真数学渲染+setext stage-2（远期清单正式立卡）** `markdown` `render`
  - 2026-09-30 #487 后续深调定罪三项能力缺口：①块内 HTML 渲染为空（mikepenz v0.45.0 基础+m3 AAR 零 HTML 组件，二进制 grep 实证；整消息 HTML 走 looksLikeHtmlPayload 预览通道但混排块隐形）②代码块无语法高亮（走库默认 code renderer，build.gradle.kts:249 注释明示）③\begin/\[/712321 数学均降级为文本或围栏（transformMathFallback 文本降级 + \begin 无变换字面流）
  - setext stage-2（升格行级定案）维持 accepted-gap 备查（spec 2026-09-30-471-3 §3.6）；此前散落 handoff 未立卡，2026-09-30 正式收编
  - 实现方向：覆写 markdownComponents html 钩子（原文呈现或接预览同款）；高亮评估 league/ prism 类纯 Kotlin 方案（M3 禁引入额外 UI 依赖库红线内评估）；真数学需渲染层 KaTeX 级能力或维持降级+标注
  - 2026-09-30 代码高亮子项可行性调研完结（docs/research/2026-09-30-code-syntax-highlighting-feasibility.md）：有条件可行，推荐引入 mikepenz 官方同族 multiplatform-markdown-renderer-code:0.45.0——版本零升级（pin 的 0.45.0 即最新，-code POM 与现状逐项一致）、零新第三方实体（语法引擎 dev.snipme:highlights 1.1.0 已因 FileViewer 在 APK，R8 keep 就位）、净增 17KB；fence 语言 v0.45.0 已解析只差消费（markdownComponents codeFence 正门接入，「league/prism 类纯 Kotlin 方案」评估答案即此库）
  - 落地条件与风险：user 气泡豁免（primary 背景预设色板不可读）+自建 M3 令牌 SyntaxTheme（键控动态色/AMOLED）+span 区间防御（官方 issue #415 反向区间崩溃先例，移植 HighlightBuilder 写法）；流式期大块高亮作业整块重启→快速流下停留纯色到 EOF（降级优雅不卡主线程）+完结换装一次纯色→着色 pop（#472 语义需评估）；json/yaml/html/sql 不在引擎语言表→静默纯色（不劣于现状）
  - 2026-09-30 影响面分析完结（docs/research/2026-09-30-code-syntax-highlighting-impact-analysis.md）：路线修正——不引 -code 依赖（span 防御/M3 主题/打点三处定制全落其 v0.45.0 private 区，可见性直证），改仓库内自建组件（core public API MarkdownCodeFence + 已在依赖树的 highlights，~120 行 fork + CodeSyntaxTheme 映射纯函数 ~50 行）→ 零新依赖，UI 依赖禁令裁决面消失；user 气泡豁免是伪问题（用户消息走纯 Text 不进 MarkdownContent，PartContent.kt:144-154）
  - 影响面总评：小切口多波及——必改 2 文件（build.gradle.kts 注释 + MarkdownContent.kt 三点位 ~40 行）+ 新增 2-3 文件 + 测试 2（~120 行），但 components 单例使高亮一次性波及 8 调用面（assistant 双路径/Reasoning 流式/工具卡×2/通知卡/压缩卡/预览对话框），与流式重启节律/高度引擎 Bold advance/主题 remember 键三套承重机制交叉；实施三批（S/S-M/M），批 1 真机判据复用 #487「50 行 Kotlin」高度单调性；顺带发现 FileViewer HighlightBuilder.kt:39 end+1 多染一字符存量偏差（#489 另立）
  - 2026-09-30 阶段三处置：①块内 HTML 原文呈现已落地（custom 钩子覆写+真机 E2E 实证原文可见）④setext 维持 accepted-gap（卡原文语义，技术论证入 journal）②③方案文档 docs/research/2026-09-30-488-syntax-math-options.md——两裁决点待用户：②高亮走官方 renderer-code+highlights（新增两依赖）还是自写 top-5 lexer；③数学走降级+着色/标注升级还是维持现状
  - 2026-09-30 ①块内 HTML 原文呈现用户验收通过（模块 C 演示『这算是对代码块的修复吧…没啥问题』——效果确认：HTML 内容以代码块样式展示原文，修复前整块隐形）；①就此收口，卡整体待 ②③两裁决点拍板后一并收尾
  - 2026-09-30 用户裁决（②③双双拍板）：②代码高亮走仓库自建渲染壳——零新依赖（core 公共 API MarkdownCodeFence + 已在依赖树的 highlights 引擎，~170 行自建 + CodeSyntaxTheme M3 映射纯函数）；官方 -code 库因三处必需定制（span 越界防御/M3 令牌主题/流式观测打点）全落 v0.45.0 private 区被否，澄清要点=两路线同一引擎只差渲染壳
  - ③数学渲染走方案 c：降级+标注升级（「公式」徽标明示局限 + 可选 \命令/花括号/上下标轻着色），零新依赖零架构变更，真排版留远期观察 KMM 生态；②③裁决既定，卡转回 [ ] 开工——实施按影响面分析三批（S/S-M/M），③可并入批 1 或随后小步
  - 2026-10-01 批 1（最小着色）落地：自建壳 HighlightedCode.kt（fork -code v0.45.0，theme 入 produceState 键/每作业新建 Builder/区间守卫/CodeHL 打点四差异）+ CodeSyntaxTheme.kt（M3→SyntaxTheme 9 角色）+ MarkdownContent 三点位 + 单测 14 例；全量 3764 绿；真机 E2E 全判据过（流式逐批着色 ms≤27/MDResize 全正单调/守卫零触发/主题三态无残留/json 静默 spans=0）——journal 2026-10-01-488-syntax-highlight-shell 批 1 节；②余批 2（观察精修）/批 3（可选），③（数学标注）未开工
  - 2026-10-01 批 1 用户验收通过（「ok 可以」）——②最小着色收口；同会话续开 ③数学降级+标注升级（方案 c：降级块「公式」徽标+可选轻着色）
  - 2026-10-01 ③数学降级+标注升级落地：transform 产物围栏 tex→math（专属识别位，不误伤手写 tex 围栏）+ SafeHighlightedMathBlock（「公式」徽标 math_block_badge×15 语言 + \命令tertiary/花括号onSurfaceVariant/上下标secondary 手写轻着色，静态完结内容 remember 同步构建）+ 单测 12 例新增/transform 断言改标，全量绿+i18n 917 keys PASSED；真机 E2E（big-pickle 会话）：Code block, math 徽标块+dual族像素+vision 三色判读+亮色重建无残留+单美元守卫——journal 2026-10-01-488-syntax-highlight-shell ③节；环境坑：v1 无 zhipuai 凭据（新会话默认模型 401，改用既有 big-pickle 会话）；③ 就此收口待用户验收
  - 2026-10-01 ③用户验收通过（「可以 我觉得可以暂时用这样的效果」）——降级+标注形态就此定局；批 2（观察精修）/批 3（可选语言标签+复制）维持挂卡随观察窗推进，不阻塞
  - 2026-10-09 排程裁决：B3 收尾批（B2 狩猎批之后）；批 2 的 CodeHL 量化数据可从 B2 soak 日志白捡（mockllm code-long 语料 200 行 Kotlin 正好触发）；批 3① showHeader+a11y 本地化独立可做

- [ ] **#486 SSE retry 重建帧 watch（原#471④）：断连续传+滚出视口弃树+ever=false 理论盲区，复发再战勿主动开工** `scroll` `chat`
  - 原 #471④ 用户裁决 c 降级观察（v1+v2 四场景不可复现，331k 行取证 Loading=0，#472 hold/registry/preParsed 防御栈有效）；#471 整卡迁移后观察线独立成卡（spec §8：watch 不关）
  - 理论盲区：断连续传+滚出视口弃树重建+ever=false 帧（0 触发）；若复发，残余只剩 staged/换装差（-1330px ③族已消失）——spec §8 残余收窄注记
  - 判读取证：MDResize 负 d / RESETKEY / nonPrefix / rawTail 探针均保留 DEBUG-only（2026-09-30 用户裁决）
  - 2026-10-09 用户裁决：改「等复现」为 soak 长时主动狩猎→转 #535；本卡保留判读上下文。关键勘误：2026-09-30 实验 A/B 均未含「滚动离屏」第三要素（longcat 限流未跑成三联场景）——「0 触发」实为「0 试验」，见 journal 2026-09-30-471-4 §4.2

- [ ] **#464 UI 暖态下列表/卡片点击偶发失效(冷启可靠)** `chat-ui`
  - 2026-09-29 #461/#462 取证副产物:force-stop 冷启后输入 tap 可靠命中(会话行/卡标题),同一 app 暖运行数分钟后点击同坐标零效果(无日志无 UI 变化,vibrator 反馈存在=命中可点击元素但未触发业务);两次独立取证会话复现,冷启后恢复。疑点:点击消费被某 overlay/焦点态拦截或状态门;影响面=自动化测试可靠性,人工使用未报告。待真机复现窗定罪(diagnosing-bugs 流程),暂无用户主诉不阻塞。
  - 2026-09-29 审计顺带实证:一次拖动送达零响应不再现(见 #465 注记);#477 dump 全盲为坐标类判读新增干扰源——本卡与 #465 高度重复,建议合并为单卡观察项待裁决
  - 2026-09-29 用户裁决:吸收 #465 合并,本卡为该域唯一观察卡(暖态间歇 input 送达零业务响应;今日一次拖动零响应不再现实证在案;#477 dump 全盲为坐标判读新干扰源)——再撞上即现场抓 input dispatcher+app 双侧日志
  - 2026-10-09 用户裁决：转 #535 soak 主动狩猎（tap 效果断言+失配现场抓取 dumpsys input/logcat/meminfo；dump+截图双通道防 #477 dump 全盲假阳性）

- [ ] **#477 长文 markdown 块 uiautomator 语义零暴露——dump 全盲致渲染正常被误判空白** `ui`
  - 2026-09-29 收口审计真机定罪:末轮长文(async parse 路径)可见区 dump 零文本节点(仅 1 个空 text 可点击 TextView h≈1921px),同期旧 turn 正常暴露;vision(三路交叉)+像素双验证内容完整渲染(连续正文/无空白/无圆点)——渲染层无恙,纯语义暴露缺陷
  - 影响面=自动化验证可靠性+#467 dump 实证段污染(核心 SGR 日志证据不受影响)+a11y;待定位暴露分叉(疑 rememberAsyncMarkdownState 路径)
  - 证据:/tmp/ui_b1.xml(底,盲) vs /tmp/ui_top.xml(顶,正常);#475「近空白/折叠态」原判即此 artifact
  - 2026-09-29 定罪深化(见journal):分叉=单part纯文本长度(同turn 993暴露/3585盲,表格组件不受影响);渲染三层同构排除(组件/库重载/success槽);超视口单item假设被未分段表格turn(3674px)暴露推翻;最强嫌疑=SPLIT_PARAGRAPH 3000拆段线;androidTest断言已写但被MIUI+idle挂死双阻塞;修复候选A完结即分段/B到达即分段/C拆段线定罪后修normalize/D平台上报——待用户裁决,不盲改
  - 2026-09-30 定性翻转(用户裁决B降级观察):今日冷启进程两会话十几个turn(0-20k chars全路径)零盲例——五假设全推翻(拆段线:3585原文标准段落格式未触发/Q换帧:7523混合part同经换帧暴露/长文=盲:4077五段纯散文注入段段暴露/路径=盲/密集dump自伤:10连dump后仍暴露);原定罪(长文语义零暴露)重定性=疑似长跑进程状态性退化(R假设:昨21:22同屏老表格turn暴露vs新essay盲分野=内容创建时刻;盲取证全部集中于4h+单一进程晚期);再撞上(长跑进程新组合内容dump全盲)即抓:logcat a11y错误+dumpsys meminfo+uptime;A11yDiag探针留树备用
  - 2026-10-09 用户裁决：转 #535 soak 主动狩猎（周期盲 dump 检测器：dump vs mock 语料/Room 直查 ground truth；命中即抓 a11y 错误+dumpsys accessibility+meminfo+uptime；R 假设=同语料进程早晚期对比可同批验证）

- [ ] **#473 调试探针族清扫——历代 campaign 遗留 DEBUG 打点归档** `chore`
  - grep \\\[DEBUG- 盘点:CardExpandReveal(#420-427/466)/ChatMessageList+MessageCardAssistant(hflick/jk)/SafeFlingBehavior(flng)/ChatScrollController(drift)/rbexp 等几十处打点,均 BuildConfig.DEBUG 门控、release 零影响,但污染调试 logcat(472 闪烁定罪时曾混入噪音)。逐族清理+保留关键结构注释;涉及文件多有编辑协议约束,单独批次执行。
  - 2026-09-30 精化+新增三条方法教训:①「retained-subtree 盲区」实为 A11yDiag 语义=组合事件(内容经 pilot/preParsed state 对象流动时外层组合体跳过),内容级探针 MDPilot/ChunkDiag 一直覆盖,r3 实证 243/38 条——无真盲路径,改判读口径即可;②device 侧 grep 命令文本被 adbd in ShellService 记入 logcat→下轮自匹配假增长,监控必须 pull 到宿主 grep;③设备 nohup logcat 的 pkill -f 自匹配自杀(pkill 按进程名);宿主 nohup 不挺过 run_code 退出
  - 2026-09-30 用户裁决：由于特殊情况先保留（不关闭，与探针永久保留裁决的冲突挂起由用户自持）
  - 2026-10-09 探针开销取证（用户六问之 6，裁决依据）：release=探针族发射点全 BuildConfig.DEBUG 门控零执行；dev 构建 soak 实测探针 ~11.7 行/s 占 logcat 19.4%（GapDiag 独占 82%，#517 批埋点在重表格语料下高频命中），10h soak 201 轮零崩溃=性能无害实证
  - 空间：设备侧 logcat 环形不落盘；持久化默认 INFO 起（探针 d 级不入库，MDPilot 等 i 级 dev 下入库但 ~0.56 行/s 量级）；诊断库有人工 clear() 无自动裁剪（业务 INFO 日志长期累积为既有事实，与探针无关）；宿主侧 soak 证据 166M（开发机 cap 轮转文件，非用户面）
  - 后手：①R8 assumenosideeffects 剥 Log.d/v（推荐，~3 行配置，顺带清零未门控业务 d 站点）②GapDiag 节流聚合（可选）③用户关闭选项已存在=Settings 诊断级别调节+Diagnostics clear()，无需新增 ④宿主侧 cap 文件 zstd 归档（~10x）。建议：维持 keep-probes，#473 按 wontfix-by-ruling+后手① 落地收口，待用户拍板。证据：docs/research/2026-10-09-probe-overhead-audit.md

- [ ] **#454 v1 真机 IME 换行注入后 prompt 未发出** `chat` `device` `v1`
  - 真机 IME keyevent 66 发送路径:消息含注入换行(Run\n\n)时 prompt POST 未发出,乐观气泡悬挂;二次干净发送正常(prompt_async 202)。发送链路疑有 IME 竞态边角,#453 验证时顺带观察,未复现第二次
  - 2026-10-09 用户裁决：转 #535 狩猎（两层：单元红用例 sendMessage("Run\n\n") 断言 sendPrompt/parts 形状先行排除纯逻辑面；E2E 注入循环 50-100 次扫时序面）

## P4 — 外部前提阻塞

- [ ] **#381 192.168.110.248:248 重配——凭据宿主零痕迹不可探查（2026-09-09 用户裁决入 P4）** `infra`
  - **前提**：上游提供远程凭据探知能力——用户原话「后续看看 dsh 官方是否会支持远程探知 token 或其他认证手段」（DSH 官方远程 token 发现，或 OAuth/设备码流等替代认证）；前提满足后 `cred-probe.sh opencode 248 <名称>` 即可接管（/proc 探针现成）；若上游确认不会支持，再议弃用或人工一次性重配
  - 2026-10-09 前提重验：未满足维持——oauth/device_code/mdns/discovery 全包 0 命中、CLI 无 token 子命令、README 明文 no query token outside root exchange / --host 0.0.0.0 unsupported。边际变化：cookie 签名 secret 已持久化 .credentials.yaml（跨重启有效默认 30 天），官方文档化「token 一次性兑换长期 cookie」——未来可探索兑换复用。证据：docs/research/2026-10-09-p4-upstream-preconditions-check.md

- [ ] **#332 spill 提示行——服务器无结构化信号（工具结果溢出 notice 内嵌纯文本）** `dsh` `sse`
  - **前提**：dsh-spill-policy 全链查实——溢出替换为有界 head/tail 预览+locator 提示全部内嵌工具结果 output 文本,transcript 无 spill 事件（session 事件枚举/types/实现三路 grep 0）;文案模式匹配脆弱（#136 先例:服务器改文案即静默失效）。待服务器暴露结构化字段再实现。→ `docs/research/2026-09-05-audit-309-313.md` #312③ + 实现 agent 取证（暂不可实现）
  - 2026-10-09 前提重验：未满足维持——61 事件枚举/ToolResultMessage 载荷零 spill 结构化字段，上游自认文本约定不可认证（hasSpillNotice 注释「not authenticated tool-output origin」+ README Known Limitations 首条）。可参考上游固定拼写 formatSpillNotice 实现替代自造正则。证据：docs/research/2026-10-09-p4-upstream-preconditions-check.md
  - 2026-10-09 用户裁决：授权降级实现（上游固定拼写文案匹配，实现处须详细注释约定来源与不可认证性）→新卡 #536；本卡维持 P4 挂结构化信号长期观察，上游升级时复查后切换

- [ ] **#146 OpenCode 官方问题清单（issue/PR 候选）** `upstream`
  - **前提**：上游 anomalyco/opencode 合入变更——需先过用户流程门槛（本地定位官方源码→修复→完整测试含 E2E+交叉验证→人工测试→用户放行才可提交 PR；源码已就位）；2026-09-03 用户裁决长期挂起，上游不提不影响本 app（客户端防御均已落地）
  - ①V2 不发 compaction.started（引擎没接线）②SSE 重连无事件回溯 ③cursor V1 格式返回 400 ④fork handleRaw bug ⑤工具输出截断语义——上游核查完成（repo 已迁 anomalyco/opencode），逐项行动方案已定
  - ⑥候选（2026-08-27 八轮实证）：V2 后台 shell 状态恒 completed（exit 7 亦然），失败信号仅正文文本——上游语义退化，客户端已防御性派生
  - **2026-09-02 逐项复现取证完结（journal 258-stage-b §十）**：源码浅克隆 `~/Documents/code/opencode-upstream`@69c172e + Host-4199 live 复验——①②③⑥ HEAD 仍成立（①连 schema 都无 Started；②端点零回溯处理；③400 已类型化 `_tag` 但无降级；⑥exitCode 从不映射 status）；**④上游已修/改版**（空 body 分支 + payload 改 `{messageID?}`，运行版未跟上，app 现发形状已匹配 HEAD）；⑤不变（FR 开放）。PR 候选排序 ③>⑥>①>②
  - → `docs/journal/2026-08-15-chat-flow-bugs.md`
  - 2026-09-29 前提复核:上游浅克隆 ~/Documents/code/opencode-upstream 已不在本机——重验/提 PR 前需重新浅克隆
  - 2026-10-09 逐项复查（HEAD 3884062 2026-10-08 + v2.0.26 tag 取证）：①已修（V2 core schema 三档 session.next.compaction.started/delta/ended+引擎发射；V1 兼容路径仍单事件）②③未修且官方定局不做（#25657/#29544/#19584 均 CLOSED NOT_PLANNED——客户端自救即终态，可标 wontfix 收口）⑥未修（v2.0.26 状态仍两档无条件 completed；dev HEAD 已拆除待重做 durable status）④已修仍在 ⑤部分进展（outputPaths/truncated 进 schema+core 统一限幅落盘；#45910 per-tool 未收口）。⑥登记「后台 shell 重建后复查」。证据：docs/research/2026-10-09-p4-upstream-preconditions-check.md
  - 2026-10-09 用户裁决：②③ wontfix 收口（上游三 issue 均 CLOSED NOT_PLANNED，客户端自救=重连快照重拉+只产 V2 cursor 即终态）；卡保留 ①（已修可消费 compaction.started 三档）/⑤（outputPaths 部分进展）/⑥（后台 shell 重做后复查）
