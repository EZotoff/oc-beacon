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
