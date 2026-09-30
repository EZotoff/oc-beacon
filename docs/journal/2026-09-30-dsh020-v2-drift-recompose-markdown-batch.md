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
