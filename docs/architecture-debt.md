# Architecture Debt Register

Generated: 2026-07-13
Updated: 2026-09-30（#42x-#47x 滚动/渲染引擎战役后全量复测——god files 表刷新+引擎域入册+测试缺口并入；#481）

## 1. 依赖方向违规（已修复 vs 剩余）

### ✅ 已修复（2026-08-05）

| 违规 | 修复方式 |
|------|---------|
| `ServerTerminalRegistry`（data）→ `ServerTerminalWorkspace`（ui） | `ServerTerminalWorkspace.kt` + `TerminalTabState.kt` 迁移到 `data/terminal/` |
| `ChatViewModel` 死注入 `SseClient` | 删除（零调用点） |
| `ChatViewModel`/`SessionListViewModel`/delegates 直接注入 `SessionStateService` | 新建 `domain/repository/SessionStateRepository` 接口，`SessionStateService` implements 之；UI 路径全部面向接口 |
| `ServerSettingsViewModel` 绕过 domain（`ProviderApi`/`SystemApi` 直接注入） | 扩展 `ProviderRepository`（+8 方法）、新建 domain 模型（GlobalConfig 等），ViewModel 只依赖 domain 接口；`SettingsDataStore` → `SettingsRepository` |

### 🔴 剩余（可接受债务）

| 文件 | 违规 | 说明 |
|------|------|------|
| `ChatViewModel` | 注入 `data/repository/ServerTerminalRegistry`（终端工厂） | 终端体系与 connectbot `TerminalEmulator`/`PtySocket` 深度耦合，是 Android 平台细节。抽 domain 接口收益低、风险高，标注为可接受 |
| `ui/screens/chat/terminal/*` | UI 依赖 `data/terminal/ServerTerminalWorkspace`（含 `TerminalEmulator` 类型暴露） | 同上，终端仿真器是平台细节，维持现状 |
| `data/repository/EventDispatcher`、`handler/*`、`SseConnectionManager` | data/service 内部互用具体类 | 正常（data 层内部），非违规 |

> ✅ **2026-08-07 已修复**：`SessionListViewModel`/`DirectoryManager` 的 `FileApi`/`SessionApi`/`SystemApi`/`TerminalApi` 直调已全部下沉为 UseCase/Repository（backlog #17）——4 个 Api 注入移除，扩展文件搬回主类，internal 全转 private。

> 完整修复终端体系需要将 `ServerTerminalWorkspace` 的 tab 管理/重连/buffer 抽象为 domain 接口——预计 20+ 文件改动、测试重写，收益低于成本。**若未来做，先抽 `ServerTerminalRegistry.workspaceFor` 的薄接口**。

## 2. Thin UseCase Layer（25 个，绝大多数纯委托）

25 个 UseCase 中绝大多数是纯委托（`suspend fun invoke(...) = repo.method(...)`），仅 `SubmitAnnotationsUseCase` 含业务逻辑。2026-08-07 新增 6 个（ListSessions/ListProjects/GetServerPaths/ProbeDirectory/SearchDirectories/CreateDirectory，backlog #17）。

**2026-08-05 已删 9 个死 UseCase**（main 代码零引用，仅有测试）：ConnectServerUseCase、CreateSessionUseCase、DisconnectServerUseCase、GetMessagesUseCase、GetServerListUseCase、GetSessionListUseCase、ManageQuestionUseCase、PermissionHandlerUseCase、QuestionHandlerUseCase。

**剩余选项**：
- A) 删除剩余纯委托 UseCase，ViewModel 直接调 Repository —— 破坏 AGENTS.md 声明的架构规范，需改 20+ 测试
- B) 保持现状 —— UseCase 作为未来业务逻辑的 seam，样板成本低（9-43 行/个）✅ 当前选择
- C) KSP 代码生成

**推荐**：Option B（AGENTS.md 已声明 "ViewModel 委托给 UseCase" 是项目规范，删除会破坏架构一致性）。

## 3. God Files（>500 行，2026-09-30 实测）

| File | 2026-08-07 | 2026-09-30 | 状态 |
|------|------|------|------|
| ChatMessageList.kt | ~674 | **2756** | #42x-#47x 引擎战役主战场（entries 装配/流式配对/分片消费）；拆分需以引擎域为单元整体外移，散抽会破坏铁律链 |
| DshApiClient.kt | — | **2550** | DSH 协议栈（0.1.x 演进期），按域分文件候选 |
| DshEventMapper.kt | — | **2150** | 同上（事件映射面随 SSE 事件集扩张） |
| V2ApiClient.kt | — | **1874** | V2 端点族（#459 漂移清单对应面） |
| CardExpandReveal.kt | — | **1861** | #420-427/#466 卡片展开战役产物；含 DEBUG 打点（#473 清扫域） |
| ChatViewModel.kt | ~493（瘦身后） | **1574** | 回涨（会话动作/数据委托并入后）；SessionActionsDelegate 880 + MessageDataDelegate 831 已是外移产物 |
| SessionListViewModel.kt | ~522 | **1418** | 持续回涨 |
| MessageCardAssistant.kt | — | **1387** | 分段/分片/StepGroup 渲染树（#422/#258） |
| ChatScreen.kt | ~770 | **1360** | 屏幕 shell；BottomBar 808 已外移 |
| MessageEventHandler.kt | ~857（#234 后） | **1289** | SSE 事件装配 |

> 700-984 行段（未列全）：V1ApiClient 984 · MarkdownContent 961 · MarkdownTable 914 · QuestionPartContent 885 · SessionActionsDelegate 880 · SseConnectionManager 866 · ChatRepositoryImpl 865 · DshRemoteMuxEngine 843 · MessageDataDelegate 831 · SessionListScreen 818 · ChatScreenBottomBar 808 · SessionStateService 792 · ChatFabMenu 775 · AppNotificationManager 756 · MessageStore 744。

### 3.1 滚动/渲染引擎域（2026-09-30 入册，#42x-#47x 战役产物）

高度-视口配对与流式稳定是铁律链承重区（见 AGENTS.md「SSE 滚动稳定性」+ docs/research/sse-scroll-stability-iron-laws.md），**改动前必读铁律**：

| 域 | 文件 | 行数 | 职责 |
|------|------|------|------|
| scroll/ | ChatScrollController.kt | 488 | 视口意图/贴底判定（snapshotFlow 双值键） |
| scroll/ | ScrollCompensation.kt | 708 | #435 高度配对/帽轨（#470 回缩缺口在册） |
| scroll/ | PreRenderCoordinator.kt | 198 | pre-draw flush 单点 |
| util/ | SafeFlingBehavior.kt | 132 | fling 行为 |
| components/ | CardExpandReveal.kt | 1861 | 卡片展开（#420-427/#466） |
| components/ | RenderSupplyCoordinator.kt | 602 | 预解析/chunk/segment 计划供给窗 |
| components/ | TurnSegmenting.kt | 208 | Stage B turn 分段骨架 |
| components/ | MarkdownChunking.kt | 413 | entries 装配/chunk 消费 |
| markdown/ | SafePrefixGate.kt | 516 | #437 稳定前缀两级放行 |
| markdown/ | StreamingMarkdownPilot.kt | 318 | #265 前缀差分试点 |
| markdown/ | HeldTailReveal.kt / HeldTailAging.kt | 188/96 | #437 降亮区 |

> 旧表勘误：原 ChatScreen ~770 / ChatMessageList ~674 系 2026-08-07 快照，两个月引擎战役使其失真 ~4 倍——本节为 2026-09-30 wc -l 全量复测。

> 2026-08-07 已瘦身出表：ChatViewModel（~1100→493，#8/#15 拆分）、SessionListScreen（~700→367）、SettingsDataStore（~690→223）、NavGraph（~570→420）、MessageDataDelegate（#15 拆为 PaginationDelegate + OptimisticMessageStore）。

> **2026-08-26 #234 战役**：MessageEventHandler 1329→857 行——~450 行合并代数迁 `data/mapper/MessageMergeEngine.kt`（纯函数直测），注册决策树收编为 `resolvePartRegistration`（sealed 决策），派生 id 契约单一权威 `domain/model/PartIdContract.kt`（V2SseMapper 生产侧/引擎消费侧双侧委托）。**空 part 防御分布更新**：不变量权威 = 引擎（mergePartsList 双侧滤空 + 注册决策 + sanitized appendOnly 封洞，含直测）；REST 侧过滤（V1ApiClient/V2Mappers）降级为纵深防御保留；MessageDao SQL LIKE 清扫保留为一次性历史迁移。

## 4. 测试缺口（高优先）

| 模块 | 风险 |
|------|------|
| 分页 androidTest @Ignore（ChatInteractionTest pagination_triggersOnScrollUp） | sessionId 空壳致 init 跳过 loadMessagesForSession → hasOlderMessages 恒 false；解法三选一（SavedStateHandle 注入非空 sessionId / 测试可见强制钩 / Mock SessionLifecycleDelegate），方案注释在测试现场（2026-09-30 并入自 #481） |
| 终端 tab 管理（ServerTerminalWorkspace） | 重连/多 tab 逻辑无单测（2026-09-30 复核仍缺） |
| ServerSettingsViewModel 新方法 | ProviderRepository 新 8 方法仅有 mapper 测试，ViewModel 层无覆盖 |
| SessionStateRepository 接口 | 已由 SessionStateServiceTest 覆盖（经具体类） |

## 5. 维护指引

- 新代码禁止 data → ui import；`grep -rln "import dev.leonardo.ocbeacon.ui" app/src/main/kotlin/dev/leonardo/ocbeacon/data/` 应为空
- 新代码禁止 UI 直接注入 `data.api.*`；必须经 domain repository
- 例外：终端体系（见 §1 剩余项）——改动前先讨论

## 6. 密码导航重构遗留（2026-08-07）

| 位置 | 债务 | 说明 |
|------|------|------|
| `ChatViewModel.kt` / `SessionListViewModel.kt` | `runBlocking(Dispatchers.IO)` 在 ViewModel 属性初始化中同步读 `getServer(serverId)` | 反模式（阻塞 VM 构造线程）。实际影响小（Home 页已 warm DataStore，读为内存级）；进程重建直入 Chat 页时冷读 20-80ms 理论可感知。正确形态：`StateFlow<ServerConfig?>` + 异步加载 + 下游组件（TerminalDelegate/workspaceFor 首次 conn）支持延迟绑定。**若未来改：先改 `ServerTerminalWorkspace.conn` 为 `@Volatile var` + `updateConnection()`，再异步化 VM** |

## 7. 未读红点体系遗留（2026-08-07，#25 落地后）

| 位置 | 债务 | 说明 |
|------|------|------|
| `EventDispatcher.persistLastCompletedReplyTime` | maxCompleted 每次变化全量 JSON 写 DataStore | 会话规模大+消息完成高频时写放大；当前量级无感（<100 会话，每条完成一次 ms 级）。优化方向：增量 key 或批量节流 |
| 断线期新回复缺失 | 重启/断连期间服务器完成的新回复无法得知（seed 过时） | 与旧 lastReplyTime 机制相同；SSE 重连后增量补全，进会话后 recompute 恢复。未来方向：重连时对活跃会话主动 listMessages 拉取 |
| `EventDispatcherUnreadTest` 迁移测试 | `coVerify` 无 `exactly = 1` | 字面名"once"与断言粒度不符；当前 drop(1)/同步落盘下 updateData 仅迁移触发，实际有效 |
| FOLDER 视图 status 门控 | `buildTreeNodes` 的 status 传递无行为测试（TreeNodeTest 只用前 3 参） | 代码审查确认正确；isUnread 纯函数门控已有单测。补集成测试为可选 |
| 预存在死 import | `SessionListViewModel` 的 `SharingStarted`、`EventDispatcher` 的 `flow.map` | 非 #25 引入，AGENTS.md 精准修改原则下未清理；后续顺手处理 |
