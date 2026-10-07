# OC Beacon — 需求与问题总览

本文档是唯一的**未决工作项清单**：只保留尚未完结的需求与问题卡片。条目完结（用户验收 `[x]`）后**当场迁出**——记录连同证据移入 `docs/journal/` 对应批次文件，本文件不保留完结记录；历史查询走 journal 与 git。

**卡片格式**：标题（含全局编号）+ Tag + 状态 checkbox + **≤3 行**摘要 + 链接。需求全文、实现要点、验证证据一律写在链接目标（spec / journal）中，不内联。登记新批次用 `./scripts/backlog-new-batch.sh "<批次名>"`（自动建 journal 文件）；改动后跑 `./scripts/backlog-check.sh` 校验机械不变量。**放置规则（check 脚本强制）**：卡片一律写在下方对应 **Pn 节内**（按优先级定义归位；一节内新卡置顶）；头部编号行与优先级定义表之间**不放任何卡片**（仅允许编号勘误等注释）。**P4 格式增补**：P4 卡必含「**前提**：…」行——说清实现前提是什么、当前为何不可实现（外部硬阻碍所在）。**术语句**：卡片标题与摘要用词遵循 [CONTEXT.md](CONTEXT.md) 术语表（堆积消息/子智能体/轮次/撤销/中断…）；「待处理」保留给权限/问题（状态词待验证/待办/待裁决不受影响）；Tag 英文与 #N 编号不受中文术语约束；API 英文原词（cursor/fork）合法，_Avoid_ 仅限中文对应词。

**编号**：全局递增，不回收。下一编号：**#530**（2026-10-07 #529 思考卡展开偶发高度跳变（E1 新实例竞态窗））。

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

- [ ] **#528 DSH 压缩报错后会话崩溃(CardExpandReveal 布局坐标)** `dsh` `crash`
  - 压缩报错(summary not smaller)后布局期 onPlaced 对已脱离节点 positionInRoot 抛 IllegalStateException 致 FATAL
  - 修法:isAttached 守卫(CardExpandReveal.kt:1449/1453)

## P1 — 核心功能需求


- [ ] **#529 思考卡展开偶发高度跳变（E1 新实例竞态窗）** `uiux` `scroll` `race`
  - 用户 21:11-21:12 实测两脏样本：均为 E1（回收重组合后首个展开集）topY+H/+H/2 位移，一例集后还有 +198 校正帧；E3+ 全干净
  - PLACED 探针已增补 boxH/host 两字段；15 轮机器复现未触发（需真手指滚动余尾+速点节奏）；嫌疑=finalHCache 抬底/预热/重组合在锚点捕获窗内的交错
  - → journal: docs/journal/2026-10-07-dsh-standard-bugs.md

- [ ] **#527 DSH 工具卡实参键 snake_case 失配(读取卡标题/路径全空)** `dsh` `uiux`
  - DSH wire 用 file_path/old_string/new_string;卡片读 filePath/path/oldString——read/write/edit 标题、打开按钮、内容全灭
  - 修法:extractToolInput 单点键规范化(V1/V2 行为不变:无 snake 键不受影响)

- [ ] **#526 DSH 展开收起方向不稳定(有时向上推动)** `dsh` `uiux` `scroll`
  - standard agent 下思考卡展开有时向上推;怀疑降级路径(流式 turn)或锚归一化 host 键失配
  - 待真机复现+[DEBUG-466]探针定罪

- [ ] **#525 DSH 上下文注入排序在提问下方** `dsh` `uiux`
  - journal 里 runtime-context(user/message kind!=user) 与 agent 指令(system/message)信封时刻 ≥ 提问;#524 把提问回拨到真实发送时刻后注入族整体落到提问下方
  - 修法:DshEventMapper 轮内注入台账+repack 校正事件(handler earliestCreated min 合并兜底)
  - → journal: docs/journal/2026-10-07-dsh-standard-bugs.md

- [ ] **#524 DSH 回复出现在提问上方——journal prompt entry 信封 time 晚于输出（created 后移族双形态）** `dsh,ordering,completion`
  - DSH journal 对 prompt entry 的事件信封 time 语义=落盘/touch 时刻而非发送时刻：①完结 touch 把 created 后移 30s+（提问翻到全部输出之下）；②原生 0.65s 错位（journal 落盘晚于首输出）。双修：SSE/live 域 created 单调防线（earliestCreated，REST 域不设防保 #485）+ swap 双源并入保 pending 发送时刻并按序移位。真机复测视觉序恢复
  - 2026-10-07 根修落地（journal 2026-10-07-523-churn §7）：双防线（SSE/live 域 created 单调防线 + swap 并入保 pending 发送时刻+移位），+4 单测全量 3917 绿；真机复测视觉树序恢复（提问在全部输出上方 ✓）。遗留小项：Room 终值快照与内存修正的落盘时序一次不一致观察（不挡视觉），复核中
  - 2026-10-07 二轮纠正+终修（journal §7.4）：用户复验视觉序仍错（首版树序误读）→ 探针钉死第三条写回路径（完结后 upsert:MergeStrategy 基线刷新覆盖修正）+ journal 协议实证（user/message entry 无创建时刻字段，data 仅 content/id/role/source）→ 终修=源头 mapper promptAdmitTimes 记忆（rpcId→播种时刻，live/fold 统一）+撤回 mergeMessageMeta 防线（V2 #485 锚定回归风险）。终验：Room+视觉 y 坐标双证 user 在全部输出上方。残余：重启后重进旧会话 ~1s 错位（记忆不持久，登记）
  - 2026-10-07 系统性矩阵（journal §7.5）：批次1 steer/2 连发排队/3a 重进/3b 冷启正常轮/4 切换/5 V1V2 回归全过（V2 #485 信封槽位未破坏、V1 完结塌缩 0）；3c 冷启重进原始 bug 会话残余复现（预期内，触发面=冷启×prompt 落盘晚于早期输出 35s 的多工具轮），修复方向=记忆持久化或上游 journal 补字段

- [~] **#523 mock 路径完结振荡——TurnFin 对齐 tail 96↔158 六周期 churn（shard-unreg+pilot 复位循环）** `v1,streaming,completion`
  - V1 mock-md 完结 reconcile 条目身份churn：完结消息换 id 走整树换装而非 #509/#510 原地换名；tail 冻结尾行闪 3-4 次；#g0 不受扰；LongCat 轮无此形态 → docs/journal/2026-10-06-515-eof-megafire-window.md §4
  - 2026-10-06 #515 E2E 矩阵扩展定性（journal 2026-10-06-515 §10）：①完结塌缩间歇性、多语料（-20646/-2116/-3219/-4024 全轮拆 96）；②新增 fire 后变体：wide-table fire 后 tail 224↔96 振荡×2（21:39:25 原生证据）；③非 mock 独占疑点待查（矩阵全走 mock-md；真模型轮 r1-r3 未见——但需系统性真模型矩阵确认）。取证日志 /tmp/515-forensics-*.log
  - 2026-10-06 晚补测升级定性：真模型实锤（LongCat -2503/deepseek -2732@TurnFin，间歇 2/4）+ V2 复现（-3001×2 循环）——mock/V1真模型/V2 全中；DSH 不走此路径（零负向）。B 前构建已存在=非 #515 批回归。优先级升 P0 候选
  - 2026-10-07 调研勘误+根修前两击（journal 2026-10-07-523 §1-§3）：①勘误——V1 完结不换 id（raw SSE×2 + REST 三方对照：流式/完结/REST part id 同为 prt_ 全形态、消息 id 稳定），「完结换名走整树换装」定性推翻（msg_110db0b2 系 user id 误读；裸 xid 系探针 takeLast(14) 截断）；②真机制=完结三击（SSE 完结链毁尾块 MC 重建 6/6 必现 / REST upsert 二连击+bus 对已终态 part 重发布=公共放大器 / CHUNK 静态分片接管换血 #g→#c 键族）；③形态二（wide-table -128×3）=完结三击短尾形态非独立缺陷；④F1 计划侧互斥 + F2 bus 终态纪律已落地（fadcb0d6，+3 单测全量绿）；⑤DSH 免疫解释=完结事件链干净（单事件换名）。修复前基线 8 轮存 journal §3.1，修复后复测进行中
  - 2026-10-07 击1 根修落地（journal §4）：F1+F2 复测 8 轮击2/击3 全消、击1 仍必现（7/7）→ 链条钉死=contentTerminal 依赖 liveText/会话投影组合时序（重建实例首帧误判 false→限速慢铺 196ms）→ G2 修判定数据源（part.time.end 优先，dfd7aa23）→ 复测 2/2 完结塌缩归零+MC 实例存活（retained=true 连续，销毁不再发生）。完整验证矩阵（longpara×8+wide-table×4+short×2）进行中
  - 2026-10-07 终报（journal §5）：三修落地（F1 fadcb0d6 计划侧互斥 / F2 fadcb0d6 bus 终态纪律 / G2 dfd7aa23 终态判定接数据源）验证链闭合——mock 矩阵 14 轮完结塌缩 0/12（基线 6/6）+形态二归零；V1/V2 DeepSeek 真模型腿完结零负向（V2 retained=true 跨完结存活，曾复现的 -3001×2 零复现）；滚动竞态健康。残余负向=fire 换装帧（#515 验收接受面，4k+ 文档 hitch 40-64ms）。verify 态待用户终验收

- [~] **#512 DSH 密码登录插件（dsh-password-login）——免捞 token 的会话铸造/密码生命周期/轮换全员下线** `dsh` `auth` `infra`
  - 独立仓库纯宿主插件（照 keepalive 骨架，GitHub 分发，零客户端 bundle）：未设密码态回环免密铸票 + 密码登录书签页 + 轮换即撤销全体会话（admit 包装 epoch）；oc-beacon 与浏览器均为消费者
  - app 侧回落链（有效 Cookie→免密铸→带密铸→legacy token），ServerConfig.password 双语义零新 UI；四批实施（骨架/密码/撤销/legacy 退役）
  - 设计经五轮 grill 全裁决收敛；环境事实锚定 dsh 0.2.0-rc.2 源码实证
  - → [spec](docs/specs/2026-10-05-512-dsh-password-login-design.md)
  - 2026-10-05 追加裁决（卡片摘要中「零客户端 bundle」以此为准）：①web 登录改用浏览器原生 HTTP Basic 单密码弹窗（用户名忽略），自绘登录表单废弃；②密码管理面板回归 scope——客户端 bundle 注入 dsh 设置页，UI 一律消费 dsh 宿主 UI 原语（keepalive primitives.ts 先例），禁止引入第三方 UI 组件库；③自绘「记住我」废弃，记忆职责归浏览器密码管理器。spec 已同步（§A/§B/§E/§G/§K/Out of Scope）。
  - 修订 2026-10-05：恢复「回收所有会话」按钮（POST /revoke，裁决 #21 推翻）——不改密只踢人+调用者自动换票+未设密码态生效且跨重启保持；动机=yml 热改不撤票缺口。spec §B 已修订。
  - 二次修订 2026-10-05：/revoke 改绝对语义——不自动换票，回收=全员下线含点击者本人（用户裁决；rotate 仍保留改密者存活）；面板文案/单测/E2E 同步（49 断言）。
  - 根路径弹跳（2026-10-05）：无 token 未认证 GET / → 303 /plugins/dsh-password-login/login（authorizeIndex 包装，stale 让位官方 401）——消除回收后缓存空壳与 401 白页两种困惑；F05 零配置全链断言。

## P2 — 优化与锦上添花

- [ ] **#519 DSH 完结换装后思考卡消失——权威消息 content 无 reasoning part，流中思考内容完结即蒸发** `dsh` `streaming` `completion`
  - 真机（2026-10-06 quicksort 轮）：流中 reasoning part（dsh-t1s1_reasoning_ord_0，443 字节 payload）正常渲染+计时；完结 seq 换装后权威消息（seq-...b7e40eebd）parts 仅 3 个 text（4564/390/4555 字符）零 reasoning——思考卡随换装消失，重进亦无。与 #501（正文换装砸出）同族的 reasoning 面：流中可见完结即失
  - 待查：DSH 服务器权威 content 是否本就不含 reasoning（服务器侧形状）vs app 端 DshEventMapper 换装丢弃；对照 DSH 0.2.0 wire 文档 block 序列

- [ ] **#514 DSH legacy token 基建退役（#512 批次④）——捞日志/adb 注入/深链配对清退** `dsh` `auth` `infra`
  - 前置：#512 dsh-password-login 插件用顺后另裁范围（用户已裁决方向，2026-10-05）。
  - 清退候选：宿主侧 token 镜像脚本（~/.dsh/sync-dsh-web-token.sh）、scripts/dsh-pair.sh 配对/adb 注入链、DshPairingParser 深链解析。
  - 预判保留：app 内 legacy 兜底（exchangeToken/token 持久化/输入 UI=回落链末级，服务无插件服务器）。开工时先列影响面清单请裁决再动手。
  - spec：docs/specs/2026-10-05-512-dsh-password-login-design.md（§K 批次④）；实现证据：docs/journal/2026-10-05-512-dsh-password-login.md

- [ ] **#493 模型抽屉键盘保持——切模型不收键盘（三端共享改造）** `keyboard`
  - 真机实测三端一致为「收起→抽屉→回弹」（ModalBottomSheet 独立窗口必夺焦点）；2026-09-30 用户裁决基准=不收键盘。
  - 实现方向：换主窗口内非模态呈现（Popup(focusable=false) 容器或布局内 BottomSheet），保留返回键/点外关闭、scrim、75% 固定高、#379/#405 手势隔离语义；共享组件=三端同改（影响面已裁决）。
  - → docs/research/2026-09-30-server-type-uiux-consistency-audit.md §一

## P3 — 观察与低价值改进

- [ ] **#522 B案升 stable 后余留装备清扫——JankHoldGate A/B 开关 + STREAM_DELTA_BUS 旗标退役（#499 余留范围）** `chore` `refactor`
  - 前置：STREAM_SHARD_PILOT / STREAM_DELTA_BUS 升 stable 并观察一批——当前 stable 均 false，JankHold 冻结链在 stable 仍是承重活体（B案开=旁路），现退役会回归 stable 流式滚动组合风暴。
  - 清扫面：JankHoldGate system-prop A/B、ChatScreen jkHold 冻结链（含 B3 埋点）、STREAM_DELTA_BUS 旗标与旁路分支；probe 按 keep-probes 裁决保留。#499 于 2026-10-06 完成范围内部迁移，余留拆本卡。
  - 2026-10-06 承接评估：前置未达维持——stable 双旗标（STREAM_SHARD_PILOT/STREAM_DELTA_BUS）仍 false（build.gradle.kts stable 块），JankHold 冻结链在 stable 仍是承重活体，现清扫=回归 stable 流式滚动组合风暴。beta 侧两旗标已升 true（2026-10-06 裁决），观察窗进行中；待 stable 随批提升后再开工本卡。

- [ ] **#520 soak 判读签名扩充：流中 MDPilot append 静默 × MDResize d=h 爆发耦合判别（冻结渲染形态）** `soak` `streaming` `infra`
  - #511 收口裁决 A 的尾项（2026-10-06）：#484 签名集（零负向 d/零 RESETKEY）对爆发式正向形态失明——#513 冻结渲染（d=h 单步满高+流中零渐进）在 10-04 soak 全程在场未被抓
  - 判别不能是单条 grep：d=h 单步满高在冷重组场景是健康签名（#509 回收复验判据），须耦合『流式活跃窗口内 MDPilot append 静默』才判冻结；实现挂 run.sh/review.sh 既有管线（复用勿重建）

- [ ] **#500 debug 通道热启动失效——app 前台时 am start 携 debug_url 不生效** `debug-channel` `dsh` `tooling`
  - 真机实证（2026-10-02，dev debug 包）：app 已前台时 am start --es debug_url http://127.0.0.1:3080 --es debug_server_type dsh，am 提示 intent delivered to top-most instance，但 handleDebugProfileIntent 未执行——无 Debug channel requested 日志、服务器不切换；force-stop 后冷启动同参数立即生效。
  - MainActivity launchMode=standard（manifest 实证）；疑点：standard 模式下复用顶层实例不回调 onNewIntent，而新实例路径亦未处理——需源码定位送达路径。
  - 影响面：自动化脚本未先 force-stop 时静默失效；debug-entry.sh 冷启动已规避。附：adbd 会把 ShellService 命令行（含 --es debug_token）回显进 logcat，注 token 后须 logcat -c 清洗。
  - 勘误（2026-09-30 盘点）：「新实例路径亦未处理」疑点过时——MainActivity:291 onNewIntent 已实现且 :298 调 handleDebugProfileIntent（onCreate 路径 :184 亦有）；真因待定罪=manifest 默认 standard 复用顶层实例不回调 onNewIntent 与实证吻合，为何未走新实例 onCreate 需真机送达路径取证；修法候选=singleTop（动 manifest 需评估返回栈语义）

- [ ] **#498 render**
  - 摘要补全（2026-09-30 盘点，正文原落 journal）：CJK 粗体闭界——闭界星号后紧跟 CJK 字符（如 **……。**学）渲染为字面星号；服务端原文+markdown 库 flanking 行为，旧路径同渲染非回归（journal 2026-10-01-442 §7）

- [ ] **#497 子会话底栏三形态分裂 + DSH composer 异步挂载焦点扰动** `subagent`
  - 子会话底栏：V1/V2 整块空白（无任何说明）vs DSH 只读提示行/可输入 composer——同意图三形态；readOnlyHintVisible 现要求 subagentsSupported，V1/V2 不给提示行。
  - DSH subagentMode 异步解析期 composer 先缺席后出现（ChatTextField 无 FocusRequester）→ 焦点/键盘重置——键盘行为按类型不同的真实结构源；对齐机会=V1/V2 也给只读提示行。
  - → docs/research/2026-09-30-server-type-uiux-consistency-audit.md §四D3/D4

- [ ] **#496 空闲长按发送键三端语义分裂——DSH 死手势无反馈** `interaction`
  - 空闲长按发送键：V1/V2=切 shell 模式；DSH=静默 no-op（连提示都没有，比能力位隐藏更差）；2026-09-07 审计 A-4 挂 ASK 至今未裁决。
  - 需裁决统一语义：能力位分派维持（DSH 给 toast 说明/换 steer 入口）或全面重设计。
  - → docs/research/2026-09-30-server-type-uiux-consistency-audit.md §四D2

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

- [ ] **#486 SSE retry 重建帧 watch（原#471④）：断连续传+滚出视口弃树+ever=false 理论盲区，复发再战勿主动开工** `scroll` `chat`
  - 原 #471④ 用户裁决 c 降级观察（v1+v2 四场景不可复现，331k 行取证 Loading=0，#472 hold/registry/preParsed 防御栈有效）；#471 整卡迁移后观察线独立成卡（spec §8：watch 不关）
  - 理论盲区：断连续传+滚出视口弃树重建+ever=false 帧（0 触发）；若复发，残余只剩 staged/换装差（-1330px ③族已消失）——spec §8 残余收窄注记
  - 判读取证：MDResize 负 d / RESETKEY / nonPrefix / rawTail 探针均保留 DEBUG-only（2026-09-30 用户裁决）

- [ ] **#464 UI 暖态下列表/卡片点击偶发失效(冷启可靠)** `chat-ui`
  - 2026-09-29 #461/#462 取证副产物:force-stop 冷启后输入 tap 可靠命中(会话行/卡标题),同一 app 暖运行数分钟后点击同坐标零效果(无日志无 UI 变化,vibrator 反馈存在=命中可点击元素但未触发业务);两次独立取证会话复现,冷启后恢复。疑点:点击消费被某 overlay/焦点态拦截或状态门;影响面=自动化测试可靠性,人工使用未报告。待真机复现窗定罪(diagnosing-bugs 流程),暂无用户主诉不阻塞。
  - 2026-09-29 审计顺带实证:一次拖动送达零响应不再现(见 #465 注记);#477 dump 全盲为坐标类判读新增干扰源——本卡与 #465 高度重复,建议合并为单卡观察项待裁决
  - 2026-09-29 用户裁决:吸收 #465 合并,本卡为该域唯一观察卡(暖态间歇 input 送达零业务响应;今日一次拖动零响应不再现实证在案;#477 dump 全盲为坐标判读新干扰源)——再撞上即现场抓 input dispatcher+app 双侧日志

- [ ] **#477 长文 markdown 块 uiautomator 语义零暴露——dump 全盲致渲染正常被误判空白** `ui`
  - 2026-09-29 收口审计真机定罪:末轮长文(async parse 路径)可见区 dump 零文本节点(仅 1 个空 text 可点击 TextView h≈1921px),同期旧 turn 正常暴露;vision(三路交叉)+像素双验证内容完整渲染(连续正文/无空白/无圆点)——渲染层无恙,纯语义暴露缺陷
  - 影响面=自动化验证可靠性+#467 dump 实证段污染(核心 SGR 日志证据不受影响)+a11y;待定位暴露分叉(疑 rememberAsyncMarkdownState 路径)
  - 证据:/tmp/ui_b1.xml(底,盲) vs /tmp/ui_top.xml(顶,正常);#475「近空白/折叠态」原判即此 artifact
  - 2026-09-29 定罪深化(见journal):分叉=单part纯文本长度(同turn 993暴露/3585盲,表格组件不受影响);渲染三层同构排除(组件/库重载/success槽);超视口单item假设被未分段表格turn(3674px)暴露推翻;最强嫌疑=SPLIT_PARAGRAPH 3000拆段线;androidTest断言已写但被MIUI+idle挂死双阻塞;修复候选A完结即分段/B到达即分段/C拆段线定罪后修normalize/D平台上报——待用户裁决,不盲改
  - 2026-09-30 定性翻转(用户裁决B降级观察):今日冷启进程两会话十几个turn(0-20k chars全路径)零盲例——五假设全推翻(拆段线:3585原文标准段落格式未触发/Q换帧:7523混合part同经换帧暴露/长文=盲:4077五段纯散文注入段段暴露/路径=盲/密集dump自伤:10连dump后仍暴露);原定罪(长文语义零暴露)重定性=疑似长跑进程状态性退化(R假设:昨21:22同屏老表格turn暴露vs新essay盲分野=内容创建时刻;盲取证全部集中于4h+单一进程晚期);再撞上(长跑进程新组合内容dump全盲)即抓:logcat a11y错误+dumpsys meminfo+uptime;A11yDiag探针留树备用

- [ ] **#473 调试探针族清扫——历代 campaign 遗留 DEBUG 打点归档** `chore`
  - grep \\\[DEBUG- 盘点:CardExpandReveal(#420-427/466)/ChatMessageList+MessageCardAssistant(hflick/jk)/SafeFlingBehavior(flng)/ChatScrollController(drift)/rbexp 等几十处打点,均 BuildConfig.DEBUG 门控、release 零影响,但污染调试 logcat(472 闪烁定罪时曾混入噪音)。逐族清理+保留关键结构注释;涉及文件多有编辑协议约束,单独批次执行。
  - 2026-09-30 精化+新增三条方法教训:①「retained-subtree 盲区」实为 A11yDiag 语义=组合事件(内容经 pilot/preParsed state 对象流动时外层组合体跳过),内容级探针 MDPilot/ChunkDiag 一直覆盖,r3 实证 243/38 条——无真盲路径,改判读口径即可;②device 侧 grep 命令文本被 adbd in ShellService 记入 logcat→下轮自匹配假增长,监控必须 pull 到宿主 grep;③设备 nohup logcat 的 pkill -f 自匹配自杀(pkill 按进程名);宿主 nohup 不挺过 run_code 退出
  - 2026-09-30 用户裁决：由于特殊情况先保留（不关闭，与探针永久保留裁决的冲突挂起由用户自持）

- [ ] **#454 v1 真机 IME 换行注入后 prompt 未发出** `chat` `device` `v1`
  - 真机 IME keyevent 66 发送路径:消息含注入换行(Run\n\n)时 prompt POST 未发出,乐观气泡悬挂;二次干净发送正常(prompt_async 202)。发送链路疑有 IME 竞态边角,#453 验证时顺带观察,未复现第二次

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
