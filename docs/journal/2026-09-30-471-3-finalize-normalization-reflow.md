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

## 已完结卡片迁入（2026-09-30）

### **#471 完结瞬间高度跳变族:StepGroup整树互换+>2048字符Loading塌缩+归一化重排** `chat,markdown`
  - 2026-09-30 调研 P2 定罪:①多消息 turn 完结时 StepGroup 流式平铺↔折叠组整树互换(探针注释自认结构性高度跳变源,数千 px 级,冷账本 24dp 桩帧,MessageCardAssistant.kt:428-479);②>2048 字符正文完结切 pilot→async 首帧 Loading≈0 高再 Success 全高(#428 同族 +268px,MarkdownContent.kt:603-715);③完结归一化变换(数学围栏/任务列表/长段空行化)只发生在完结=一次性重排。方向:②pilot 终帧同步换入/缓存预热收益最明确;①依赖 L3 AST 切片既有计划。另:setext 升格(SafePrefixGate 自认缺口)+tight→loose 列表+CRLF 表格三小项随 markdown 批次顺带。
  - 2026-09-30 #472 修(②完结 Loading 塌缩):真机定罪在手——断连重连恢复换装时 RESIZE t_msg_0e641a99a001 h 1105→865(d=-240)→1580(d=+715) 41ms 两连跳(=Loading≈0 高帧→Success 弹回,与正常完结换装同构);另 RESERVE align-flip overflow=-62 佐证帽负溢出。根修:pilotTerminalHold 纯函数(pilot 曾渲染∧async 未就绪→完结帧保持 pilot 终帧)+asyncTerminal 提升固定组合位(条件创建,hold 期与切换后同实例零重解析,collectAsState 响应式解除);残余归一化差由帽配对吸收。TDD 3 例红转绿+全量单测绿;①StepGroup 互换与③归一化重排为残余(量级小于已修,另批)。
  - 2026-09-30 修复交付(②完结 Loading 塌缩):真机定罪——重连恢复换装时 RESIZE t_msg_0e641a99a001 h 1105→865(d=-240)→1580(d=+715) 41ms 两连跳(=Loading≈0 高帧→Success 弹回,与正常完结换装同构);另 RESERVE align-flip overflow=-62 佐证帽负溢出。根修(12f8214b):pilotTerminalHold 纯函数(pilot 曾渲染∧async 未就绪→完结帧保持 pilot 终帧)+asyncTerminal 提升固定组合位(条件创建,hold 期与切换后同实例零重解析,collectAsState 响应式解除);残余归一化差由帽配对吸收。TDD 3 例红转绿+全量单测绿+装包。①StepGroup 互换与③归一化重排为残余(量级小于已修,另批)。待用户复验:完结一瞬无塌缩弹开。
  - 2026-09-30 用户验收通过(②完结 Loading 塌缩):茶文化轮判定链+高度序列双证据——完结帧 hold=true 拦截 Loading(19:00:10.772),144ms 后 async 就绪无缝切换,全程高度差仅 ±24px(归一化微差,一行文字高);对比修复前同场景 -13171/+12824 两连跳。残余:①StepGroup 边界换装(轮次开始/结束各一次 845→1492)与③归一化重排量级小待后续;新发现④SSE retry 恢复场景:重连后内容跳变重组期 pilotEverRendered 丢失(ever true→false 实证)→塌缩 -8575px 仍现,仅断连续传时发生,正常轮次不受影响——随 retry 路径稳定性专项处理。
  - 2026-09-28 ①StepGroup 互换已根灭(#422 清理批次,用户裁决彻底清理):统一渲染树后流式/完结同构,互换不存在——10轮多step POST完结塌缩 10/10→0/10。③归一化重排与④SSE retry pilotEverRendered 丢失仍待处理(与本卡完结族余项同批)
  - 2026-09-29 E2E 活证:表格轮完结/换装震荡 -8544/+7236(t_msg 项 14k px)——完结族在真实表格轮的量级实证(此前 #472 验收为纯文本轮 ±24px);表格轮完结路径(归一化+staged 重建+换装叠加)待专项取证
  - 2026-09-30 ④复现确认调研(用户指令:修复前先确认仍存在):静态链仍可达(ever=false 重建帧无防御——pilotTerminalHold 只拦 ever=true);真机 v2 双场景未复现 ever 丢失(实验A服务端重启 reconcile:hold 拦截✓残余-1330归一化差③族/实验B链路断+续生成跳变2523→5144:hold 拦截✓零塌缩+5969正向追平)——v2 backfill partId 稳定组合位不销毁。未验证:v1 message.updated 全量重发路径(bigmodel key 401 失效,16:23 前同 key 尚成功)+弃树重建三联(longcat 免费档限流挂死)。裁决请求 a防御性根修(msgId 级 ever 账本)/b恢复 v1 后补验/c降级 watch——用户已选先走 b:查官方文档重新部署 v1 再测。详见 docs/journal/2026-09-30-471-4-sse-retry-pilot-ever-probe.md
  - v1 验证完成(裁决数据齐备):bigmodel 双key全灭→免费档 nemotron 重部署;wire 级证伪 full-parts resend(message.updated 恒无 parts,partId 全程稳定,mergeAssistantMeta 只并元数据);真机三轮断连实验 331k 行取证——asyncTerminal Loading 帧=0、hold=true 拦截正例、preParsed 分块复入 Success、底部屏幕实证完整渲染;④ v1+v2 双协议不可复现(#472 后防御栈有效)。裁决请求 c(降级观察,推荐)/a(防御根修 ~30 行)。详 journal 2026-09-30-471-4-v1-verification.md
  - 2026-09-30 ④用户裁决 c(降级观察):v1+v2 四场景实测不可复现(331k 行 Loading=0,#472 hold/registry/preParsed 防御栈有效)→④转 watch 复发再战(理论盲区仍在:断连续传+滚出视口弃树+ever=false 重建帧,0 触发);残余③归一化重排(含 09-29 表格轮 -8544/+7236 活证)继续挂本卡待批
  - 2026-09-30 ③归一化重排专项开工:方向=归一化前移到流式 ingest(终帧=流式帧,完结变换幂等化);setext 升格/tight→loose/CRLF 三小项随批评估
  - 2026-09-30 ③归一化重排设计定稿 to spec:docs/specs/2026-09-30-471-3-streaming-normalization-unification-design.md——归一化前移 gate 前流式 ingest(终帧=流式帧)+位置制空行化(cumEnd 行完成即定案)+gate 表格注入退役;实现按 spec §5 三 commit(新会话可执行);可关卡片清单 spec §8(本卡验收后整卡关)
  - ③实现落地待验收：spec §5 三 commit 完成——A 276d8fbf(纯函数+TDD+性质测试抓出四破口修订:尾$run收口/表格排除数学行/表头待定回退/MarkdownFenceLine 统一围栏) B 9130c83e(pilot ingest 接线+gate 注入退役) C(E2E:held=0→85ms 桥接→Success 直达,无弹跳双帧,flap=0,3716 绿)。E3 以单测+长段档案替代(用户验收保留)。验收清单 spec §9;铁律 9 已收编 iron-laws
  - ③验收发现第三根因并修复:差分基准错配——prev 存归一化全文快照,$$闭合重写天然非前缀但落扣留区,SMP 误判重生成→静默 resetKey 重建(真机 P1:h 塌缩-1128+从零重铺×2);修复=prev 改放行前缀 normalized.take(released),复现验证重建事件 0/无负向跳。验收探针(MDResize/heldTail/nonPrefix)保留 DEBUG-only
  - 验收发现(真机)：GFM 任务列表整块毕业（4条一次性吐出）——归一化后 [ 硬停+无任务项行分支致整列表扣到空行毕业；#471③ 归一化前移后原保守理由(☐完结改写)已消失，可行级放行（#441 任务项分支模式）
  - 追加根修(#471④-a,6011b888)：任务列表逐条放行——v1 行首守卫被真机 E2E 证伪(行续段冻结)，v2 锚定真实行首+未完行渐进；视觉验收待用户
  - 追加根修(#471④-b)：任务复选框此前渲染字面[x]文本——markdownComponents漏传checkbox参数落基础模块默认（官方demo接m3 Material CheckBox）；已接线+真机a11y验证0字面/119语义节点
  - 坍缩重建根修四连（2026-09-30 真机定罪）：①归一化 run 哨兵空行吞噬——ensureBlankLineBeforeGfmTables/transformMathFallback 的 run.isNotEmpty() 哨兵把「首行为空行」（闭合围栏后空行）误判 run 未启动→分隔换行被跳过→空行吞噬；流式中 | 首达（快速路径退出）时首次生效→已放行前缀中段非前缀→RESETKEY 重建坍缩+4.4s 限速重铺（三案 13:55/14:13/14:40 同源 divergeAt 皆落围栏后空行）。②gate 表头行中线/未完分隔行/行首空白回退补口——批预算截点越过未来插空行点。③mergePart 流式期前缀一致性守卫——异构快照不再替换 delta 累积，终态权威替换不变。④pilot 重建快速重灌（200ch/200ms→800ch/帧）+rawTail 取证探针。真机终验：同配方 prompt 零 nonPrefix 零重建高度单调；全量单测绿；证据链=wire 抓取回放（WireReplayDivergenceTest）+全前缀扫描（NormalizeDivergenceProbeTest）
  - 2026-09-30 用户整卡验收通过（③归一化前移+④-a 任务列表逐条放行+④-b 复选框接线+坍缩重建四连，真机复验干净；①#422 根灭/②#472 已验收/④转 watch 另立观察卡）；同批新裁决：验收探针（MDResize/heldTail/nonPrefix/rawTail 等）永久保留 DEBUG-only 不清理——同 #485，为复发保留第一手取证
  - 迁入依据：用户整卡复验通过（③④及坍缩重建四连真机干净，spec §8 关卡清单兑现）；④转 watch 另立观察卡；探针保留裁决随卡迁入（backlog.sh migrate 2026-09-30）

### **#443 markdown 稳态粒度扩展：更多大块逐行/逐段放行（引用块/嵌套列表/长段落折行等）** `streaming`
  - 用户裁决（2026-09-26 #441 后续）：表格/列表已逐行；评估引用块(>)、嵌套列表、def list、长代码块行级、setext 标题等大块的行级定案可行性，逐类 TDD 扩展 SafePrefixGate。
  - 2026-09-30 核查收口：逐类对照 SafePrefixGate 现码，清单已随 #441→#472→#471③④ 四批逐类兑现——表格逐行(续放+三回退守卫)/列表(* 与有序行级、任务项行级+渐进、普通 - + 走纯文字直出)/引用块行级(懒延续刻意扣留)/嵌套列表浅缩进走行级、≥4 缩进刻意扣留/长代码块围栏行级/长段落位置制空行化+纯文字直出/ATX 行级/def list 无需(纯文字直出)；唯一残余 setext=刻意 accepted-gap(spec §3.6 stage-2 备查+铁律12注记)，不再单独占卡
  - 迁入依据：用户裁决关闭（2026-09-30「好」）：逐类对照表证实实质已随四批落地，setext 维持 accepted-gap 由 spec §3.6+铁律12 承载（backlog.sh migrate 2026-09-30）

### **#482 V2 prompt.files 嵌套契约未经部署版实证(2026-08-16 TODO)** `v2` `data`
  - V2ApiClient:526:嵌套 body 部署版 next-17430 一律 400→线上一直走平铺降级(files 顶层);主干部署后需 E2E 验证 modernBody 分支再收敛双路
  - 验证成本低(一次带附件 prompt E2E+抓帧);与 #459 漂移族相邻但独立(这是契约实证,非端点缺失)
  - 2026-09-30 实证收口（部署版 v2.0.19，systemd 常驻 4096）：①嵌套 body {prompt:{text,files}} → 400 Missing key ["text"]（服务端 zod schema 仍要求顶层 text，嵌套包裹仍不被接受）②平铺 body {text,files:[{uri:data:...;base64,name}]} → 200，payload.files 回显 {data,mime,source:inline,name}；一次性会话已 DELETE 204。结论：双路收敛不可行，平铺降级路径在 v2.0.19 仍为部署版唯一有效契约，modernBody 分支保留待未来主干部署
  - 迁入依据：实证完成：v2.0.19 部署版嵌套 400/平铺 200，保持双路不收敛，零代码改动（curl 直打 4096，测试会话已清理）（backlog.sh migrate 2026-09-30）
