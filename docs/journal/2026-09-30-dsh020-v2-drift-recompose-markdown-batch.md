# dsh020-v2-drift-recompose-markdown-batch（2026-09-30）

> 状态：进行中
> 关联：（spec 路径，若有）·（issue 编号，若有）
> 来源：用户反馈 / grilling / E2E / 顺带发现

<!-- 过程中的取证/验证证据直接写本文件；backlog.md 只留 ≤3 行卡片。 -->

## 阶段一A 侦察：DSH 0.2.0-rc.2 线面全貌（live 3080 实测 + npm dist 生成绑定提取）

环境：live = 宿主 3080 systemd 用户服务 dsh.service（`dsh web --no-open`，pid 8928，HOME=/home/leonardo）；token 从 journalctl 提取（--since 2026-09-29 grep token=），交换流 `GET /?token= → 303 + Set-Cookie` 不变；二进制 @deepseek-ai/dsh 0.2.0-rc.2（brew node_modules，monorepo 化：dsh-api-* controller 包 + typert.remote-client.js 生成绑定）。

## 通道面
- HTTP 单发 `POST /api/{method}` 兼容保留（typert gateway 描述符强制校验）
- WS 通道 **events.mux → /api/remote.mux**（唯一，101 实证；events.mux/events.host 均亡）——"carrying every Typert Remote stream"，内部逻辑端点 `$events`（开事件流）+ `$events/result`（waterfall 回程）
- `/api/respond` **404 消失**（D5 延续）；`/api/$events/result` 在但信封形状变（要求 client-request 形态，实测 client-response 回 "gateway/bad-request: invalid client-request message"）
- 探针教训：unauthorized/bogus 探测时 URL 方法必须用斜杠 wire 名（点式 404 ≠ 端点消失）

## 方法面（app 47 调用点 → 0.2.0 状态）
**args 契约三类**（typert gateway 描述符）：
- `request:{...}` 包装：session/create|cancel|prompt|fork|rename|search|selectModel|updateQueue|attachment|page|follow|projections|openWorkspacePath|workspacePathApplications、messageFeedback/delete|list|put、skills/list、workspace/archiveSession|unarchiveSession|create、subagents/prompt、job/list
- `_request:{}`：session/list（独一份）
- 裸字段：commands/execute（agentId,line,**images→submittedAttachments 改名**）、commands/list、credentials/describe(refs)|set|unset(ref)、fileReferences/list、llm/discoverModels(settingsNs..)、settings/mutate(ns..)、goals/*(agentId..)、agentPresets/read(agentPreset)|select(agentId..)、subagents/interruptByParent(childSessionId)、sessionReferenceResolver/candidates、terminal/*、account/*
- 空 args 即通：settings/describe、llm/listProviders、llm/listConfigurableProviders、pluginInventory/list

**改名/迁移**：session/history→**session/page**；llm/providers→**llm/listProviders**（待确认语义）；host/listDirectory|createDirectory→**directoryPicker/list|createDirectory**；单数域→复数：goal/*→**goals/**、subagent/*→**subagents/**、agentPreset/read|select→**agentPresets/**。

**消失（404 实证）**：agentPreset/list|copy|deletePreset、subagent/list、workspace/list、host/describe、llm/models、session/history（→page）。

**新增域（app 未接）**：account/*（8：DeepSeek 账户/余额/签到）、terminal/*（10）、job/*（3）、workspace/create|delete|insertBefore|insertSessionBefore|pinSession|unpinSession|**unarchiveSession**（#352 前提解锁候选！）、goals/complete|get|pause|resume、userQuestions/answer|attachWait、session/follow|projections|page。

## 错误码面（源码提取）
gateway/* 家族 20+（internal/arguments-invalid/bad-request/cancelled/ambiguous-endpoint/binding-invalid/context-*/definition-unavailable/input-invalid/invocation-unavailable/lookup-*/method-unavailable/protocol/provider-mismatch/result-invalid/service-unavailable/signature-invalid/uplink-overflow）+ session/*（not-found/conflict/agent-busy/queue-item-not-found/steer-unavailable/model-unavailable/title-invalid/writer-held/...）+ workspace/*（not-found/name-conflict/...）+ subagent/* + credential/rejected。app 39 值点式闭集全面脱节确认（#458 原定罪成立）。

## 适配工作量定级
1. DshWireProtocol/Adapter：V020 收敛（用户裁决不保 0.1.x）+ args 三形状翻译表 + 改名映射
2. DshApiError：错误码闭集重建（斜杠码 + 七类分类表重排）
3. 消失端点的 app 侧消费面清点与降级（agentPreset.list 等 8 个）
4. DshWsEventClient：events.mux → remote.mux 迁移 + $events 流开启协议
5. 回程：respond 亡 → $events/result（信封形状待解析）+ userQuestions/answer 评估

## #458 DSH 0.2.0 适配（commit 2 项）

**认知修正（重要）**：侦察初判"0.2.0 全面漂移"过重——DshWireAdapter（V012，0.1.2 时代适配）已承载 session/history→page、单数→复数域（goals/subagents/agentPresets）、host→directoryPicker、llm/providers→listProviders、session/list _request 键、args 四风格（SELF/EMPTY/FLAT/WRAPPED）全部映射；remote.mux 也是 V012 已用通道。**真实漂移面（V012@0.1.x → 0.2.0-rc.2）收窄为**：
1. 错误码闭集脱节（#458 原定罪主体）：39 点式 → 0.2.0 观测 64 码（gateway 22 + session 15 + subagent 8 + workspace 5 + directory-picker 4【0.1.x directory-* 族继任，坏路径实测】+ agent-preset 4【横杠命名空间，agentPresets/read bogus 实测漏网补齐】+ credential/job/llm/terminal 6）；具名常量收敛为引用集 + 新增 Gateway* 族
2. commands/execute 字段改名 images→submittedAttachments（两处）
3. 本地铸码：command-error→gateway/result-invalid（kind!=success 语义标记）、cancelled→gateway/cancelled（userQuestions 回程）
4. 目录探测信号码：directory-unreadable→directory-picker/unreadable（WorkspaceViewModel demoteToFile）
5. 消失端点（404 实测）：agentPresets/copy|deletePreset（UI 失败提示降级天然成立）、subagents/list（本地镜像递归软降级已就位）——注记不实现（rc 版可能回归）
6. V3 词汇表外新帧：workspace/changes（具名降级容错正常，E2E 实测出现）——待词汇表批次收编

教训：KDoc 内 `internal/*-failed` 文字中 `/*` 被解析为块注释开启（DshApiError.kt 曾编译失败）——注释里避免斜杠+星号相邻序列。

**测试**：DshApiErrorTest 期望表重写（64 码独立来源表）；DshEnvelopeTest 闭集数断言+fixture 码值斜杠化；DshApiClientTest submittedAttachments/gateway-result-invalid/gateway-cancelled/agent-preset 横杠码；WorkspaceViewModelTest 信号码。dsh 域+WorkspaceViewModel 558 绿 → 全量 3750 绿。

## #459 V2 2.0.19 重核（commit 8c6853b0）

- openapi.json 需认证（2.0.18 无凭据可访问——新变化）；138 路由 vs 2.0.18 的 115
- 14 漂移端点 **13 仍缺失**（漂移延续，降级路径维持）；pty/shells 实为已自愈项（V2ApiClient:1839 注释实证路由把 shells 当 ptyID，app 已不用）
- 适配两 URL：listPendingQuestions GET /api/form/request(404)→GET /api/form（curl 实测 {location,data:[]} 信封 flexibleList 兼容；Form.Info{id,sessionID,title,metadata,fields} 与 toQuestionRequest 消费面**逐字段一致**含 sessionID 大小写）；importSession POST /api/session/import→POST /api/experimental/session/import
- reply 通道 POST /api/session/{id}/form/{formID}/reply 2.0.19 在（app 已在用）
- v2 域测试 138 绿

## 真机 E2E（dev 包 @ houji，dsh020-live = 宿主 3080 live 0.2.0-rc.2）

- debug intent 注入（debug_server_type=dsh + debug_token journalctl 提取）：token 交换 ok，**会话列表 10 条完整渲染**（session/list+_request ✓）
- 点进会话：POST /api/session/page 200，**历史装配+markdown 全渲染**（标题/行内码/任务列表/代码执行卡/链接/模型耗时标注）✓
- 发送测试消息：**prompt request 包装送达**（AI 开始思考）、流式思考预览回流、工具卡（Run code 3.4s/失败 5ms 红叹号/重试）渲染、骨架自愈（step.started 丢失→seeded skeleton）、flush 批处理（deltas=4）全部正常；零 DshApiError/崩溃
- 中断回合（ESC）+ force-stop 收尾

## 阶段二 #439 重组放大复查（真机仪器实测）

**背景核对**：卡片四修点位中的预载项（ChatEntry.Turn isUser/isStreaming 构建时编码 + item lambda 不再捕获 displayItems/turnGroups/streamingMsgId，MarkdownChunking.kt:179-187 R4-B3 注释）**已在前批落地**；2026-09-29 的活证（单轮流式 TextDelta 2877 → InjCard 邻项 4443 次，1.5x）是 R4-B3 之前的数据。

**复查实测**（dev 包 @ houji，v2019-check v2.0.19 新会话，600 字散文流式轮 ~22s）：
- 流式批（SGR）145 批；InjCard 注入卡重组 **38 次**（22:40:49×2 发送时刻 / 22:40:55-59×28 流式期 / 22:41:01×6 / 22:41:19×2 完成时刻）
- 邻项重组率 **0.26 次/批**（历史基线 1.5 次/delta）——两个数量级下降，全量重组放大**实质已消除**；剩余 38 次为正常布局位移/状态重组（注入卡紧邻流式 turn）
- 回合渲染正常（散文全文完整、22.0s 完成、自动标题生效）

**残余失效链分析**（不动刀依据）：transcriptCardPlan（ChatMessageList.kt:1353 remember(chatEntries, displayItems, ...)）每流式批重算 → extras Map 新实例 → item 捕获失效——是剩余 38 次的来源。修法（ref-cache/结构签名化）与 #452 空白列表、回归 37d9a6ac 冻结两次前科同域，收益仅 38→个位数/轮，**并入 #442 高度引擎二期 R2 与「稳定/活跃双容器 append-only+前缀吸收」同批设计**（与 #470 并入 #442 同构裁决）。

同批 v2 E2E 副产物：#459 主链路真机验证（prompt POST /api/session/.../prompt 200、流式 SSE 全程、回合完成渲染、自动标题）。

## 阶段三 #488 分项处置

**①块内 HTML 原文呈现（已落地+E2E）**：mikepenz v0.45.0 对 HTML_BLOCK 零组件（javap 实证 MarkdownComponents 无 html 钩子；HTML_BLOCK ElementType 在 org.intellij.markdown.MarkdownElementTypes 非 GFM），分派走 custom(IElementType, model) 兜底（默认空=整块隐形根因）。覆写 custom：HTML_BLOCK → HtmlBlockRaw（等宽代码样式 + codeBlockBg/Fg 主题令牌 + 横向滚动 + 区间 clamp 截取防 #437 流式失配越界，失败回退整 content 宁多显不丢）；components remember 键补 codeBlockBg/codeBlockFg/typography.code。真机 E2E（v2 会话让 AI 产 div 块+正常句）：HTML 块原文完整可见、后续句子正常渲染——修复前该块整块隐形。
**④setext stage-2**：卡片原文即「维持 accepted-gap 备查」——无实现需求。技术论证补档：setext 不破坏前缀不变量（gate 放行文本追加式，=== 到达仅渲染层样式翻转 paragraph→heading=视觉闪变）；stage-2 唯一安全解=段落尾行扣留到下一行首可见，实质收窄全部纯文字增量直出节奏，收益（低频 setext 闪变）不抵代价（每段慢一行）。
**②③方案文档**（docs/research/2026-09-30-488-syntax-math-options.md，带两裁决点）：②推荐接官方 multiplatform-markdown-renderer-code:0.45.0（同版本线，引擎 dev.snipme:highlights 纯 Kotlin KMM 非 UI 库——不违红线；17 语言+增量缓存+sync/async）；备选 B 自写 top-5 lexer 零依赖。③推荐维持降级+着色/标注升级（方案 c）；WebView+KaTeX 混排割裂不推荐，KMM 数学排版生态未成熟。

## 已完结卡片迁入（2026-09-30）

### **#459 V2 2.0.18 消费侧 14 端点漂移清单（health/question|form request/pty shells/share/rename/service stop 等 404）** `regression,v2,data`
  - app 调用面 45 点中 14 点在 2.0.18 openapi 缺失（全 404 实证）；真机主链路不受影响（探测器/PATCH session 等降级路径实证），但 question/form 轮询兜底、pty shells、share、service/stop 在 2.0.18 下不可用。详见回归报告 §1.2/缺陷 D2
  - 2026-09-30 #482 实证副产物：部署版已升 v2.0.19（回归报告基于 2.0.18）——开工时漂移清单需对 2.0.19 重核（/api/version、/api/app/version、/api/health 均已实测 404）
  - 2026-09-30 阶段一B 重核+适配完结：2.0.19 openapi 138 路由（openapi.json 需认证=新变化）对齐 14 漂移点——13 仍缺失（漂移延续，降级路径维持）；两小项适配落地：form 轮询 URL /api/form/request→/api/form（信封与字段逐一对齐实证）、session/import→/api/experimental/session/import；pty/shells 实为已自愈项。v2 域测试 138 绿
  - 2026-09-30 0.2.0-rc.2 live E2E（dsh020-live:3080）副产物：①POST /api/session/prompt 受理正常、流式/渲染/轮次键稳定（t_seq-* grp=1 无漂移）；②[MsgEventHandler][skeleton] orphan part host missing→step.started 丢失自愈 在每轮流式首步稳定出现（0.2.0 part 事件先于 host 消息到达，自愈有效非阻塞，漂移信号留档）；③workspace.list 404（0.2.0 端点移除，app 已优雅降级）——归入本卡漂移清单
  - 迁入依据：2026-09-30 用户验收通过（模块 B 演示『Bok』：v2 列表/新建/流式回合/双路回退全通过，附带双发对照证据亦收）（backlog.sh migrate 2026-09-30）

### **#439 流式期重组隔离：entries 签名缓存与子卡 skippability 恢复** `streaming` `compose`
  - 流式批（~14/s）仍使流式 turn + 相邻注入卡条目全量重组（真机 35s 524 次）；渲染像素幂等故非闪烁源，属性能债。
  - 修复位：ChatMessageList.kt:741 chatEntries 键改结构签名（仿 :283 turnGroups sig-cache）；MarkdownChunking.kt:291-313 ChatEntry 预载 msg/streaming/key；:2448-2487 item lambda 消除 displayItems/turnGroups 直读；:1361-1375/:1772 回调 lambda remember 化。
  - 2026-09-29 E2E 活证:单轮流式 TextDelta 2877→InjCard 邻项全量重组 4443(1.5x);另一轮 1387→2523(1.8x)——逐 delta 重组放大实测,修点位与量级依据齐
  - 2026-09-30 阶段二复查（真机仪器）：卡片修点位 2/3 已被前批 R4-B3 收编（ChatEntry 预载+身份编码，2026-09-29 的 4443 活证是其之前数据）；实测 v2.0.19 流式轮 145 批 InjCard 38 次（0.26 次/批 vs 基线 1.5 次/delta）——全量重组放大实质消除；残余链（transcriptCardPlan 每批重算 extras 新实例）收益微小且与冻结前科同域，并入 #442 R2 设计
  - 迁入依据：2026-09-30 用户验收通过（模块 D：仪器面 88 次/轮 vs 基线 4443=1/50、视口外零重组、零负向/零前缀破坏；体感面『其他的没啥问题』）；残余 transcriptCardPlan 每批重算并入 #442 R2（卡内已注记）（backlog.sh migrate 2026-09-30）
