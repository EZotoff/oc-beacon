# 471-3-finalize-normalization-reflow（2026-09-30）

> 状态：进行中
> 关联：（spec 路径，若有）·（issue 编号，若有）
> 来源：用户反馈 / grilling / E2E / 顺带发现

<!-- 过程中的取证/验证证据直接写本文件；backlog.md 只留 ≤3 行卡片。 -->

## §1 调研与设计定案（2026-09-30；spec 为权威：docs/specs/2026-09-30-471-3-streaming-normalization-unification-design.md）

- 代码级全景调研：归一化 5 变换本体 + 全部 5 消费点（async 终态/parseAsync/小文本同步/libStreaming fallback/RenderReadiness.preParse）+ pilot 包装器与 SafePrefixGate 全 516 行 + markdown 域全部 17 个测试文件
- 定位冲突①裁决原文（StreamingMarkdownPilot.kt:66-68，#265 时代取舍）及其解锁钥匙：单调性只需对已放行前缀成立，归一化回改点全部落在 gate 扣留区（$$/☐☑✅/|/反斜杠/[] 皆活动标记）
- 调研两项新发现：① libStreaming fallback（pilot 关闭时）本就在流式侧做 normalizeForRender——逐快照归一化有先例；② gate injectTableBlankLines 即同判定前移的先例，本批泛化后退役
- 设计关键推演：位置制空行化必须「cumEnd(行 j)≥3000 且行完成时即定案」（不能等下一行存在，否则已放行换行被回改）；语义变更 = ≤3000 段落不拆 + >3000 头部整块+尾部渐进拆（稳定接缝），SplitOversizedParagraphsTest 语义测试需重写
- 影响面：主代码 3 文件（MarkdownContent/StreamingMarkdownPilot/SafePrefixGate）+测试 4-5 文件；ChatScreen/高度引擎/HeldTailReveal 零触碰；三 commit 计划见 spec §5；可关卡片清单见 spec §8（#471 整卡验收清单、#437 铁律收编 +1 域）
- 用户裁决链：方向批准（终帧=流式帧）→ 管线示意图确认 → async 保留确认（静态渲染本职：分片/内存/历史消息三硬理由）→ 调研+to spec 指令

## §2 实现（2026-09-30，commit A 276d8fbf / commit B 9130c83e）

- **commit A（纯函数+TDD）**：normalizeMarkdownCore（CRLF 快路径+表格空行+数学降级，无身份分支）/ normalizeForStreaming（pilot 专用）/ normalizeForRender 重构（同源组合，render==streaming 逐字节不变量入测）/ splitOversizedParagraphsByPosition 位置制空行化（cumEnd≥3000 行完成即定案；旧 splitOversizedParagraphs 删除）
- **性质测试（NormalizationStreamingMonotonicityTest）抓出四个真实破口**——spec §3.3 矩阵的论证漏洞，全部修复并钉死：
  1. gate 返回处尾 $ run 收口：$ 逐字符到达时首字符以「单美元=纯文字」直出、次字符以行续段身份再放，跨批凑成 $$ 后闭合字符到达触发归一化改写（math-block fixture k=21/22 逐步实证）；收口=截点在快照尾且以 $ run 结尾时退过整个 run
  2. gate 表格三处（族续放/表头分支/正文行循环）排除含 $$/\[ 行：块级数学跨行配对面，完整行 ≠ 定案
  3. gate 表头行待定三行结构回退：空行毕业先放「文字行\n表头\n\n」，分隔行后到时 ensureBlankLineBeforeGfmTables 在文字行与表头间插空行（random 轮 k=318 实证）；回退到表头行首，走表头分支或 EOF flush
  4. 归一化侧两个**存量渲染缺陷**兼修：① ensureBlankLineBeforeGfmTables 无围栏意识——栏内表格形态（代码字面）被插空行；② 三变换围栏跟踪与 gate 分歧（栏内「```xxx」误闭合 + 反引号栏 info 不查反引号）——统一为 MarkdownFenceLine 共享判定（gate/CommonMark 严格语义）
- **commit B（接线+退役）**：rememberPilotStreamingMarkdownState 顶部 normalizeForStreaming（prev/released/heldTail 坐标全归一化；heldTail 显示 - [ ] 预览/tex 围栏行=WYSIWYG）+ injectTableBlankLines/prevLineBeforeIsTextRow 删除（releaseDelta 退化 substring，state.content 与快照坐标重新耦合）+ 冲突①裁决 KDoc 重写 + gate 注入正例测试迁移
- 全量回归 **3716 绿**（基线 3700 + 16 新增）

## §3 真机 E2E（小米 houji 无线 192.168.110.239:36339；dev debug；v2 4096 反向端口；LongCat 2.5 Preview Free）

- **E1**（任务+$$ 公式+贴边表格轮）：完结态 tex 围栏块/表格（双列渲染）/任务行渲染正确——归一化生效；裸「☐ 文字」行首无列表标记属 task 变换既有语义（流中/完结一致显示 ☐，非本批回归）
- **E1b**（- [ ] 列表诱导 + >2048 essay 轮，2664 字符）**核心判据全过**：完结序列 = gate release held=**0**（归一化全文已放行，pilot 终帧=全文归一化文本）→ path=pilot hold=true ×2（**85ms** 桥接窗）→ path=render src=asyncTerminal **Success 直达**——无 Loading→Success 弹跳双帧（对照修复前同场景 -13171/+12824 族与 #472 残余 ±24px）；flap suppress=0；2672（归一化后）vs 2664（原文）= 8 字符空行插入自洽
- **E2**：与 E1b 同轮覆盖（>2048 完结换装无塌缩/弹开）
- **E4**：gate release 全程节奏正常（首批铺开+小批直通），无扣留风暴/无非前缀重建
- **E5**：流式批次间隔 ~90-100ms（48ms 预算节奏内），无 ANR/无渲染期 AnrScout 报警
- **E3（>3000 单段）取舍记录**：真机诱导 3000+ 单段成本高——以位置制单测（截断前缀稳定/幂等/精确边界）+ E1b 长段（716px 连续大段）视觉档案替代；**用户验收清单保留此项**（spec §9 第 3 条）
- 截图档案：/tmp/e1b_final.png（本机临时，未入 git）；logcat 全程 /tmp/e1b.txt、/tmp/e1full.txt

## §4 验收发现第三根因并修复：差分基准错配（2026-09-30，用户真机观感驱动）

- **用户报告**：P1 输出过程仍有「闪烁与整段内容重建」多次。日志精细化取证（用户裁决方向）：补 MDResize 整卡高度探针 + heldTail 交接探针 + 非前缀/重建盲区日志（resetKey 单次重建此前静默——只有风暴才打 flap，定位盲区自认）。
- **现象定罪**（三通道对齐）：流式卡 h=1304→176（**-1128px 塌缩**）→ 历史卡占位闪现 → pilot append 200ch total=200 从零重铺（resetKey 静默重建），一轮 2 次。
- **根因**：prev 差分基准存「归一化全文快照」——归一化闭合重写（双美元→tex 围栏等）天然使全文对 prev 非前缀（实证 divergeAt=146，prevCtx=[双美元开头，newCtx=[tex 围栏开头），但重写点全部落在 gate 扣留区（released 之后）、放行前缀跨快照稳定（性质测试早已证明）。SMP 以全文为基准 → 把合法扣留区重写误判为「重生成」→ 300ms 宽限 → resetKey 整树重建。spec §3.4 设计漏项：归一化世界的正确差分基准是**放行前缀**而非全文。
- **修复**：prev 语义改为 normalized.take(released)（放行前缀，4 处赋值统一）；闭合重写后 startsWith(releasedPrefix) 仍成立 → 不再误触发；真重生成（改写已放行区）仍正确触发。
- **修复后真机验证**（同场景复现）：非前缀/重建事件 **0**、无负向高度跳（修复前 -1128）；剩余大跳仅 +278/+222（任务/公式块毕业=gate 闭合语义设计行为）与 +1836（新卡首帧）。



## 5. 验收追加轮：GFM 任务列表整块毕业根修（#471④-a，2026-09-30）

用户验收发现：任务清单 4 条一次性吐出（复选框渲染正常，纯粒度问题）；期望逐条流式。

根因链（代码定罪）：#471③ 归一化前移后 gate 吃 [ ] 形态，而 [ 是 InlineSpanSafety 硬停字符且无任务项行分支——未完任务行先被兜底分支放行 "- "（截在 [ 前），行续段 lineStartReal=false 使行级分支永久不可达，整个紧凑列表冻结到列表后空行才一次性毕业。原整块扣留理由（☐ 完结时被 normalizeTaskListMarkers 改写=非前缀）已随归一化前移消失。

v1 到 v2（真机 E2E 证伪驱动）：
- v1（行首守卫任务分支）：单测全绿（喂入都是行首对齐），但真机 E2E 冻结——日志三批 release=0 held=105→207→280，空行到达才一次性放 387。教训：行级分支必须考虑行续段恢复（兜底分支的 "- " 前缀泄漏是常态路径）。
- v2（6011b888）：任务项判定锚定真实行首（行续段可恢复）+ 复选框+空白前缀齐备即定案（未完行条目文字经安全帽渐进直出，字级节奏优于整行等待）。含 $$/\[ 条目行仍扣留（与表行同口径）。
- Kotlin 块注释嵌套陷阱：KDoc 文本含字面 /* 序列会开启嵌套注释层级吞掉后续代码（本次编译事故根因，已改写措辞规避）。

测试：SafePrefixGateBlockGranularityTest +5 用例（整行/预算逐条/渐进+硬停/冻结恢复/数学扣留）；任务列表逐字符单调性性质；全量 testDevDebugUnitTest 绿。

验证边界（如实）：v2 的逐条视觉效果尚未取得真机日志/观察证据——复测窗口被会话污染（模型沿用会话模板输出物理学史清单 + Build agent 把测试提示词当任务开工 Reading 3m12s，已 API 中断）与设备占用（锁屏/用户操作）打断。函数级证据（含 E2E 形态冻结恢复回归）已钉死；视觉验收交用户（新会话发任务清单提示词，观察逐条出现）。

（勘误：上一条目 "## -s" 为脚本误调用残片，已随本节补写清除。）

## 6. 验收追加轮②：任务复选框字面文本根修（#471④-b）

用户裁决：要求真 markdown 复选框（控件），否决 [x]/[ ] 字面文本形态。

根因（库源定罪）：markdownComponents() 未覆写 checkbox 参数 → 基础模块默认 checkedIndicator = MarkdownText("[x] ", Monospace)（MarkdownCheckBox.kt 源码）。解析器（GFMFlavourDescriptor）与列表 CHECK_BOX 节点→components.checkbox 调用链路一直正确——纯组件层装配缺口，非解析缺口。

修复：接 m3 MarkdownCheckBox（Material3 Checkbox 只读+role/stateDescription 语义），与官方 sample MarkDownPage.kt:84 同款。真机 a11y 验证：字面文本节点 0（修复前满屏）、checked 语义节点 119。全量单测绿。

调研产出（能力矩阵+官方对照）见本轮回复；代码高亮模块（multiplatform-markdown-renderer-code）未接入为已知现状。

## 坍缩重建根修四连（2026-09-30 下午，用户主诉「输出时整个回答坍缩并重建」）

**证据链（Phase 1-2，全部可复核）**：
- 99MB 后台全量 logcat（bash-901 持续抓取）捕获得 13:55 事件全链：nonPrefix armed（divergeAt=396）→ 300ms 宽限 → RESETKEY rebuild → MDResize 5741→3249（d=-2492 坍缩）→ 200ch/210ms × 21 块重灌 4.4s（重建段）→ 流末 d=-15432（模型退化复读 output=9155 tokens、服务器截断 914 字符、text.ended 权威替换）。
- wire 真值：GET /api/event 全局 SSE 抓取（curl -Ns 重连循环）+ 逐 delta 回放（WireReplayDivergenceTest）：数据层组装与 wire 逐字节一致（289 字符）→ 数据层无罪。
- Kotlin 探针（NormalizeDivergenceProbeTest）：真实语料 289 字符逐前缀扫描 normalizeForStreaming——定罪 k=232 处 divergeAt=163（围栏后空行消失），分级定位 = ensureBlankLineBeforeGfmTables。

**根因（双层）**：
1. ensureBlankLineBeforeGfmTables / transformMathFallback 的 run 累加器以 run.isNotEmpty() 作「已消费行」哨兵——run 首行为空行（闭合围栏后的空行）时 run=="" 被误判未启动，下一行 append 跳过分隔换行 → 空行被静默吞噬。流式中该吞噬在文本首次出现 |（表格行迟到，快速路径退出全量重扫）时首次生效 → 已放行前缀中段非前缀改写。13:55（divergeAt=396）/14:13/14:40（163）三案同源，divergeAt 全部落在闭合围栏后的空行位置。
2. SafePrefixGate 纯文字分支视表头行为无标记文本增量直出 + 既有「表头行待定三行结构回退」以截点收在行尾为前提——批预算截点落在表头行行中/未完分隔行/行首空白时越过未来插空行点（14:58 真机：released=231 > 插点 229）。

**修复（四提交）**：
- 根修①（4f1..: MarkdownContent/MarkdownMathFallback）：run 哨兵改 runLines 显式计数，split/join 无损。
- 根修②（SafePrefixGate）：截点所在行为表头行形态/截点前行内仅空白/截点落未完分隔行内——三形态回退到安全边界；分隔行完整=定案解锁。
- 根修③（MessageMergeEngine）：流式期（无 end）前缀一致才替换（REST 领先快进保留），分歧保 delta 累积；终态权威不变（reasoning 对称+isTerminal 补齐）。
- 根修④（StreamingMarkdownPilot）：RESETKEY 重建再铺开走快速重灌（800ch/帧、免壁钟，~6 帧完成）；限速仅保留首跑。nonPrefix 探针附 rawTail。

**回归锚**：NormalizeDivergenceProbeTest（全前缀扫描）/ SafePrefixGateTableHeaderMidLineTest（三形态回退+完整解锁+跨分隔行前缀稳定）/ WireReplayDivergenceTest（wire 回放组装一致）/ RefeedPacingTest / MessageMergeEngineTest 四新测试 / MessageEventHandlerTest 语义修订（diverging longer snapshot kept out）。

**真机终验（15:14，同配方 prompt——此前 4 次触发）**：零 nonPrefix、零 RESETKEY、MDResize 单调无负 delta（96→1838）。全量单测绿（EXIT=0），装机 Success。

**残余（登记不修）**：流末模型退化截断替换（d=-15432 类）为内容真变的正确收敛，平滑化归高度引擎收縮配对（后续卡片）；RESETKEY 重建首帧空态闪帧（快速重灌已压至 ~6 帧，零闪帧需帽保持协议）。

**方法论**：diagnosing-bugs 全流程——后台持续抓取即反馈环（Phase 1 #5 replay captured trace）；wire 回放 + Kotlin 探针 = 离线确定性复现（Phase 2 minimize）；假设排名（Phase 3）排除了高度引擎/数据层组装/表格插行主路径；分级探针定位（Phase 4）逐变换二分；TDD 红→绿（Phase 5）四层各带回归锚。
