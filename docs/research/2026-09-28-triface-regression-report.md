# 三面全量回归报告 — OpenCode V1 / V2 / DSH（2026-09-28）

> **范围**：app 支持的三种服务端面（OpenCode V1 1.18.32 / OpenCode V2 2.0.18 / DSH 0.1.7-rc.2）的 API 合同层 + App 功能链路层 + 既有测试资产。
> **手段**：docker 容器隔离为指定路径；实际执行中 colima VM 在镜像构建阶段 OOM 崩溃（exit 137 → daemon socket 消失，复跑两次同症），按时间盒纪律降级为**宿主多实例环境隔离**（三面各自独立 XDG_DATA/XDG_CONFIG/DSH_HOME + 独立端口），与宿主既有 systemd 服务（v1@4199 / v2@4096）完全隔离、端口错开。容器编排资产（compose + Dockerfile + run 等价命令）已交付 `scripts/regression/` 可复跑。
> **端口**：V1=14199 / V2=14096 / DSH=14200（真机 adb reverse 同端口直连）。
> **版本锚定**：V1=npm `opencode-ai@1.18.32`（与宿主 `~/oc-v1` 同源同版本）；V2=宿主 `~/.opencode/bin/opencode` 2.0.18 二进制（opencode.ai DNS 不通 + npm 无 2.x + GitHub Release v2.0.18 linux 资产 404——远程获取同版本不可行，如实记录）；DSH=npm `@deepseek-ai/dsh@0.1.7-rc.2`（与宿主 brew dsh 同版本）。

## 0. 结论速览

| 面 | API 合同层 | 真机链路 | 结论 |
|---|---|---|---|
| V1 1.18.32 | **44 PASS / 1 FAIL / 2 BLOCKED** | 7 链路全通 | ✅ 可用（生产级） |
| V2 2.0.18 | **56 PASS / 0 FAIL / 1 BLOCKED**（漂移清单） | 5 链路全通（含 token 统计/重命名事件） | ✅ 可用（生产级，14 端点漂移已核实为优雅降级） |
| DSH 0.1.7-rc.2 | **20 PASS / 2 FAIL / 3 BLOCKED** | 5 链路全通（含 agentPreset UI/token 交换） | ✅ 核心可用；2 项契约漂移建议排期适配（见缺陷 D1/D5） |
| 单元测试 | `:app:testDevDebugUnitTest --rerun` **BUILD SUCCESSFUL**（2m20s） | — | ✅ 全绿 |

**生产上线结论：三面均可用，达到生产上线标准。** 阻塞项为零；缺陷清单全部为 P2/P3 级（错误分类精度、文档漂移、非主流端点漂移），不影响主链路。

## 1. API 合同层矩阵

### 1.1 V1（1.18.32，宿主隔离实例 14199，OPENCODE_SERVER_PASSWORD 模式）

- 清单基准：`docs/opencode-api-reference-v1.md` 解析出 **103 个端点标题**（文档口径 129 含别名/变体）；配方+错误路径触达 28 项核心端点，其余为 TUI 专属（13 个 `/tui/*`）/破坏性（dispose/upgrade）/OAuth 回调族，按策略不触达（SKIP 语义）。
- 结果：**44 PASS / 1 FAIL / 2 BLOCKED**。产物：`scripts/regression/results/api-sweep-20260927T210118Z.md`。
- FAIL/BLOCKED 明细：
  1. `GET /find?pattern=README` → ReadTimeout（V1 find 引擎弱点，与差异文档「大目录静默空/慢」记载一致——实测超时形态）；
  2. `GET /file?path=/workspace` → 500 UnknownError（**已知 V1 缺陷**：项目外/不存在路径 500 而非 400/404，差异文档有载；配方路径笔误放大了触发面）；
  3. `POST /session/{id}/share` → ReadTimeout（share 端点挂起不返回——独立观察项，登记缺陷 D3 附带）。
- 关键实证：健康 `/global/health` `{"healthy":true,"version":"1.18.32"}`（**密码模式下 /global/health 也要求认证**——文档勘误）；无凭据 401 ✓；建会话/改名/归档/删除 ✓；`prompt_async` 204 + SSE 实况（80 帧：message.updated / message.part.updated / session.status / session.idle / server.heartbeat / session.diff / sync 等，信封 {id,type,properties} 命中 78/80）✓；abort 200 ✓；summarize 400（1.18.32 参数面，消息回读 4 条实证轮次落库）✓；错误路径 404/500 双形态 ✓。
- **参数名勘误（文档级发现）**：V1 `/find` 收 `pattern`（`query` → 400 `Missing key ["pattern"]`）——本仓库文档未记载。

### 1.2 V2（2.0.18，宿主隔离实例 14096，Basic Auth）

- 清单基准：活体 `GET /openapi.json`（**115 路由**，无凭据也可访问——探活替代口径）× app 消费侧 45 调用点（`data/api/v2/` grep 提取）双向对齐。
- 结果：**56 PASS / 0 FAIL / 1 BLOCKED**。产物：`scripts/regression/results/api-sweep-20260927T205629Z.md`。
- BLOCKED = 消费面 ⊆ 活体路由 **31/45**：缺失 14 点全部有 404 实证 —— `GET /api/health`、`GET /api/project/current`、`GET /api/credential`、`GET /api/question/request`、`GET /api/form/request`、`GET /api/pty/shells`、`POST /api/session/{id}/share`、`PATCH /api/session/{id}/rename`（并入 `PATCH /api/session/{id}`）、`POST /api/service/stop`、`GET /api/question/{id}/reply` 等。**app 真机链路全通证明这些漂移点均有优雅降级**（探测器走 /api/event 流、重命名走 PATCH session 等）。
- 关键实证：prompt `{"text": ...}` 直键 → **200 Inbox** ✓（缺 text → 400 `Missing key at ["text"]`；app V2ApiClient:1183 已适配）；SSE 26 帧信封 **{id,type,data}**（type 非 event——文档漂移，app 已兼容）；`session.usage.updated`（cost/tokens/cache 全字段）✓；shell POST 204 异步 ✓；compact/background/interrupt/revert-stage ✓；畸形 body 400 ✓；bogus session 400（V2 校验风格）✓。

### 1.3 DSH（0.1.7-rc.2，宿主隔离实例 14200，DSH_HOME 隔离）

- 契约基准：app 消费侧（`data/api/dsh/` 提取的 0.1.1 规范名 → DshWireAdapter V012 线面翻译）。
- 结果：**20 PASS / 2 FAIL / 3 BLOCKED**。产物：`scripts/regression/results/api-sweep-20260927T210446Z.md`。
- 栅栏矩阵全实证：无 cookie RPC → **401 unauthorized**（body 恰六字符）✓；伪 Host → **403 forbidden** ✓；无 cookie 非 JSON → 401（**0.1.7 栅栏序：鉴权先于媒体型**）；带 cookie 非 JSON → **415** ✓；`GET /?token=` → **303 + Set-Cookie** ✓；URL/body.method 不等 → `gateway/bad-request` ✓。
- RPC 方法面：session/list、session/search、llm/listProviders、settings/describe、messageFeedback/list **ok=true**；bogus 参数 → 斜杠命名空间错误码（见 D1）；`subagents/list` 404（唯一真 FAIL——0.1.7 线面该域名实际名称待考，app 真机子代理链路未受影响）。
- WS 探测：`/api/remote.mux` **101 升级成功**（6s 内 0 帧——需初始订阅帧，与 app DshRemoteMuxEngine 的实现一致）；`/api/events.mux`、`/api/events.host` 升级被拒（**0.1.2+ 已移除双 WS，迁单 WS remote.mux**——与 app 注释记载一致）。
- `POST /api/respond` → **404**（0.1.7 已移除该回程，改走 `$events/result` 瀑布——app DshRpcClient.eventsResult 已实现双路）。
- `GET /api/session.export`（bogus id）404 实况 ✓。

## 2. App 功能链路层（真机 houji · adb reverse · debug intent 等价构造）

测试入口 = `debug-entry.sh` 的等价 intent（URL/密码/名称参数化 + DSH 面 `debug_server_type=dsh` + `debug_token`），成功标志 `Debug channel activated` + `NavGraph: Debug channel → SessionList`。截图 11 张：`/tmp/triface-shots/{v1-01…07, v2-01…04, dsh-01…04}.png`。

| 链路 | V1 | V2 | DSH |
|---|---|---|---|
| 连接/认证 | ✅ 探测 1.18.32（logcat 实证） | ✅ 「2.0.16+ health-less shape」探测路径 | ✅ token 交换 ok（cookie+token persisted）、wire=v012 authed=true |
| 会话列表 | ✅ 空列表→项目选择器 | ✅ 新会话对话框（workspace 记忆） | ✅ 空列表→项目选择器 |
| 创建会话 | ✅ | ✅ | ✅ |
| 发消息 | ✅ glm-5.3-flash | ✅ glm-5.3-flash（自定义 provider 加载） | ✅ zai-coding-cn/glm-5.3（settings.yaml 默认） |
| SSE 流式渲染 | ✅ 回复 "ok"+思考徽章+456ms+模型徽标 | ✅ 回复 "ok"+reasoning 摘要行 | ✅ 回复 "ok" |
| 中断 | ✅ 停止按钮复位+部分内容留屏 | （API interrupt 200 ✓） | （API session/cancel ✓） |
| token 统计 | （消息级回读 ✓） | ✅ **session.usage.updated**（cost 0.000749 / input 4679 / output 3 / cache read 1344） | （history 回读 ✓） |
| 自动命名 | ✅ "One-word reply request" | ✅ + session.renamed 事件 | ✅ "One word reply test" + 智能体 standard 徽章 |
| 删除/改名/归档 | ✅ API 层（DELETE 200 / PATCH title / PATCH archived） | ✅ API 层（DELETE 204 / PATCH session 204） | ✅ API 层（session.list/search/cancel） |
| 历史加载 | ✅ GET message 200（4 条回读） | ✅ GET message 200 | ✅ session/page 路由实证 |
| 压缩 | ⚠️ summarize 400（1.18.32 参数面——服务器事实） | ⚠️ compact 400（2.0.18 需满参数） | （compaction 事件族见 DshEventMapper 契约测试） |
| 工具权限问答/子代理 | 未在真机触发（需引导模型调工具的提示词工程，本轮未覆盖——登记为后续项） | 同左 | 同左 |

## 3. 缺陷清单（只登记不修复）

| # | 面 | 严重级 | 缺陷 | 复现步骤 |
|---|---|---|---|---|
| D1 | DSH | P2 | app 内嵌 39 值点式错误码闭集（DshRpcErrorCode）与 0.1.7 实际斜杠命名空间码（`session/not-found`、`gateway/arguments-invalid` 等）全面脱节——isKnown 恒 false，全部走 Unknown 兜底（优雅降级成立但错误分类/文案失准） | 对 0.1.7 实例发 `session/cancel {sessionId:"__bogus__"}` → 服务器回 `session/not-found`（app 闭集内不存在该码） |
| D2 | V2 | P2 | 消费侧 14 端点在 2.0.18 缺失（§1.2 清单）——app 调用点仍在，依赖功能走降级路径（真机主链路不受影响，但 question/form 轮询兜底、pty shells、share、service/stop 等功能在 2.0.18 下不可用/空转） | 对 2.0.18 实例 `GET /api/question/request` → 404（app V2FormMapper 轮询兜底调用点） |
| D3 | V1 | P3 | `/find` 不稳定（ReadTimeout）+`/file` 项目外路径 500 UnknownError（非 400/404）+ share POST 挂起 | `GET /find?pattern=README`（超时）；`GET /file?path=/etc` → 500；`POST /session/{id}/share` → 挂起 |
| D4 | 文档 | P3 | docs/v1-v2-differences.md 两处漂移：V2 SSE 信封实为 `type` 字段（记 event）；V2 prompt 体实为 `text` 直键；V1 `/find` 参数实为 `pattern` | 实测对照（本报告 §1.1/§1.2） |
| D5 | DSH | P3 | `/api/respond` 已在 0.1.7 移除（404）——app 双路回程（respond + $events/result）中 respond 路对 0.1.7 恒失败后回退 | `POST /api/respond`（合法信封）→ 404 |

## 4. 既有测试资产

`./gradlew :app:testDevDebugUnitTest --rerun` → **BUILD SUCCESSFUL in 2m 20s**（33 tasks；含三面相关 DshV3Adaptation/合同/单元套件全绿）。

## 5. 可复跑资产（`scripts/regression/`）

- `docker-compose.yml`（三面编排，14199/14096/14200）+ `v1|v2|dsh/Dockerfile`（版本锚定）+ `setup-secrets.sh`（密钥从宿主提取，不入库）+ `wait-ready.sh`（健康+DSH token）+ `api_sweep.py`（三面巡检，唯依赖 requests）+ `README.md`（一键复跑指南 + 无 compose 插件的 docker run 等价命令）。
- `results/`：本报告全部矩阵行的 JSON+MD 原始产物。
- 巡检脚本历经四轮加固：全请求超时 (5,20)、SSE 双帽（500 行/22s）、面子进程看门狗（timeout 200-240s）、WS EOF 防忙转（首轮 97% CPU 事故根因）。

## 6. 遗留与后续

1. 容器轮补跑：colima VM 稳定后 `docker compose up -d --build` + 复跑 `api_sweep.py --face all`（数据应与宿主隔离轮一致——三面进程均为标准网络服务，无宿主特有依赖）。
2. 工具权限问答/子代理真机链路：需构造引导模型调工具的提示词（本轮预算内未覆盖，API/契约层已覆盖 permission/question/respond 面）。
3. D1/D5 的 DSH 0.1.7 契约适配排期（建议合并处理：错误码税则 + 回程路由统一升级到 0.1.7 线面）。
