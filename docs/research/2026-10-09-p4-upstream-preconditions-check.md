# P4 上游前提重验 — 2026-10-09（源码+官方文档双面）

> 问题：backlog 五张 P4 卡的上游前提（dsh 服务器能力 / opencode 上游修复）现在是否满足？
> 方法：两个调研 subagent 并行——A 路 = npm `@deepseek-ai/dsh` 解包子包源码 grep + GitHub `deepseek-ai/deepseek-harness` + release notes；B 路 = shallow clone `anomalyco/opencode` HEAD 源码 + issue 面 + 官方文档站。全部只读取证，未触现网服务器、未改仓库代码。
> 基线：DSH 上次核查 2026-09-29（0.1.7-rc.2）；OpenCode 上次逐项取证 2026-09-02（@69c172e）。
> 证据原件：/tmp/dsh-check/（npm 解包）、/tmp/opencode-upstream-check（克隆）——本文件为合并存档。

---

# A 路：DSH 上游（#352 / #332 / #288 / #381）

# DSH 上游能力核查报告 — 2026-10-09

核查对象：npm 包 `@deepseek-ai/dsh`（DeepSeek dsh 服务器）
上次核查：2026-09-29（当时 npm latest = 0.1.7-rc.2，生产 live = 0.2.0-rc.2）
本次方法：npm pack 直连（无代理）→ /tmp/dsh-check 解包子包 → 源码 grep + GitHub 公开仓库（deepseek-ai/deepseek-harness）+ 官方 release notes / 部署文档交叉验证。

---

## 0. 版本时间线与包结构（本次重大方法学发现）

### 版本清单（0.1.7-rc.2 之后）

| 版本 | 发布时间 (UTC) | dist-tag |
|------|------|------|
| 0.1.7-rc.2 | 2026-09-24 | （上次核查基线） |
| **0.2.0-rc.1** | 2026-09-28 | — |
| **0.2.0-rc.2** | 2026-09-29 | `latest` / `next` |
| **0.2.1-alpha.1** | 2026-10-03 | `alpha`（当前最新） |

无新 stable；`latest` 仍是 0.2.0-rc.2。GitHub Releases 与 npm 一一对应（`dsh-v0.2.1-alpha.1` = 2026-10-03，作者 tianyicui；`dsh-v0.2.0-rc.2` = 09-29，作者 imccyu）。

### 包结构（勘误：历史核查方法有盲区）

- **顶层 `@deepseek-ai/dsh` 是薄 CLI 启动器**：tarball 仅 20 个文件（bin.js / dump-config / profile-boot 等），不含业务代码。真实代码在 ~150 个 `@deepseek-ai/dsh-*` 子包中**平行发版**（同一版本号同日发布）。
- **0.2.0-rc.2 与 0.2.1-alpha.1 顶层包代码字节级相同**（diff 仅 package.json 的版本引用），子包才有实质差异（部分子包另有 0.2.1-alpha.2）。
- ⚠️ **2026-09-29 复验结论（"0.1.7-rc.2 全仓无 unarchive"）系方法学错误**——当时只查了顶层薄包。子包 dsh-api-workspace-controller@0.1.7-rc.2 实际已含 unarchive 动词（0.1.6-alpha.1 引入）。tool-workflow 事件同理（见 ③）。
- GitHub 仓库 **公开**：`github.com/deepseek-ai/deepseek-harness`（MIT，~24.6 万 star，master 分支，含全部源码与 E2E 测试）。

本次解包清单（均在 /tmp/dsh-check/）：顶层 dsh@0.2.0-rc.2 / 0.2.1-alpha.1；子包 api-workspace-controller（0.1.5-rc.3 / 0.1.6-alpha.1 / 0.1.7-rc.2 / 0.2.0-rc.1 / 0.2.0-rc.2 / 0.2.1-alpha.1）、dsh-session（0.1.7-rc.2 / 0.2.0-rc.1 / 0.2.1-alpha.2）、dsh-tool-workflow（0.1.2-alpha.2/3/4 / 0.1.2-rc.1 / 0.1.7-rc.2 / 0.2.0-rc.1/rc.2 / 0.2.1-alpha.1）、dsh-client-connection@0.2.1-alpha.2、dsh-credentials-local、dsh-host-webserver、dsh-host-frontend-static、dsh-spill-policy、dsh-spill、dsh-output-retention、dsh-llm、dsh-api-session-controller、dsh-session-projection、dsh-client-ui-session/sidebar(-browser)/layout/modules、dsh-web-app（均 0.2.1-alpha.x）。

---

## ① #352 取消归档动词 —— **前提满足（上游已全面落地）**

**结论：满足。** RPC 动词、跟随流增量、会话闸门、官方 Web UI 菜单、E2E 测试、release notes 六面证据齐全。

### 证据（子包 @deepseek-ai/dsh-api-workspace-controller@0.2.1-alpha.1，路径相对于 /tmp/dsh-check/sub/deepseek-ai-dsh-api-workspace-controller-0.2.1-alpha.1/package/）

1. **RPC 路由与请求类型**（`lib/typert.remote-client.d.ts:42`）：
   ```
   'workspace/unarchiveSession': (request: WorkspaceUnarchiveSessionRequest) => Promise<RemoteResult<WorkspaceArchiveValue>>
   ```
   `lib/types/types.d.ts:123`：
   ```ts
   /** Session requested for restoration from the archived Session list. */
   export interface WorkspaceUnarchiveSessionRequest { readonly sessionId: SessionId; }
   ```
2. **Host 实现**（`lib/index.js:317-318`）：`async unarchiveSession(request) { await this.ctx.workspaceRegistry.unarchiveSession(request.sessionId); … }`；幂等语义（`lib/types/commands.d.ts:50-54`）："Drop one Session from the registry-global archive set. An id that is not archived is not an error: the call is idempotent"。客户端便捷方法：`lib/types/client/service.d.ts:84-87` `unarchiveSession(sessionId): Promise<void>`（"Unarchive a Session from the archived Session list"）。
3. **归档面同期增强**：`WorkspaceArchiveSessionRequest` 新增 `stopActivity?: boolean`（`lib/types/types.d.ts:113-119`，归档活跃会话时先停其 turn/子代理/后台 job/schedule，替代原来的 `workspace/session-active` 拒绝）。
4. **引入版本**：子包 bisect —— 0.1.5-rc.3 = 0 命中，**0.1.6-alpha.1（2026-09-15 发布）= 14 文件命中**；此后所有版本（含 0.1.7-rc.2、0.2.0-rc.x、0.2.1-alpha.1）均有。
5. **跟随流增量**（`lib/types/types.d.ts:157-160,167-171`）：`workspace/follow` RPC 流的 `WorkspaceFollowIncrement` 含 `{ type: 'archived', archivedSessionIds: readonly SessionId[] }` —— 客户端可实时跟随归档集变化。
6. **会话闸门**（dsh-api-session-controller `lib/types/archived-session-gate.d.ts`）：归档会话（含子代理血统）不得执行模型步，"unarchiving lifts the gate for the whole lineage" —— 取消归档有真实运行时语义。
7. **官方 Web UI 菜单**（GitHub 源码 `apps/web/tests/session-unarchive.e2e.ts`，文件头注释："Archive and restore through sidebar row menus and the archived filter, then reload from the Host baseline."）：测试点击 `menuitem { name: 'Unarchive session' }`，前置 `View options → All conversations (show archived)` 过滤器，并验证 `workspaceRegistry.archivedSessionIds` 清空 + reload 后保持。
8. **文档面**：GitHub Release `dsh-v0.1.7-rc.1`（2026-09-23）："Manage archived sessions with pinning, filters and restoration; confirm affected tasks before archiving a running session."；`dsh-v0.1.7-rc.2`："Choose Hide archived, All conversations, or Archived only."（https://github.com/deepseek-ai/deepseek-harness/releases）

**对 oc-beacon 的意义**：#352 可以解封。需确认实际连接的服务器 ≥ 0.1.7-rc.1（生产 09-29 已在 0.2.0-rc.2，满足）。接口形：`workspace/unarchiveSession`（typert RPC）或经 API Gateway 等价路由，请求 `{sessionId}`，响应完整 `WorkspaceArchiveValue`。

---

## ② #332 spill 结构化信号 —— **未满足（仍为纯文本约定，上游文档自认）**

**结论：未满足。** session 事件枚举与 tool/result 事件载荷仍无任何结构化 spill 字段/事件；溢出提示仍全部内嵌在工具结果 output 文本里。唯一进展是上游把"文本约定"正式化了（提供 hasSpillNotice 识别器 + 固定拼写格式），并承认其不可认证性。

### 证据

1. **事件枚举无 spill**：dsh-session@0.2.1-alpha.2 `lib/types/known-event-types.js` 全部 61 个事件类型（agent-preset/selected … workspace/changes）中无 spill / overflow / truncat 类（逐项核对）。
2. **tool/result 载荷无结构化字段**（dsh-session `lib/types/types.d.ts:374-388`）：
   ```ts
   'tool/result': { turn: number; step: number; message: ToolResultMessage; error?: {name,code,reason?}; meta?: JsonValue; }
   ```
   `meta` 是通用 JSON 逃生舱非 spill 契约。`ToolResultMessage`（dsh-llm `lib/types/message.d.ts:153-159`）仅 `role/source/toolCallId/isError`，dsh-llm 全包 spill 命中 0。
3. **上游自认文本约定**（dsh-spill-policy@0.2.1-alpha.1 `lib/types/notice.d.ts`）：
   ```ts
   /** Recognize a final spill-policy notice in persisted text …
    * This identifies the text convention, not authenticated tool-output origin. */
   export declare function hasSpillNotice(text: string): boolean;
   ```
   README "Known Limitations" 第一条原文："**Text recognition cannot authenticate output** — a tool can print the same notice text; `hasSpillNotice` identifies a text convention, not proof that the policy saved a result."
4. **dsh-spill / dsh-output-retention 无事件发射**：两包 grep `SessionEventMap|session.append|sessionEvent` = 0 命中；导出的 `SpillRef/SpillLocator/SaveTextSpill` 是 Context 服务内部 seam（dsh-spill `lib/types/index.d.ts:19,48`），不进 transcript。
5. **变化点（相对 09 月取证）**：配置从 `maxInlineBytes` 改为 `maxInlineTokens`（release `dsh-v0.1.7-alpha.2`：文本+图像共享估算 token 预算）；notice 现在同时报告省略的图像数量。模型可见格式仍是纯文本：
   ```
   (Omitted N bytes. Full formatted result stored at: /…/session-…/…-web_fetch.txt. Use read with offset/limit, or grep this path to search within it.)
   ```
   （dsh-spill-policy README "What the model sees" 节）

**对 oc-beacon 的意义**：#332 继续阻塞。客户端文案匹配仍是唯一途径，但可参照上游 `hasSpillNotice`/`formatSpillNotice` 的固定拼写实现（notice.d.ts + notice.js，浏览器安全、零依赖）比自造正则可靠。

---

## ③ #288 tool-workflow 运行事件 —— **前提满足（journal 事件已存在且经 follow 流直达客户端）**

**结论：满足。** 会话 journal 现有 4 个 tool-workflow 运行事件（顶层调用默认记录、无开关），并经 `session/follow` 流以原始事件形式送达远端客户端。

### 证据

1. **事件枚举与载荷**（dsh-session known-event-types.js 实测含以下 4 项；dsh-tool-workflow@0.2.1-alpha.1 `lib/types/types.d.ts:39-54` 声明 SessionEventMap 增强）：
   ```ts
   'tool-workflow/run-start':   { runId, name }
   'tool-workflow/agent-start': { runId, seq, label, phase?, childId: SessionId }
   'tool-workflow/agent-end':   { runId, seq, outcome }
   'tool-workflow/run-end':     { runId, stopReason }
   ```
2. **发射点**（dsh-tool-workflow `lib/index.js:98-127`）：`append(session, "tool-workflow/agent-start", {...})` 等；门控仅一条（`lib/index.js:401`）`const recordsRun = exec.parent === void 0` —— **顶层 workflow 调用一律记录，非嵌套即可，无配置开关**。前台/后台（run_in_background job）两路都接 recorder（`lib/index.js:235,402`）。
3. **客户端可达性**：dsh-api-session-controller `lib/typert.remote-client.d.ts:46` `'session/follow'` 流返回 `SessionFollowFrame`（`lib/types/types.d.ts:516-527`），其中 `SessionEventEntry = { type: 'event', event: SessionWireEvent }`，`SessionWireEvent`（`lib/types/types.d.ts:432-442`）为 `{type: string, seq, time, data, ignorable?}` —— **原始 journal 事件原样透传**。`session-wire-event.d.ts` 头注释："raw Session journal responses; payloads remain owner-defined JSON … received in a follow frame or history page"。冷启动历史分页 `SessionPageRequest` 同源。
4. **上游测试断言**（GitHub `packages/workflow/tool-workflow/tests/tool-workflow.spec.ts:172-179`）：
   ```ts
   expect(session.snapshotEvents().map(event => [event.type, event.data])).toEqual([
     ['tool-workflow/run-start', { runId: 'run-1', name: 'audit' }],
     ['tool-workflow/agent-start', {…}], ['tool-workflow/agent-end', {…}],
     ['tool-workflow/run-end', { runId: 'run-1', stopReason: 'completed' }]])
   ```
   另有 `apps/web/tests/workflow-run.e2e.ts`（Web 端 workflow 树 E2E）。
5. **引入时间与历史矛盾说明**：子包 bisect —— dsh-tool-workflow **0.1.2-alpha.2（2026-08-30）已含全部发射代码**，0.1.2-alpha.3/4、0.1.7-rc.2、0.2.x 均在。09-01/09-02 四面夹击取证"无 tool-workflow 事件"与 npm 代码不符，最可能是当时 live 服务器版本更旧（≤0.1.2-alpha.1/0.1.1-rc.x）或探测面未覆盖 journal 原始事件流（events.mux 转发的是 `api-session/*` 远端事件与投影，`remote-events.d.ts` 仅 5 种；tool-workflow 事件走 session/follow 的 SessionEventEntry 通道）。**建议对现网 0.2.x 服务器做一次 journal 复测**（跑一个顶层 workflow，grep session.history / follow 流中 `tool-workflow/`）。
6. **边界**：dsh-session-projection 不解释这些事件（journal-only，不进消息投影/表面）——客户端需消费 follow 流的原始事件帧而非消息投影。
7. **文档面**：release `dsh-v0.1.7-rc.1` headless 新增 "emit run events as newline-delimited JSON with --json"（旁证运行事件一等公民化）。

---

## ④ #381 远程凭据探知 —— **未满足（设计立场未变：token 只从启动 stdout 出，无任何自动发现/替代认证）**

**结论：未满足。** 无 token 签发端点、无 mDNS/bonjour、无 OAuth/设备码、无查询运行中服务器 token 的 CLI。官方远程部署路径 = 反向代理 + TLS + `--trusted-host`，token 仍是打印在启动 URL 里的进程凭证。

### 证据

1. **token 铸造仍是进程内 WeakMap**（dsh-client-connection@0.2.1-alpha.2 `lib/index.js:275,291`）：`const PROCESS_LAUNCH_TOKENS = new WeakMap()`；`encodeBase64Url(randomBytes(SECRET_BYTES))`。`browser-auth.d.ts`："Process launch-token exchange and persistent signed-cookie verification … retaining one token across Connection reloads"。
2. **官方 README 原文**（dsh-client-connection README "Browser authentication and request trust" 节）：
   > "Every Host RPC method and WebSocket stream requires one browser session; there is no method-specific loopback tier. **Each process mints a random launch token. `dsh-web-app` prints and opens its application URL with `?token=...`** … The HTTP carrier accepts **no query token outside the root exchange and no Authorization-header token**." / "**`dsh web --host 0.0.0.0` is unsupported.**"
3. **全包 grep 0 命中**：`oauth|device_code|mdns|bonjour|zeroconf|discovery|pairing`（顶层 + 10 个关键子包）。唯一 'oauth' 命中是 dsh-credentials-local README:97 的 **LLM 提供商凭据示例**（`llm-pi-ai/openai-codex` 的 grant payload `type: oauth`）——属"调用模型 API 的凭据存储"，与 Host 连接认证无关。
4. **CLI 无 token 子命令**：顶层 dsh `lib/bin.js` 全部命令面 = `--profile/--patch/--dump-config*` 选项 + `plugin` 子命令。`dsh web`（dsh-web-app startup.js）选项 = `--host/--port/--public-url/--trusted-host/--no-open`，无 token 查询。
5. **官方部署文档**（GitHub `docs/user/guide/public-deployments.md`）：
   > "The fence only admits the request; **the launch token in the printed URL and the signed cookie authenticate it.**" / "The printed URL carries a process credential, so share it only with intended users."
   无 discovery/pairing/OAuth/device-code 任何字样。
6. **变化点（对客户端有边际价值，但非探知）**：
   - cookie 机制成熟：签名 secret 持久化于 `$DSH_HOME/.credentials.yaml` 的 `client-connection/browser-session` grant 记录（**secret 持久 ⇒ cookie 跨服务器重启有效**），默认 30 天绝对过期，authority 绑定（hostname+port 进签名），HttpOnly/SameSite=Strict，TLS 时带 Secure。
   - 即：**token 一次性兑换 → 长期 cookie** 的模式现在被上游正式支持（`GET /?token=…` 换 cookie 后 token 从 URL 消除）。oc-beacon 若拿到一次 token 可换 30 天会话——但 token 的获得途径仍然只有启动 stdout 一条，"远程探知"前提仍未出现。
   - `--trusted-host`/`trustedHosts`（`api-request-trust.d.ts`）是 Host-header 围栏（防 DNS rebinding/跨站），文档明言 "this fence **never establishes identity**"；`--public-url` "grants no trust"，纯通告。

**对 oc-beacon 的意义**：#381 继续阻塞，维持 09-04 结论（token=RCE 等价物，设计使然）。可关注点：cookie 持久化后，未来可探索"token 兑换 cookie 后复用"以减少重启轮换的痛点（上游已把该路径文档化）。

---

## 总表

| 卡片 | 需求 | 上游前提 | 最强证据 |
|------|------|----------|----------|
| ① #352 | 取消归档动词 | **满足**（0.1.6-alpha.1 引入 API，0.1.7-rc.1 UI+restoration 落地） | `workspace/unarchiveSession` RPC + 官方 E2E `'Unarchive session'` 菜单项 |
| ② #332 | spill 结构化信号 | **未满足** | 上游自认 "identifies a text convention, not authenticated tool-output origin"；tool/result 载荷无 spill 字段 |
| ③ #288 | tool-workflow 运行事件 | **满足**（≤0.1.2-alpha.2 起，0.2.x 在） | journal 事件 4 项 + `session/follow` 原始事件透传 + 上游测试快照断言 |
| ④ #381 | 远程凭据探知 | **未满足**（设计未变） | "no query token outside the root exchange and no Authorization-header token"；`dsh web --host 0.0.0.0` is unsupported；oauth/mdns/discovery 全 0 命中 |

### 建议后续动作
1. #352、#288 可进入实现排期（先对现网 0.2.x 服务器各做一次实测冒烟：unarchive 往返 + journal 中 tool-workflow 事件）。
2. #332、#381 维持阻塞，登记下次复查（关注点：spill 结构化字段、token 签发/配对机制）。
3. 修正历史调研记录：09-29 复验的方法学错误（顶层薄包 ≠ 全仓）；后续核查一律查 `@deepseek-ai/dsh-*` 子包 + GitHub 源码。

---
*证据文件均在 /tmp/dsh-check/（本地解包），GitHub 引用为 raw.githubusercontent.com / releases 页面，npm 元数据来自 registry 直连。*

---

# B 路：OpenCode 上游（#146 ①②③④⑤⑥）

# OpenCode 上游能力调研 — backlog #146 五项核查

- **日期**: 2026-10-09
- **仓库**: https://github.com/anomalyco/opencode（默认分支 `dev`）
- **HEAD**: `388406238bd5ca15564a762840a2362c3a45bd9c`（`3884062`，2026-10-08 09:24:52 -0700，"docs(web): remove Fledge Alpha Free from Zen docs (#53984)"）
- **克隆**: /tmp/opencode-upstream-check（depth 1，直连成功）
- **版本参照**: npm `opencode-ai` latest = **1.18.35**（2026-10-06）；并行存在 **v2.0.x tag 系列，最新 v2.0.26**（tag 于 2026-10-08，无 GitHub Release 对象）。dev HEAD `packages/opencode/package.json` version = 1.18.35，v2.0.x 从同一开发线切 tag。
- **上游取证基线**: 2026-09-02 @69c172e（本次为一个月后复查）
- **布局变化**: 服务器源码已大幅重组——`packages/opencode/src/` 仍在（V1 兼容引擎 + httpapi 路由），新增 `packages/core/src/`（V2 核心引擎：session runner、event、schema 等）与 `packages/schema/src/`（事件/消息 schema）、`packages/server/src/`（独立 server 包）、`packages/sdk/js/src/v2/`（V2 生成 SDK）。

---

## ① V2 不发 compaction.started — **已修（V2 core 引擎）；V1 兼容路径仍是单事件**

### 证据（dev HEAD）

1. **Schema 三档齐全**：`packages/schema/src/session-event.ts:397-431` — `session.next.compaction.started`（字段 `messageID` + `reason: "auto" | "manual"`）、`session.next.compaction.delta`（`text`）、`session.next.compaction.ended`（`reason`/`text`/`recent`）。
2. **引擎实际发射 Started**：`packages/core/src/session/compaction.ts:192-199`：
   ```ts
   yield* dependencies.events.publish(SessionEvent.Compaction.Started, {
     sessionID: input.sessionID, messageID, timestamp: yield* DateTime.now, reason: "auto",
   })
   ```
   摘要 LLM 流式开始前发布；`compaction.ts:222-231` 在流结束后发布 `Ended`（带 summary text 与 recent）。
3. **接线**：`packages/core/src/session/runner/llm.ts:109`（`SessionCompaction.make` 注入 runner）、`llm.ts:224`（`compactIfNeeded` 在每次请求前判定）。core 执行面经 `packages/core/src/session/execution/local.ts`（SessionRunCoordinator → SessionRunner）挂进 server（`packages/opencode/src/session/session.ts:14`、`server/routes/instance/httpapi/server.ts` 引用）。
4. **消费端**：`packages/core/src/session/message-updater.ts:375-376` 处理 started/delta（no-op 占位）；`packages/core/src/session/projector.ts:393` 投影 Ended；测试 `packages/opencode/test/v2/session-message-updater.test.ts:207-248` 覆盖三档事件；V2 SDK 生成类型含全部三档（`packages/sdk/js/src/v2/gen/types.gen.ts`）。

### 保留意见

- **V1 兼容引擎仍只有 `session.compacted` 单事件**：`packages/opencode/src/session/compaction.ts:554`（`events.publish(Event.Compacted, { sessionID })`），schema 在 `packages/schema/src/session-compaction-event.ts:7`。httpapi 手动压缩端点 `/session/:id/summarize`（handlers/session.ts:282 调 `compactSvc.create`）走的正是这条 legacy 路径，无 started 档。
- Related issue: #36187（OPEN，"V2: clarify admitted vs started event handling for session pending/compaction"）——admitted/started 分档语义仍在清理中。

**结论**：oc-beacon 关注的 V2 事件流（session.next.*）现在具备完整 compaction started/delta/ended 生命周期，**已修**；若客户端仍消费 V1 兼容事件，则该路径行为未变。

---

## ② SSE 重连无事件回溯 — **未修（与 2026-09-02 结论一致）**

### 证据（dev HEAD）

1. **主实例 `/event` 端点**：`packages/opencode/src/server/routes/instance/httpapi/handlers/event.ts`
   - `:12-19` `eventData()` 构造 SSE 帧时 `id: undefined` —— **SSE 协议层 `id:` 行从不发出**（事件 id 只在 JSON payload 里）；
   - `:25-41` 连接时注册 eager listener → unbounded queue，纯 live 订阅；初始只补一个 `server.connected`（`:70`）+ 10s heartbeat（`:63-66`）；
   - **全 handler 无读取 `Last-Event-ID` 请求头的任何代码**（全仓库 grep `Last-Event-ID|lastEventId` 仅命中 SDK 客户端发送侧与一个断言"不发送"的 e2e）。
2. **独立 server 包同样**：`packages/server/src/handlers/event.ts:11-18`（`id: undefined`）、`:23-49`（connected + live 流 + 15s heartbeat，无回溯）。
3. **文档**：`https://opencode.ai/docs/server/` 仅写 "First event is server.connected, then bus events"，无重连/replay/Last-Event-ID 语义。
4. **Issue 面**（均为 NOT_PLANNED 关闭，即官方明确不做）：
   - #25657（CLOSED NOT_PLANNED）"/global/event SSE stream loses events on reconnect (no Last-Event-ID support)"
   - #29544（CLOSED NOT_PLANNED）"SSE event streams omit standard event IDs"
   - #19584（CLOSED NOT_PLANNED）"/event replay semantics are implicit and conflict with SSE backpressure handling"
   - 后果类仍开：#38458（SSE mid-turn 断流）、#52361（Web 错过 /global/event 后 session 陈旧、无 per-session catch-up）、#52377（/event 无重放游标）。

**结论**：**未修**，且三条相关 bug 均 NOT_PLANNED 关闭——官方立场是客户端靠快照/重拉而非事件回溯。

---

## ③ cursor V1 格式返回 400 — **未修（与 2026-09-02 结论一致）**

### 证据（dev HEAD）

1. **cursor 编解码**：`packages/opencode/src/session/message-v2.ts:62-78`
   ```ts
   const Cursor = Schema.Struct({ id: MessageID, time: Schema.Finite... })
   const decodeCursor = Schema.decodeUnknownSync(Cursor)
   decode(input) { return decodeCursor(JSON.parse(Buffer.from(input, "base64url").toString("utf8"))) }
   ```
   严格要求 base64url 编码的 `{"id": msgID, "time": number}`，任何 V1 风格（裸 messageID 等）直接 throw。
2. **400 路径原样**：`packages/opencode/src/server/routes/instance/httpapi/handlers/session.ts:107-112`
   ```ts
   if (ctx.query.before) {
     yield* Effect.try({ try: () => MessageV2.cursor.decode(before), catch: () => new HttpApiError.BadRequest({}) })
   }
   ```
   无任何降级/兼容分支（`catch` 直接映射 400）。
3. **Issue 面**：#48034（OPEN，2026-09-08）"Message pagination accepts malformed cursor characters"——方向相反（嫌校验太松），说明官方在收紧而非放宽 cursor 校验，V1 格式兼容无动议。

**结论**：**未修**。oc-beacon 需继续在客户端侧只产 V2 格式 cursor。

---

## ⑥ V2 后台 shell 状态恒 completed — **未修（2.0.x 发行线原样；dev HEAD 已整体移除该表面待重做）**

### 证据 A：发行线 v2.0.26（oc-beacon 实际会跑到的 2.0 系列）

经 GitHub API 取 tag `v2.0.26` 的 `packages/core/src/tool/plugin/shell.ts`（该文件在 dev HEAD 已不存在）：

- `:73` `status: Schema.optionalKey(Schema.Literals(["completed", "running"]))` —— **状态字面量只有两档，无 error**；
- `:216-231` run effect 拿到 `shell.result(info)` 后**无条件** `status: "completed" as const`；exit code 仅进 structured 字段（`:62-67` `exit`/`signal`/`truncated`/`timeout`），失败信号只在正文文本（`ShellResult.notice`）；
- job 失败态判定（`:267-269`）只覆盖 `NotFoundError`/取消路径，不覆盖非零退出码。

即 2026-09-02 观察到的"exit 7 亦 completed"在 2.0.26 源码层面**原样存在**。

### 证据 B：dev HEAD（未来方向）

- `packages/core/src/tool/plugin/shell.ts` 已删除；`ShellNotFoundError`、`/api/shell` 端点在 HEAD 全仓库无命中（V2 SDK gen 也无该端点，只剩 `/pty/*` 与 `/session/:id/shell`）。
- `packages/core/src/tool/bash.ts:72-74` TODO 明示重建计划：
  ```
  // TODO: Persist background job status and define restart recovery before exposing remote observation.
  // TODO: Re-add model-facing background launch only with owner-bound get/wait/cancel tools and completion delivery.
  // TODO: Add HTTP background-job observation only after durable status, restart recovery, and authorization are defined.
  ```
- 新 `BackgroundJob` 注册表（`packages/core/src/background-job.ts:146-150`）已按 Effect exit 派生 `completed/cancelled/error` 三态——但当前仅 background subagent task 使用（`handlers/experimental.ts:163-170` 过滤 `job.type === "task"`），shell 不在其列。
- V2 core bash 工具（`packages/core/src/tool/bash.ts:186-196`）非零退出码仍走 success 分支：返回 `{ exit: result.exitCode, output, truncated }`，tool part status 仍 completed；仅 `ToolFailure`（spawn 失败等）才 error（registry.ts:64-66）。

### Issue 面

- #36424（OPEN，label 2.0）"v2: shell: no way to stop a running background command"——确认 2.0 线后台 shell 存在且缺 stop 能力；
- #52908（CLOSED NOT_PLANNED）60 分钟 idle 驱逐杀后台 shell；#51828（OPEN）同类。

**结论**：**未修**。2.0.x 发行线行为与 2026-09-02 取证一致；dev HEAD 把后台 shell 表面整个拆掉、按"durable status + owner-bound 工具 + 完成 delivery"重做中，重建后的状态模型（BackgroundJob error 态）理论上能解决，但落地前无变化。

---

## ④ fork handleRaw bug — **已修且在 HEAD 仍然在（对照项维持）**

### 证据（dev HEAD）

`packages/opencode/src/server/routes/instance/httpapi/handlers/session.ts:218-230`：

```ts
const forkRaw = ... function* (ctx) {
  const body = yield* Effect.orDie(ctx.request.text)
  if (body.trim().length === 0) return yield* fork({ params: ctx.params })   // 空 body 分支仍在
  const json = yield* tryParseJson(body)
  const payload = yield* Schema.decodeUnknownEffect(ForkPayload)(json).pipe(
    Effect.mapError(() => new HttpApiError.BadRequest({})),
  )
  return yield* fork({ params: ctx.params, payload })
}
```

- `fork`（`:206-215`）消费 `payload?.messageID`（可选）；`ForkPayload = Struct.omit(Session.ForkInput.fields, ["sessionID"])`（`groups/session.ts:59`）→ `{messageID?}`。
- 端点注册 `:425` `.handleRaw("fork", forkRaw)`。

**结论**：2026-09-02 确认的修复（空 body 分支 + `{messageID?}` payload）在当前 HEAD **完好**。当时"运行版未跟上"的问题取决于部署版本 ≥ 修复版即消失。

---

## ⑤ 工具输出截断语义（开放 FR）— **部分进展：截断信号与全文落盘路径已进 schema**

### 证据（dev HEAD）

1. **part 级新字段 `outputPaths`**：`packages/schema/src/session-message.ts:100-101`（`ToolStateCompleted.outputPaths: Schema.Array(Schema.String)`，optional）；事件侧同步 `packages/schema/src/session-event.ts:349`。
2. **统一输出限幅服务**：`packages/core/src/tool-output-store.ts:13-14`（`MAX_LINES = 2_000` / `MAX_BYTES = 50KiB`），`bound()`（`:44`）超限时把全文写入存储、返回有界预览 + `outputPaths`；V2 工具结算统一走它（`packages/core/src/tool/registry.ts:69-83` `resources.bound(...)`）。
3. **shell 工具显式 truncated 标志**：`packages/opencode/src/tool/shell.ts:575-590` —— 输出前缀 `"...output truncated...\n\nFull output saved to: {file}"`，metadata 含 `truncated` 与 `outputPath`；截断上限可配（`tool_output` config，`packages/opencode/src/tool/truncate.ts:41-46`）；截断文件 7 天保留清理（`truncate.ts:11`）。
4. v2.0.26 后台 shell 的 StructuredOutput 同样有 `truncated: Schema.Boolean`。

### Issue 面

- 无针对"客户端读截断语义"的专门 FR 关闭记录；周边仍开：#45910（OPEN，"glob (V2) drops the truncation flag, model can't tell results were limited"——**截断标志在部分工具仍丢失**）、#48783（OPEN，TUI 折叠行未截断工具输入）。

**结论**：**部分**——机制面（`outputPaths` + `truncated` + 全文落盘）已落地进 schema 与主流工具，但 per-tool 一致性未收口（glob 丢标志仍开）。oc-beacon 可开始按 `outputPaths`/`truncated` 字段做 UI 提示，兼容旧版需判字段缺失。

---

## 汇总表

| # | 问题 | 结论 | 最强证据 |
|---|------|------|----------|
| ① | V2 不发 compaction.started | **已修**（V2 core 引擎；V1 兼容路径仍单事件） | core/session/compaction.ts:192 `publish(SessionEvent.Compaction.Started, …)` + schema session-event.ts:397 |
| ② | SSE 重连无事件回溯 | **未修** | handlers/event.ts:16 `id: undefined`、无 Last-Event-ID 读取；#25657/#29544/#19584 均 NOT_PLANNED |
| ③ | cursor V1 格式 400 | **未修** | handlers/session.ts:107-112 `catch: () => new HttpApiError.BadRequest({})` 原样 |
| ⑥ | 后台 shell 状态恒 completed | **未修**（2.0.26 线原样；dev HEAD 移除待重做） | v2.0.26 plugin/shell.ts:73 `Literals(["completed","running"])` + :231 无条件 `status: "completed"`；HEAD 无 /api/shell |
| ④ | fork handleRaw（对照） | **已修仍在** | handlers/session.ts:222 空 body 分支 + :226-229 ForkPayload |
| ⑤ | 工具输出截断语义 | **部分** | schema session-message.ts:101 `outputPaths` + core/tool-output-store.ts bound()；#45910 仍开 |

## 对 oc-beacon 的提示

- ①：升级到带 V2 core 引擎的版本后，可监听 `session.next.compaction.started/delta/ended` 驱动压缩 UI；需确认目标发行版本走 core runner 还是 legacy 路径。
- ②③：上游明确不做（NOT_PLANNED），backlog 侧维持客户端自救方案（重连后全量快照重拉 + 只产 V2 cursor）即为终态。
- ⑥：短期无解；dev HEAD 的重建方向（durable status + error 三态）值得在下一轮复查时跟进，届时 oc-beacon 的后台 shell 状态展示需适配新 API 形态。

---

# 合并总裁决（2026-10-09）

| P4 卡 | 前提状态 | 落点建议 |
|---|---|---|
| #352 取消归档 | ✅ **满足**（unarchive 动词 0.1.6-alpha.1 起 + 官方 Web UI/E2E） | 可解封开工：长按菜单「取消归档」+ RPC + 折叠区刷新（#351 能力位先例）；开工前对现网 0.2.x 做一次 unarchive 往返冒烟 |
| #288 workflow 阶段卡 | ✅ **满足**（journal 4 事件 + session/follow 原始帧透传，≤0.1.2-alpha.2 起） | 可解封，但与 2026-09-01 四面探测定论矛盾——**先对现网 0.2.x 跑一个顶层 workflow 复测 journal**（当时疑 live 版本更旧或探测面漏 journal 原始流通道）；确认后走 run 级聚合器（消费 follow 流 SessionEventEntry，非消息投影） |
| #332 spill 提示行 | ❌ **未满足**（事件枚举/载荷零结构化，上游文档自认文本约定不可认证） | 维持 P4；若将来做文案匹配，参照上游 `hasSpillNotice`/`formatSpillNotice` 固定拼写实现（notice.d.ts/notice.js）比自造正则可靠 |
| #381 248 重配 | ❌ **未满足**（token 仍仅启动 stdout 一条路；oauth/mdns/discovery 全 0 命中） | 维持 P4；边际变化：cookie secret 已持久化（跨重启有效、默认 30 天），官方文档化「token 一次性兑换长期 cookie」——未来可探索兑换复用减小轮换痛点 |
| #146 OpenCode 官方清单 | ①✅已修（V2 core）/ ②③❌官方 NOT_PLANNED / ⑥❌未修（dev HEAD 拆除待重做）/ ④✅仍在 / ⑤◐部分（outputPaths+truncated 进 schema，per-tool 未收口） | ① 需真机确认 V2 服务器版本走 core runner 后可消费 `session.next.compaction.started/delta/ended`；②③ 上游定局不做，客户端自救即终态（可标 wontfix 收口）；⑥ 登记「后台 shell 重建后复查」提醒；⑤ 客户端可开始按 `outputPaths`/`truncated` 做 UI 提示（需判字段缺失兼容旧版） |

## 方法学勘误（重要，影响历史结论）

**2026-09-29 的「0.1.7-rc.2 全仓无 unarchive」复验是误报**——当时只查了顶层 `@deepseek-ai/dsh` 薄包（20 文件 CLI 启动器），真代码在 ~150 个平行发版的 `@deepseek-ai/dsh-*` 子包里。后续 dsh 上游核查一律：顶层 + 关键子包 + GitHub 源码三面。

---

# C 路：DSH 现网取证（2026-10-09，只读）

> 执行：subagent，全程只读（无服务操作、无改状态 RPC）。报告原件 /tmp/dsh-live-forensics-2026-10-09.md，本节为存档摘录。

## 现网拓扑与版本

- DSH 服务器在本机：systemd 用户服务 `~/.config/systemd/user/dsh.service`（enabled/running）`ExecStart=/home/linuxbrew/.linuxbrew/bin/dsh web --no-open`，监听 127.0.0.1:3080。
- bin 符号链接 → `/home/linuxbrew/.linuxbrew/lib/node_modules/@deepseek-ai/dsh/lib/bin.js` = npm 全局 **@deepseek-ai/dsh@0.2.0-rc.2**（npm ls + 根/嵌套子包 package.json 四路一致，无第二安装）。
- 数据目录 `~/.dsh/`（无 DSH_HOME，走默认）。`/api/version` 需鉴权（journalctl token 与 ~/.dsh/token 均 401），版本以离线证据定案。
- 8181 = docker `dsh-pw-demo` 演示容器（另有一个 ssh 进程同时监听 8181，记录未处置）。

## #352 unarchive：现网在

运行安装树四路实证：`dsh-workspace/lib/index.js:551`（实现）、`dsh-api-workspace-controller/lib/index.js:317`（`Remote("unarchiveSession")`）、`dsh-api-remotes/lib/client.js:12447`（客户端）、`.d.ts:224`（类型）；follow 增量 `{type:'archived'}`（controller `index.js:119` + schema `typert.host.js:95`）；`~/.dsh/storages/workspace.json` 的 `archivedSessionIds` 非空 = 归档功能实际在用。

## #288 tool-workflow：代码在、历史零样本

- 149 个本机 journal（138M，7 工作区）+ 演示容器 10 个 journal 全量 zstd 解码扫描：真实 `"type":"tool-workflow/*"` 事件 **0 个**（四种全零）；宽松 grep 5 处命中全为假阳性（DSH 开发会话里 grep/read oc-beacon 自身 DshEventMapper.kt 的工具输出文本）。
- 发射代码在运行安装内齐备：`dsh-tool-workflow/lib/index.js:98/109/117/124`（v0.2.0-rc.2）。
- **定性：现网支持 #288（代码在），缺的是运行样本——顶层 workflow 从未在历史会话中跑过，非版本缺口。** 2026-09-01「四面皆无」与 npm bisect（发射代码 0.1.2-alpha.2=08-30 起）的矛盾由此解释：当时 live 版本更旧。要真实样例需现网实际跑一次 workflow 工具（本次按只读纪律未制造）。
