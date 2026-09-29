# OC Beacon — 需求与问题总览

本文档是唯一的**未决工作项清单**：只保留尚未完结的需求与问题卡片。条目完结（用户验收 `[x]`）后**当场迁出**——记录连同证据移入 `docs/journal/` 对应批次文件，本文件不保留完结记录；历史查询走 journal 与 git。

**卡片格式**：标题（含全局编号）+ Tag + 状态 checkbox + **≤3 行**摘要 + 链接。需求全文、实现要点、验证证据一律写在链接目标（spec / journal）中，不内联。登记新批次用 `./scripts/backlog-new-batch.sh "<批次名>"`（自动建 journal 文件）；改动后跑 `./scripts/backlog-check.sh` 校验机械不变量。**放置规则（check 脚本强制）**：卡片一律写在下方对应 **Pn 节内**（按优先级定义归位；一节内新卡置顶）；头部编号行与优先级定义表之间**不放任何卡片**（仅允许编号勘误等注释）。**P4 格式增补**：P4 卡必含「**前提**：…」行——说清实现前提是什么、当前为何不可实现（外部硬阻碍所在）。**术语句**：卡片标题与摘要用词遵循 [CONTEXT.md](CONTEXT.md) 术语表（堆积消息/子智能体/轮次/撤销/中断…）；「待处理」保留给权限/问题（状态词待验证/待办/待裁决不受影响）；Tag 英文与 #N 编号不受中文术语约束；API 英文原词（cursor/fork）合法，_Avoid_ 仅限中文对应词。

**编号**：全局递增，不回收。下一编号：**#484**（2026-09-30 #483 流式逐字揭示动效：char reveal + 尾）。

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

- [ ] **#470 流式高度配对收缩缺口:帽不回改空白残留+ledger收缩不配对视口落** `scroll,chat`
  - 2026-09-30 调研 P3 定罪:①帽轨 reserveReleasePlan 对 trueHeight<=reserved 恒 null(帽单调只增,ScrollCompensation.kt:338)——流式内容回缩(表格列放宽/setext 前重排)时 item 保持旧高=空白残留,直到换流式项 reset;②ledger 轨 note 对 d<0 只 rebase 不配对(:157)——压缩卡/工具横幅回缩时上方内容下坠无补偿。修复需高度引擎域专项设计(帽回改与『已上屏永不回改』既有裁决冲突,需用户裁断语义:回缩时同步缩帽+视口跟随 vs 维持空白)。


- [~] **#441 app SSE 长连接随机断连：输出期间渲染静默（服务端正常）** `bug` `dsh`
  - 二十四/二十五世轮实证：DSH 服务正常（RPC accepted、服务端 turn 照常完成并生成标题），app 侧 MDPilot/渲染全静默；重启 app 即恢复。疑电池优化杀后台 socket（横幅曾警告）或 SSE 重连缺失。
  - 毒害流式测试通道（多轮'队列 stall'误诊实为此）；也是生产可用性缺陷。
  - 2026-09-28 方案A 子项一(静默哨兵)交付:DshSilenceWatchdog(纯逻辑,虚拟时钟,7例TDD)+引擎接线(帧喂食/监控协程15s/判死→close走既有退避重连)。两轮真机实证收敛出关键结论:期望源在引擎层不可用——常开=空闲110s周期重连循环(follow后无帧);挂follow open=fire-and-forget无回执必判死;respond走HTTP独立通道与WS帧流无关。正确源=ChatUiState streaming(SseConnectionManager层onStreamingChanged接线),与follow End自愈状态机同批(A2)。当前态:哨兵待命(帧喂食在,判死门常关=零误杀,210s+空闲观察0判死,连接Online authed)——A2接线即激活
  - 2026-09-28 方案A 子项二(A2)交付:①streaming 期望三跳接线——SessionStateService.activityFlow(任一会话 activity 非空)→SseConnectionManager collect(distinctUntilChanged)→dshFrameSources 转发(DshFrameSource 接口新增 default 钩子,协议路由源覆写→mux 哨兵)——A1 待命态判死门通电;②follow End/StreamError 自愈——原 End 分支 Unit 无动作(定罪点)改为清 followed 幂等集(followSessionIdOf 纯函数反解,3例TDD),事件驱动补开(#319)/聚焦请求(#333)不再被去重拦截;不自动立即重开(End=正常消亡,防风暴)。验证:10例测试绿+全量单测绿+真机空闲0判死+等待期FSM未Busy时门关自洽(链路行为分析闭环)。trip 正例留待 #441 真实场景(不可按需构造)
  - 2026-09-28 方案A 子项三(A3 网络切换 kick)交付:①NetworkMonitor 增 NetworkIdentity(handle=Network.getNetworkHandle 稳定句柄+主传输)与 networkIdentity StateFlow——onCapabilitiesChanged(validated)更新身份,onLost 仅当前身份网络清空;回调工厂提取 createCallback() internal 可测缝(纯提取重构生产行为零变化,绕开单测 android stub:NetworkRequest.Builder.addCapability 返回null)。②OpenCodeConnectionService 增 networkSwitchKickJob:identity 流 drop(1)+debounce 2s(切换竞速防抖)+distinctUntilChanged→reconnectAll;onDestroy 与 recoveryJob 同步 cancel。③SseConnectionManager 死注入清理:networkMonitor 构造参数删除(kick 统一 Service 层;调研§5.3'接线或删除'取删除)。TDD:NetworkMonitorIdentityTest 4例(validated追踪/同态切换identity变化且NetworkState恒Available盲区前提自证/仅当前网络lost才清/unvalidated不成为身份)全绿+全量单测绿。真机自动验证不可行:WiFi切换/飞行模式都断无线adb(serial即WiFi adb),同A1哨兵trip正例先例留真实场景——用户日常网络切换后 logcat 搜 'Network identity switched' 应见 kick。
  - 2026-09-29 用户裁决:A3 网络切换 kick 真机暂不可测(会断无线adb)→待观察:或由 agent 侧模拟测(ConnectivityManager 双网络回调仿真不可行,系统层注入无门;可做=USB adb 有线连接下 svc wifi 切换——serial 走有线时不受影响,待执行)。3 周观察窗(至 2026-10-19):无反馈即关闭待观察标记。
  - 2026-09-29 用户质疑「不是根因修复」分析与回应:三子项分界诚实裁定——A2(follow End 清幂等集)=根修(修「订阅状态不跟随终结事件」的状态机缺陷);A3(网络 identity 流)=根修(修「同态切换零信号」的感知盲区;半开本身是传输层固有特性,应用层只能检测+重连);A1(静默哨兵)=纯自愈,未修「为什么会静默」——其上游根因(电池优化杀 socket/传输半开/服务端行为)未定罪,原调研「先 B 探针后 A 自愈」的顺序被跳过直接做了 A。风险披露:A1+A2 组合存在未验证的误杀通道——streaming 期(门开)若服务端正常长思考>110s 无帧会 trip 误杀(此前真机实证仅覆盖空闲态 0 误杀,长静默流式场景未验)。提案(待用户裁决):B 定罪探针批次——连接死亡现场快照日志(异常时刻:存活时长/最后帧距今/退避计数/电池优化状态/网络 identity 对齐),下次真实断连自动落袋证据→定罪上游→根修;同时标定长思考流式的真实无帧间隔分布,校准 110s 阈值或加 streaming 心跳请求。
  - 2026-09-29 A3 实测尝试结论:真机双 adb 通道均无线(WiFi 直连+mDNS 无线调试),无 USB 有线 serial——切换 WiFi 必断 adb,agent 侧物理不可达(需用户插 USB 线或提供第二 AP 凭据后 agent 可代测)。按观察窗处理(至 2026-10-19 无反馈关闭)。
  - 2026-09-29 B 定罪探针一期交付(用户裁决'相当于探针埋点吗?可以做'):lastEventAtMs 每服务器最后事件时间戳表(SSE 事件入口打点)+backoffWithSchedule 快照日志(death-snapshot server/attempt/lastEventAgoMs——退避重连必经点)。判读法:巨大 lastEventAgoMs=连接活着但长期无事件=静默型死亡(哨兵域,上游为服务端/半开);小值=事件流活跃中断=传输断(上游为网络/系统杀)。完整版二期(电池优化状态/网络 identity/断连异常栈)待一期数据回流后按需接入。同批:#463 V1 兼容调研结论——DB 实证三服务器均'每 step 一条消息'(assistantMsgs≈reasoningParts),现有消息边界装配已全兼容,无需改动;用户看不到分割线=完结 turn 折叠为计数行(#422 语义),展开后可见。
  - 2026-09-30 #467 定罪副产物=本卡根因闭环:死亡现场 death-snapshot lastEventAgoMs=240737(4min04s 零字节静默,无超时无梯子零行为);SSE read timed out 三日志 11 连接周期 0 触发——#108 withTimeoutOrNull(readByte) 挂起不可取消,Ktor socketTimeout 按设计只管响应头,V1/V2 双裸奔;野外恢复全靠网络回调 churn(r2/r3 各 213/235 次)碰运气。修法:读循环竞争+超时分支 response.cancel() 强杀底层 call(心跳契约服务端已在 ~6s/次);详 journal 2026-09-30-467 §2
  - 2026-09-30 修复批次落地(加固定位,commit 见 journal §4):StreamStallWatchdog 字节级 45s 执法+双保险强杀 V1/V2 接线,单测 4/4+全量 3698 绿,E2E 五场景零回归零误报恢复三验;重要反转:SIGSTOP 实证 #108 对纯零字节停顿有效,「读不可取消」论废,4min04s 挂死=adbd 僵尸拆链稀有竞态(4 次复现失败)机制未定;残余:复发抓包级证据+事件级哨兵 v1 legacy 空缺(A2 DSH-only)
  - 2026-09-30 深究批次(journal §5):dsh 链路审计定罪——A1+A2 哨兵(110s 期望门)覆盖主场景,但「空闲期WS假活→用户发言」变体三层不通电(期望无源/admission 不置忙/FSM TextStarted 从 Idle 不升级)=#467 同构洞;修法方向已备忘(prompt admission HTTP 回执→onRequestSent 播种期望,防风暴语义自洽),待真 dsh 环境验证后实施;僵尸模式裁定=adb 测试专属竞态不再追,SIGSTOP 配方与复发判据已固化
  - 2026-09-30 期望播种修复落地(journal §6):prompt HTTP 受理回执→onRequestSent 播种(7 处接线),哨兵 9/9+全量 3700 绿;docker 隔离 dsh(dsh-e2e 容器,保留)真机 E2E:轮次全渲染+92s 冻结自动恢复+零误杀;诚实边界:帧死WS活形态无法外部伪造,击杀路径单测级证明待野生复发背书

- [ ] **#437 流式Markdown稳定揭示渲染——安全前缀两级放行(稳定块+纯段安全后缀),消灭不稳定尾回溯跳变** `sse,render,perf`
  - 根因:不稳定尾先字面排版后回溯重释义=已显示内容高度回溯(真机录屏A-B翻转帧定罪);#435引擎只能配对单调增长。方案:pilot差分与append之间加SafePrefixGate(库与渲染零改动):稳定块+开放段纯文字安全后缀两级放行,尾部扣留超龄进锁高降亮区,完结EOF全量flush。spec:docs/specs/2026-09-25-437-streaming-md-stable-reveal-design.md(阶段A-D+验收矩阵)
  - 2026-09-28 深度调研(issue437-research.md,204行):核心根因已由 SafePrefixGate 消灭有二十余轮定量证据;剩余=验收收口+关联残差分卡。R-7 重要发现:beta/stable 双关闭,修复仅 dev 生效(build.gradle.kts:108-118);铁律文档 sse-scroll-stability-iron-laws.md 未收编#437 内容(文档同步缺口);#450 是本卡判据放宽的直接次生回归(教训建议进铁律)
  - 2026-09-29 发掘审计:铁律收编缺口比 R-7 记载更大——sse-scroll-stability-iron-laws.md 止于 2026-09-25 #435,#437 稳定揭示/#438 限速与保 key/#472 行内放行/#474 守卫分通道/#476 GUARD 死区五域铁律全未收编;R-7 收口时应一并补

## P2 — 优化与锦上添花

- [ ] **#478 Room 库 837MB 无界增长源待定位——热表修剪+归档在,库文件仍巨** `data` `perf`
  - 2026-09-29 发掘审计真机实测:dev 包 ocbeacon.db=837MB(800M databases,files/shared_prefs/cache 全<1MB)——增长全在库文件
  - 修剪机制其实存在(MessageStore SESSION_MESSAGE_LIMIT+溢出 zstd 归档后 prune)——嫌疑收窄:FTS 索引行(独立于分层,删会话才清)/归档桶常驻库内/SQLite 自由页无 VACUUM 回收/工具输出 provider 缓存
  - 影响面=存储占用与冷启开销;定罪路径:库表体积普查(sqlite dbstat/各表 COUNT+长度和)→对位修复(FTS 随归档清/周期 VACUUM/归档外移文件系统)
  - 2026-09-29 T-B 普查定罪(844MB 库 dbstat):FTS5 占 778MB/92%——message_fts_content 613MB(181,682 行全文镜像,未用 external-content 表配置)+message_fts_data 162MB(倒排);真实数据仅 ~58MB(cached_parts 22.3+archive_buckets 25.9+cached_messages 3.4+logs 5.9);freelist=0
  - 复合根因:①FTS5 建表未用 content=外部内容表→全文在库内双份;②FTS 行不随热表修剪(181k 行 vs cached_parts 6.6k 行,冷数据未压缩文本永驻——ContentSearch.kt:70 'prune 不删 FTS 行'设计);③page_size=1024 小页放大 btree/溢出链开销;修向=external-content 重建 FTS+迁移回填(+可选 page_size 4096 需 VACUUM 备份路径)——预计回收 ~613MB,稳态 ~230MB

- [ ] **#471 完结瞬间高度跳变族:StepGroup整树互换+>2048字符Loading塌缩+归一化重排** `chat,markdown`
  - 2026-09-30 调研 P2 定罪:①多消息 turn 完结时 StepGroup 流式平铺↔折叠组整树互换(探针注释自认结构性高度跳变源,数千 px 级,冷账本 24dp 桩帧,MessageCardAssistant.kt:428-479);②>2048 字符正文完结切 pilot→async 首帧 Loading≈0 高再 Success 全高(#428 同族 +268px,MarkdownContent.kt:603-715);③完结归一化变换(数学围栏/任务列表/长段空行化)只发生在完结=一次性重排。方向:②pilot 终帧同步换入/缓存预热收益最明确;①依赖 L3 AST 切片既有计划。另:setext 升格(SafePrefixGate 自认缺口)+tight→loose 列表+CRLF 表格三小项随 markdown 批次顺带。
  - 2026-09-30 #472 修(②完结 Loading 塌缩):真机定罪在手——断连重连恢复换装时 RESIZE t_msg_0e641a99a001 h 1105→865(d=-240)→1580(d=+715) 41ms 两连跳(=Loading≈0 高帧→Success 弹回,与正常完结换装同构);另 RESERVE align-flip overflow=-62 佐证帽负溢出。根修:pilotTerminalHold 纯函数(pilot 曾渲染∧async 未就绪→完结帧保持 pilot 终帧)+asyncTerminal 提升固定组合位(条件创建,hold 期与切换后同实例零重解析,collectAsState 响应式解除);残余归一化差由帽配对吸收。TDD 3 例红转绿+全量单测绿;①StepGroup 互换与③归一化重排为残余(量级小于已修,另批)。
  - 2026-09-30 修复交付(②完结 Loading 塌缩):真机定罪——重连恢复换装时 RESIZE t_msg_0e641a99a001 h 1105→865(d=-240)→1580(d=+715) 41ms 两连跳(=Loading≈0 高帧→Success 弹回,与正常完结换装同构);另 RESERVE align-flip overflow=-62 佐证帽负溢出。根修(12f8214b):pilotTerminalHold 纯函数(pilot 曾渲染∧async 未就绪→完结帧保持 pilot 终帧)+asyncTerminal 提升固定组合位(条件创建,hold 期与切换后同实例零重解析,collectAsState 响应式解除);残余归一化差由帽配对吸收。TDD 3 例红转绿+全量单测绿+装包。①StepGroup 互换与③归一化重排为残余(量级小于已修,另批)。待用户复验:完结一瞬无塌缩弹开。
  - 2026-09-30 用户验收通过(②完结 Loading 塌缩):茶文化轮判定链+高度序列双证据——完结帧 hold=true 拦截 Loading(19:00:10.772),144ms 后 async 就绪无缝切换,全程高度差仅 ±24px(归一化微差,一行文字高);对比修复前同场景 -13171/+12824 两连跳。残余:①StepGroup 边界换装(轮次开始/结束各一次 845→1492)与③归一化重排量级小待后续;新发现④SSE retry 恢复场景:重连后内容跳变重组期 pilotEverRendered 丢失(ever true→false 实证)→塌缩 -8575px 仍现,仅断连续传时发生,正常轮次不受影响——随 retry 路径稳定性专项处理。
  - 2026-09-28 ①StepGroup 互换已根灭(#422 清理批次,用户裁决彻底清理):统一渲染树后流式/完结同构,互换不存在——10轮多step POST完结塌缩 10/10→0/10。③归一化重排与④SSE retry pilotEverRendered 丢失仍待处理(与本卡完结族余项同批)
  - 2026-09-29 E2E 活证:表格轮完结/换装震荡 -8544/+7236(t_msg 项 14k px)——完结族在真实表格轮的量级实证(此前 #472 验收为纯文本轮 ±24px);表格轮完结路径(归一化+staged 重建+换装叠加)待专项取证
  - 2026-09-30 ④复现确认调研(用户指令:修复前先确认仍存在):静态链仍可达(ever=false 重建帧无防御——pilotTerminalHold 只拦 ever=true);真机 v2 双场景未复现 ever 丢失(实验A服务端重启 reconcile:hold 拦截✓残余-1330归一化差③族/实验B链路断+续生成跳变2523→5144:hold 拦截✓零塌缩+5969正向追平)——v2 backfill partId 稳定组合位不销毁。未验证:v1 message.updated 全量重发路径(bigmodel key 401 失效,16:23 前同 key 尚成功)+弃树重建三联(longcat 免费档限流挂死)。裁决请求 a防御性根修(msgId 级 ever 账本)/b恢复 v1 后补验/c降级 watch——用户已选先走 b:查官方文档重新部署 v1 再测。详见 docs/journal/2026-09-30-471-4-sse-retry-pilot-ever-probe.md
  - v1 验证完成(裁决数据齐备):bigmodel 双key全灭→免费档 nemotron 重部署;wire 级证伪 full-parts resend(message.updated 恒无 parts,partId 全程稳定,mergeAssistantMeta 只并元数据);真机三轮断连实验 331k 行取证——asyncTerminal Loading 帧=0、hold=true 拦截正例、preParsed 分块复入 Success、底部屏幕实证完整渲染;④ v1+v2 双协议不可复现(#472 后防御栈有效)。裁决请求 c(降级观察,推荐)/a(防御根修 ~30 行)。详 journal 2026-09-30-471-4-v1-verification.md
  - 2026-09-30 ④用户裁决 c(降级观察):v1+v2 四场景实测不可复现(331k 行 Loading=0,#472 hold/registry/preParsed 防御栈有效)→④转 watch 复发再战(理论盲区仍在:断连续传+滚出视口弃树+ever=false 重建帧,0 触发);残余③归一化重排(含 09-29 表格轮 -8544/+7236 活证)继续挂本卡待批

## P3 — 观察与低价值改进

- [ ] **#483 流式逐字揭示动效：char reveal + 尾部渐变 + 终端光标（设计定稿）** `streaming` `ui`
  - 逐字（字形簇）揭示 + 尾部 4 字不透明度阶梯（双驱动 + 0.1s 平滑插值）+ 终端下划线光标（忙常亮/停顿 500/500 硬闪；行尾独行换行、预留整行槽位零跳动）；完结快速全亮 + 光标即移（业界零例外）。
  - 架构红线：SafePrefixGate→揭示层→StreamingMarkdownState 单动画驱动；光标 overlay 禁 inline content（13ms/frame 血案先例）；自适应速率 max(40字/s, 到达速率) 限简单算术；退后台/中断/停止立即揭示全部。
  - 范围 Part.Text（排除表格/围栏码块/thinking）；BuildConfig 开关 dev 先行 + debug 属性调参；尾部阶梯业界无先例、可读性真机自证。调研 docs/research/2026-09-30-streaming-reveal-animation-patterns.md
  - → docs/specs/2026-09-30-streaming-char-reveal-design.md
  - 影响面评估（2026-09-30）：主代码 7 个=6 改(StreamingMarkdownPilot/HeldTailReveal 中改、MarkdownContent/ClickableMarkdown 小改、Motion/build.gradle.kts 微改)+1 新(揭示层)；测试 2 新+2-3 扩；ChatScreen/批处理/高度引擎/i18n 零触碰；全程限 dev pilot 路径。
  - 深挖定案（2026-09-30，一次到位确认）：①渐变弃 AnnotatedString span 改绘制层叠加（TextLayoutResult.getBoundingBox+drawBehind 读动画值零重排——span 路线卡壳推档期每帧重建字符串+重排，自踩禁区）；②append 频率硬帽 ≤40/s（tick 25ms 批量多字，防快流 append 风暴）；③离屏销毁 rememberSaveable(revealedCount)+回视口快速追平；多 Part 非活跃 Text Part 快速排空；④行尾光标改溢出绘制（前沿恒为最后节点，下方是卡片空白，零布局操纵零跳动）；⑤排除项检测=delta 流状态机（围栏平衡扫描+表格分隔行入态），零 gate 改动；⑥字形簇 android.icu（minSdk 26 ✓）+单测 seam。承重先例全核实：onTextLayout 钩子在位（MarkdownContent:464）、overlay 模式可复刻（HeldTailReveal:135-187）。spec 已同步修订。
  - 看门狗交互审计（2026-09-30，响应用户 d7b3ffd5 提交）：StreamStallWatchdog(#441/#467) 纯传输层（SseClient/V2 接线），与影响面零文件重叠，调研文档无需改；spec 补两条——①传输层 stall≠中断，禁止把连接状态接豁免触发器（维持卡壳呈现等 backfill）；②重放致非前缀重写走 resetKey 重建时揭示进度即时重置为满。

- [ ] **#439 流式期重组隔离：entries 签名缓存与子卡 skippability 恢复** `streaming` `compose`
  - 流式批（~14/s）仍使流式 turn + 相邻注入卡条目全量重组（真机 35s 524 次）；渲染像素幂等故非闪烁源，属性能债。
  - 修复位：ChatMessageList.kt:741 chatEntries 键改结构签名（仿 :283 turnGroups sig-cache）；MarkdownChunking.kt:291-313 ChatEntry 预载 msg/streaming/key；:2448-2487 item lambda 消除 displayItems/turnGroups 直读；:1361-1375/:1772 回调 lambda remember 化。
  - 2026-09-29 E2E 活证:单轮流式 TextDelta 2877→InjCard 邻项全量重组 4443(1.5x);另一轮 1387→2523(1.8x)——逐 delta 重组放大实测,修点位与量级依据齐

- [ ] **#442 高度引擎根修二期：R2分片增量化(滑动p90 12ms)+cadence收编+flush深拆+终审待复核项** `perf` `refactor`
  - 终审判定：R1批次已锁 A2 贴底5ms/A1回归/A4全项；滑动p90 19-27 未达12——R2分片(稳定块缓存/尾块单测)是 O(内容)→O(尾块) 唯一路径。
  - Medium欠账：cadence结构收编(spec裁决3)、flush八职责深拆(终审S2)、StreamingPairingRule缝退役迁移。
  - 待复核：ScrollQuiescence单例假设、HeldTail锁高裁剪视觉等价、diffDisplayItemsInto边界、SSE铁律逐条。
  - 2026-09-29 用户裁决:吸收 #445 并入——R2 深水区含流式 markdown 稳定/活跃双容器状态管理方案(append-only+前缀吸收,固化解迁移帧问题);随卡迁入用户观感记录:完结窗会小跳一下(2026-09-27,限速节奏参数 BIG_RELEASE_MIN_INTERVAL_MS 可调,视情况修)

- [ ] **#458 DSH 0.1.7 错误码税则漂移：39 值点式闭集全面脱节** `regression,dsh,data`
  - 0.1.7 实发斜杠命名空间码（session/not-found、gateway/arguments-invalid），app DshRpcErrorCode 闭集 isKnown 恒 false 全走 Unknown 兜底（优雅降级成立但分类/文案失准）；/api/respond 已移除改 /result（app 双路已备）。详见 docs/research/2026-09-28-triface-regression-report.md 缺陷 D1/D5

- [ ] **#459 V2 2.0.18 消费侧 14 端点漂移清单（health/question|form request/pty shells/share/rename/service stop 等 404）** `regression,v2,data`
  - app 调用面 45 点中 14 点在 2.0.18 openapi 缺失（全 404 实证）；真机主链路不受影响（探测器/PATCH session 等降级路径实证），但 question/form 轮询兜底、pty shells、share、service/stop 在 2.0.18 下不可用。详见回归报告 §1.2/缺陷 D2

- [ ] **#464 UI 暖态下列表/卡片点击偶发失效(冷启可靠)** `chat-ui`
  - 2026-09-29 #461/#462 取证副产物:force-stop 冷启后输入 tap 可靠命中(会话行/卡标题),同一 app 暖运行数分钟后点击同坐标零效果(无日志无 UI 变化,vibrator 反馈存在=命中可点击元素但未触发业务);两次独立取证会话复现,冷启后恢复。疑点:点击消费被某 overlay/焦点态拦截或状态门;影响面=自动化测试可靠性,人工使用未报告。待真机复现窗定罪(diagnosing-bugs 流程),暂无用户主诉不阻塞。
  - 2026-09-29 审计顺带实证:一次拖动送达零响应不再现(见 #465 注记);#477 dump 全盲为坐标类判读新增干扰源——本卡与 #465 高度重复,建议合并为单卡观察项待裁决
  - 2026-09-29 用户裁决:吸收 #465 合并,本卡为该域唯一观察卡(暖态间歇 input 送达零业务响应;今日一次拖动零响应不再现实证在案;#477 dump 全盲为坐标判读新干扰源)——再撞上即现场抓 input dispatcher+app 双侧日志

- [ ] **#482 V2 prompt.files 嵌套契约未经部署版实证(2026-08-16 TODO)** `v2` `data`
  - V2ApiClient:526:嵌套 body 部署版 next-17430 一律 400→线上一直走平铺降级(files 顶层);主干部署后需 E2E 验证 modernBody 分支再收敛双路
  - 验证成本低(一次带附件 prompt E2E+抓帧);与 #459 漂移族相邻但独立(这是契约实证,非端点缺失)

- [ ] **#479 SSE 连接期 PARTIAL_WAKE_LOCK 持有策略/电池影响未审计** `sse` `perf`
  - 2026-09-29 发掘审计:dumpsys 实测持锁(OpenCodeRemote::SSEConnection,周期性重取)+日志 WakeLock renewed 每 30s(OpenCodeConnectionService:650 起)——抗 MIUI 杀 socket 的保活设计(#441 域)
  - 未审计点:后台期是否释放/持锁时长分布/对电量的真实代价;若后台仍长持=电池债——需 acquire/release 全路径走查+一次耗电基线
  - 2026-09-29 E2E 活证:HOME 退后台 12s 后 PARTIAL_WAKE_LOCK 仍持有(dumpsys ACQ≈2m33s LONG;历史模式 ~9.5min REL+立即续取)——后台不释放确认;余下=电量代价量化+策略裁决(后台长置是否转释放靠重连)

- [ ] **#477 长文 markdown 块 uiautomator 语义零暴露——dump 全盲致渲染正常被误判空白** `ui`
  - 2026-09-29 收口审计真机定罪:末轮长文(async parse 路径)可见区 dump 零文本节点(仅 1 个空 text 可点击 TextView h≈1921px),同期旧 turn 正常暴露;vision(三路交叉)+像素双验证内容完整渲染(连续正文/无空白/无圆点)——渲染层无恙,纯语义暴露缺陷
  - 影响面=自动化验证可靠性+#467 dump 实证段污染(核心 SGR 日志证据不受影响)+a11y;待定位暴露分叉(疑 rememberAsyncMarkdownState 路径)
  - 证据:/tmp/ui_b1.xml(底,盲) vs /tmp/ui_top.xml(顶,正常);#475「近空白/折叠态」原判即此 artifact
  - 2026-09-29 定罪深化(见journal):分叉=单part纯文本长度(同turn 993暴露/3585盲,表格组件不受影响);渲染三层同构排除(组件/库重载/success槽);超视口单item假设被未分段表格turn(3674px)暴露推翻;最强嫌疑=SPLIT_PARAGRAPH 3000拆段线;androidTest断言已写但被MIUI+idle挂死双阻塞;修复候选A完结即分段/B到达即分段/C拆段线定罪后修normalize/D平台上报——待用户裁决,不盲改
  - 2026-09-30 定性翻转(用户裁决B降级观察):今日冷启进程两会话十几个turn(0-20k chars全路径)零盲例——五假设全推翻(拆段线:3585原文标准段落格式未触发/Q换帧:7523混合part同经换帧暴露/长文=盲:4077五段纯散文注入段段暴露/路径=盲/密集dump自伤:10连dump后仍暴露);原定罪(长文语义零暴露)重定性=疑似长跑进程状态性退化(R假设:昨21:22同屏老表格turn暴露vs新essay盲分野=内容创建时刻;盲取证全部集中于4h+单一进程晚期);再撞上(长跑进程新组合内容dump全盲)即抓:logcat a11y错误+dumpsys meminfo+uptime;A11yDiag探针留树备用

- [ ] **#473 调试探针族清扫——历代 campaign 遗留 DEBUG 打点归档** `chore`
  - grep \\\[DEBUG- 盘点:CardExpandReveal(#420-427/466)/ChatMessageList+MessageCardAssistant(hflick/jk)/SafeFlingBehavior(flng)/ChatScrollController(drift)/rbexp 等几十处打点,均 BuildConfig.DEBUG 门控、release 零影响,但污染调试 logcat(472 闪烁定罪时曾混入噪音)。逐族清理+保留关键结构注释;涉及文件多有编辑协议约束,单独批次执行。
  - 2026-09-30 精化+新增三条方法教训:①「retained-subtree 盲区」实为 A11yDiag 语义=组合事件(内容经 pilot/preParsed state 对象流动时外层组合体跳过),内容级探针 MDPilot/ChunkDiag 一直覆盖,r3 实证 243/38 条——无真盲路径,改判读口径即可;②device 侧 grep 命令文本被 adbd in ShellService 记入 logcat→下轮自匹配假增长,监控必须 pull 到宿主 grep;③设备 nohup logcat 的 pkill -f 自匹配自杀(pkill 按进程名);宿主 nohup 不挺过 run_code 退出

- [ ] **#467 V1 外部注入轮次(POST /session/{id}/message)app 不实时渲染** `sse,v1,data`
  - 2026-09-30 #463 流式验证副产物:服务端 POST 触发的完整轮次(glm-5.3-flash 3 step,响应 JSON 正常返回)app 打开态全程未渲染——新 user 消息与 assistant 流式内容均未出现(视口停中部非贴底排除法+dump 底部仍为旧 turn 实证)。疑 app SSE 订阅/事件处理与「app 自发 prompt」绑定(SessionStateService idle 态过滤外部 message.part 事件?)或 SSE 连接已静默断(#441-B 探针可判:death-snapshot lastEventAgoMs)。影响面:仅外部注入轮次,用户正常发送路径不受影响。待复现窗+探针日志定罪。
  - 2026-09-28 补充实证:两次注入(22:35/23:15)服务端完成但 app 零渲染(SGR 无新行),重启即恢复;与 #441 SSE 随机断连同根,用户自发消息不受影响
  - 2026-09-29 审计:#477 登记(长文语义零暴露)——本卡「dump 底部仍旧 turn 实证」段以 dump 判读受污染(dump 对该类内容全盲);核心证据(SGR 日志零新行/重启即恢复)不受影响
  - 2026-09-30 定罪完成(journal 2026-09-30-467-v1retained-subtree §2):S0 健康连接注入全链渲染正常——注入本身无罪;S2 kill-server 断连窗口注入→app 零接收→复隧道后 backfill +6 msgs 免重启补渲染(「重启即恢复」已过时);真根=静默死连接 4min04s 无检测(SSE read timed out 全史料 0 触发,#108 withTimeoutOrNull 打不断 OkHttp 通道桥阻塞读),与 #441 同根;修法已设计(读任务vs超时竞争+response.cancel)待裁决
  - 2026-09-30 修复批次联动:断连窗口注入→恢复免重启补齐 E2E 两验(服务端 REST 全量终态+app msgs=163 落库);S0-S2 定罪结论不变,修复定位=加固层,详 journal §4

- [ ] **#454 v1 真机 IME 换行注入后 prompt 未发出** `chat` `device` `v1`
  - 真机 IME keyevent 66 发送路径:消息含注入换行(Run\n\n)时 prompt POST 未发出,乐观气泡悬挂;二次干净发送正常(prompt_async 202)。发送链路疑有 IME 竞态边角,#453 验证时顺带观察,未复现第二次

- [ ] **#443 markdown 稳态粒度扩展：更多大块逐行/逐段放行（引用块/嵌套列表/长段落折行等）** `streaming`
  - 用户裁决（2026-09-26 #441 后续）：表格/列表已逐行；评估引用块(>)、嵌套列表、def list、长代码块行级、setext 标题等大块的行级定案可行性，逐类 TDD 扩展 SafePrefixGate。

## P4 — 外部前提阻塞

- [ ] **#381 192.168.110.248:248 重配——凭据宿主零痕迹不可探查（2026-09-09 用户裁决入 P4）** `infra`
  - **前提**：上游提供远程凭据探知能力——用户原话「后续看看 dsh 官方是否会支持远程探知 token 或其他认证手段」（DSH 官方远程 token 发现，或 OAuth/设备码流等替代认证）；前提满足后 `cred-probe.sh opencode 248 <名称>` 即可接管（/proc 探针现成）；若上游确认不会支持，再议弃用或人工一次性重配

- [ ] **#352 长按菜单「取消归档」——wire 层无恢复动词（2026-09-07 用户裁决要求，服务器阻塞）** `dsh` `archive` `ui`
  - 裁决原文:「归档单向契约同删除一样在长按弹出框中增加即可」——用户要求已归档行长按菜单加「取消归档」
  - **前提**：上游 dsh 服务器提供恢复动词——实测证据（2026-09-07 深夜，当前部署源码 dsh-api-workspace-controller typert）：WorkspaceArchiveSessionRequest={sessionId} **add-only**，全 API 面仅 archiveSession 一个归档动词，官方 web 客户端同无恢复入口（SessionRowMenu 2026-09-05 四重取证注释仍有效）；动词就位后：菜单项+RPC+已归档折叠区行刷新一步到位（#351 能力位先例同款）
  - 2026-09-29 前提重验:dsh 部署版=0.1.7-rc.2(=npm 最新) dist 全仓仍无 unarchive/恢复动词——前提不变,维持 P4

- [ ] **#332 spill 提示行——服务器无结构化信号（工具结果溢出 notice 内嵌纯文本）** `dsh` `sse`
  - **前提**：dsh-spill-policy 全链查实——溢出替换为有界 head/tail 预览+locator 提示全部内嵌工具结果 output 文本,transcript 无 spill 事件（session 事件枚举/types/实现三路 grep 0）;文案模式匹配脆弱（#136 先例:服务器改文案即静默失效）。待服务器暴露结构化字段再实现。→ `docs/research/2026-09-05-audit-309-313.md` #312③ + 实现 agent 取证（暂不可实现）

- [ ] **#288 workflow 阶段卡（tool-workflow agent-start/end 聚合渲染）** `dsh` `ui`
  - **前提**：dsh 服务器在任何客户端面暴露 tool-workflow 运行事件——events.mux 实况 / session.history journal / session.projection / session/jobs 四面实测皆无（Web 端 workflow 树为 client-ui 本地组件，同一事件源）；服务器升级暴露后重验再启聚合器
  - Task 3b 落地 workflow run-start/run-end 降级单卡（同 runId 原位更新 running→终态）；agent-start/end 维持 Ignored（防逐成员刷卡）
  - 方向：run 级聚合器（成员 label/outcome/phase 折叠进阶段卡，参照官方 tool-workflow 装配）；验证=真机 workflow 运行会话卡片分阶段展示
  - **2026-09-01 活体四面包夹（走查 #9 定性）**：events.mux 实况帧（两次 WS tap + 现跑 workflow 对照，仅 tool/code-dispatch* 渲染伴生）、session.history journal（39 页全翻 0 行，fresh run 亦不入）、session/projection（仅 permissions）、session/jobs（仅 bash 后台任务）四面皆无 → app 侧映射链（DshEventMapper:469 + DshMessageAssembler）为休眠代码路径，非缺陷；走查期「18 事件在 a6c4」不复现（疑当时另有来源/版本窗口）。重开丢卡=结构性（无服务器数据源），DSH synthetic 消息零持久化同因
  - **2026-09-02 复验（差距调研独立交叉确认）**：`docs/research/dsh-gap-2026-09-01/` 四路证据（fe 源码/Android 清点/Web 实测/服务端 api-gap）再次确认服务器事件面无 tool-workflow 运行事件——门维持关闭；聚合器设计参照 fe-inventory §2.17 client-ui-workflow-run
  - 2026-09-29 前提重验:0.1.7-rc.2 dist 仍无 tool-workflow 运行事件——前提不变,维持 P4

- [ ] **#146 OpenCode 官方问题清单（issue/PR 候选）** `upstream`
  - **前提**：上游 anomalyco/opencode 合入变更——需先过用户流程门槛（本地定位官方源码→修复→完整测试含 E2E+交叉验证→人工测试→用户放行才可提交 PR；源码已就位）；2026-09-03 用户裁决长期挂起，上游不提不影响本 app（客户端防御均已落地）
  - ①V2 不发 compaction.started（引擎没接线）②SSE 重连无事件回溯 ③cursor V1 格式返回 400 ④fork handleRaw bug ⑤工具输出截断语义——上游核查完成（repo 已迁 anomalyco/opencode），逐项行动方案已定
  - ⑥候选（2026-08-27 八轮实证）：V2 后台 shell 状态恒 completed（exit 7 亦然），失败信号仅正文文本——上游语义退化，客户端已防御性派生
  - **2026-09-02 逐项复现取证完结（journal 258-stage-b §十）**：源码浅克隆 `~/Documents/code/opencode-upstream`@69c172e + Host-4199 live 复验——①②③⑥ HEAD 仍成立（①连 schema 都无 Started；②端点零回溯处理；③400 已类型化 `_tag` 但无降级；⑥exitCode 从不映射 status）；**④上游已修/改版**（空 body 分支 + payload 改 `{messageID?}`，运行版未跟上，app 现发形状已匹配 HEAD）；⑤不变（FR 开放）。PR 候选排序 ③>⑥>①>②
  - → `docs/journal/2026-08-15-chat-flow-bugs.md`
  - 2026-09-29 前提复核:上游浅克隆 ~/Documents/code/opencode-upstream 已不在本机——重验/提 PR 前需重新浅克隆
