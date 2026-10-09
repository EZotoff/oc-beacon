# b1-dsh-unarchive-workflow-spill（2026-10-09）

> 状态：进行中
> 关联：（spec 路径，若有）·（issue 编号，若有）
> 来源：用户反馈 / grilling / E2E / 顺带发现

<!-- 过程中的取证/验证证据直接写本文件；backlog.md 只留 ≤3 行卡片。 -->

## #352 取消归档——实现+真机 E2E 验收（2026-10-09）


## 实现（commit 75877d8e）

- 数据层四点：WorkspaceApi/DshWorkspacePort/ChatRepository(+Impl)/DshApiClient 各加 unarchiveSession——wire 形状与 archiveSession 同款（V012 门控、`workspace/unarchiveSession`、回执 {archivedSessionIds} 完整新集合）。行去留不乐观更新，由 workspace/follow {type:'archived'} 增量（集合替换式）驱动，与归档同链。
- UI：SessionRow 已归档行详情在能力位内新增「取消归档」按钮（onUnarchive 穿线 SessionTreeList→Screen→ViewModel.unarchiveSession）；SessionTreeList/SessionRow 两处「归档单向契约」陈旧注记更新为前提重验沿革。错误哨兵 ERROR_UNARCHIVE_FAILED→session_unarchive_failed。
- i18n：session_menu_unarchive/session_unarchive_failed ×15（意语撇号转义经 python 修正——sed a\ 会吞反斜杠，坑记录在案）。
- 单测：DshApiClientTest 2（wire 钉死+V011 unsupported）+ ChatRepositoryImplTest 1（端口代理）+ SessionListViewModelArchiveTest 3（成功/失败哨兵/断连快速失败）镜像 #311 三态；全量 testDevDebugUnitTest --rerun 绿；i18n-check 878×14 PASSED。

## 真机 E2E（houji，DSH@3080 现网，验收委派裁决路线）

全判据 PASS（证据 /tmp/352-e2e/，报告全文见当批执行 agent 输出）：
- 按钮存在：已归档行详情「取消归档」+「复制会话 ID」并存；主列表行反向只显「归档」（能力位随归档态正确翻转）
- 取消归档三通道：折叠头 7→6 + 行回主列表分组 / logcat "Unarchived session … (receipt: 6)" + Ktor /api/workspace/unarchiveSession / 服务端 workspace.json 7→6 差集恰为目标
- 跨 force-stop 冷启持久（UI 6 + 服务端 6）
- 归档复原：集合与基线恒等（对称差空）
- 失败路径双证据：断 reverse 后 RPC 级 ConnectException→取消归档会话失败 snackbar；断连闸门「服务器已断开」snackbar；行不动服务端不变

## 环境事实（后续 DSH E2E 必读）

- **live DSH@3080 于 2026-10-09 15:59 启用访问密码**（~/.dsh/storages/dsh_password_login.json）；app 存量 cookie 与 ~/.dsh/token（9-27 旧值）全 401。E2E 绕行：journalctl --user -u dsh.service 取启动行 fresh login token → app `--es debug_token` extra 交换（#317 机制）。今后 DSH E2E 入口须带当前 debug_token。
- 7 个归档中 2 个孤儿 ID（不在任何 workspace.sessionIds）——取消归档后的呈现未测，非本卡范围。

## 已完结卡片迁入（2026-10-09）

### **#352 长按菜单「取消归档」——wire 层无恢复动词（2026-09-07 用户裁决要求，服务器阻塞）** `dsh` `archive` `ui`
  - 裁决原文:「归档单向契约同删除一样在长按弹出框中增加即可」——用户要求已归档行长按菜单加「取消归档」
  - **前提**：上游 dsh 服务器提供恢复动词——实测证据（2026-09-07 深夜，当前部署源码 dsh-api-workspace-controller typert）：WorkspaceArchiveSessionRequest={sessionId} **add-only**，全 API 面仅 archiveSession 一个归档动词，官方 web 客户端同无恢复入口（SessionRowMenu 2026-09-05 四重取证注释仍有效）；动词就位后：菜单项+RPC+已归档折叠区行刷新一步到位（#351 能力位先例同款）
  - 2026-09-29 前提重验:dsh 部署版=0.1.7-rc.2(=npm 最新) dist 全仓仍无 unarchive/恢复动词——前提不变,维持 P4
  - 2026-10-09 前提满足解封（P4→P2）：现网=本机 systemd dsh.service@3080 跑 npm 全局 0.2.0-rc.2，运行安装树四路实证动词在（dsh-workspace index.js:551 / api-workspace-controller :317 Remote("unarchiveSession") / api-remotes client :12447 / .d.ts:224）；follow 增量 {type:'archived'} 在；workspace.json archivedSessionIds 非空=归档在用。上游 UI/E2E 佐证（官方 web 'Unarchive session' 菜单项+跨 reload 持久）。证据：docs/research/2026-10-09-p4-upstream-preconditions-check.md
  - 勘误：2026-09-29「0.1.7-rc.2 全仓无 unarchive」复验系只查顶层薄包的误报——真代码在 ~150 个平行发版的 dsh-* 子包（顶层仅 20 文件 CLI 启动器）；后续 dsh 上游核查一律查子包+GitHub 源码
  - 2026-10-09 排程裁决：B1 DSH 功能批第 1 项（B1.1）——前提最干净先赢一张；验收走 AI 真机 E2E（归档→取消归档往返+跨重启持久+follow 增量刷新）
  - 迁入依据：实现+全量单测绿+i18n 绿+真机 E2E 五判据全 PASS（验收委派裁决 2026-10-06：无大 UI 改动卡 AI 系统性 E2E 过即关卡）；净状态零变化（服务端集合恒等）（backlog.sh migrate 2026-10-09）

## #288 冒烟关卡——现网 workflow 真实样本（2026-10-09）


## 冒烟执行

headless 触发（/tmp 工作区，不进用户真实 workspace）：`dsh --profile headless --json "Please use the workflow tool … two agents alpha/beta"`——final 确认 workflow "smoke-288" completed (2 agents)。样本 journal：`~/.dsh/sessions/--tmp--/session-b318811c-406b-40ce-923a-005e200eff40/session.v4.jsonl.zstd`；headless 事件流 `/tmp/288-smoke-events.jsonl`。

## 关卡判据：PASS

journal 解码出完整四事件序列（runId=91690cb6…）：
- run-start {runId, name:"smoke-288"}
- agent-start seq=1 {label:"alpha", phase:"smoke", childId:10affb95…}
- agent-start seq=2 {label:"beta", phase:"smoke", childId:40fafcf3…}
- agent-end seq=2 {outcome:"completed"}（**先于 seq=1**——并行执行，end 序与 start 序无关）
- agent-end seq=1 {outcome:"completed"}
- run-end {runId, stopReason:"completed"}

## 聚合器设计输入（真实载荷事实）

1. agent-start 全量：label/phase/childId/seq；agent-end **只有 (runId,seq)+outcome**——成员身份必须从 start 建档，end 靠 seq 回填（end 先到 start 未见的分页边界→按 seq 建骨架成员）。
2. childId=子会话 id——未来可做导航（注意 #242 信封箭头守卫：synthetic 卡不可给会话箭头）。
3. 重放幂等：journal 冷回放按序全量重放，账本按 (runId,seq) upsert 天然幂等。
4. headless --json 事件流与 journal 事件是两个面（前者 step 级 status，后者 session 事件）——app 消费面=后者（session/follow SessionEventEntry / journal 首页兜底）。

## #288 run 级聚合器——实现+真机 E2E（2026-10-09，commit 7cfab01a）


## 实现

DshEventMapper：runId 键控账本（WorkflowRunState：name + members[seq]，软上限 64 run 防长跑无界）；agent-start 建档（label/phase/childId 留档不渲染——#242 守卫不给会话箭头）、agent-end 按 seq 乱序回填 outcome（实测 end 序与 start 序无关）、(runId,seq) upsert 幂等；进度卡滚动摘要 "workflow: name · N agents · M done" + 阶段分组正文（[phase] ✓/✗/▸ 成员行）；run-end 优先账本名+终态成员清单。词表收编：DshEventVocabulary 两条 agent 忽略项与 WORKFLOW_AGENT 常量退役。测试 5 新增（滚动进度/账本名+成员终局/孤儿骨架/重放幂等/MALFORMED）；全量绿。

## 真机 E2E（houji，冒烟会话 session-b318811c journal 回放）

全判据 PASS（证据 /tmp/288-e2e/）：
- name 摘要：UI 与 Room 均 "workflow: smoke-288"（无 runId 前缀串——账本名生效）
- 成员折叠三样：展开正文 "completed [smoke] ✓ alpha ✓ beta"（非 Task 3b 降级形态）
- 单卡原位更新：dump 恰 1 次 + Room 恰 1 条 dsh-workflow 消息 + logcat 10+ 次 MessageUpdated 同 id
- Room 二通道：cached_parts text = `<task state="completed"><summary>workflow: smoke-288</summary><task_result>completed [smoke] ✓ alpha ✓ beta</task_result></task>`
- 会话完整性：9 条消息完整（首问+助手终答含 alpha/beta JSON 可见）
- 冷启免再认证（#352 cookie 持久化生效）

## 顺带发现（脚本直接修域）

pull-app-db.sh 0 字节假阳：脚本用 PATH 裸 adb，run-as cat 静默失败拉出 0 字节文件而 sqlite integrity_check 对空库返回 ok——须加最小体积门槛+固定 adb 路径（当批直接修）。

## 已完结卡片迁入（2026-10-09）

### **#288 workflow 阶段卡（tool-workflow agent-start/end 聚合渲染）** `dsh` `ui`
  - **前提**：dsh 服务器在任何客户端面暴露 tool-workflow 运行事件——events.mux 实况 / session.history journal / session.projection / session/jobs 四面实测皆无（Web 端 workflow 树为 client-ui 本地组件，同一事件源）；服务器升级暴露后重验再启聚合器
  - Task 3b 落地 workflow run-start/run-end 降级单卡（同 runId 原位更新 running→终态）；agent-start/end 维持 Ignored（防逐成员刷卡）
  - 方向：run 级聚合器（成员 label/outcome/phase 折叠进阶段卡，参照官方 tool-workflow 装配）；验证=真机 workflow 运行会话卡片分阶段展示
  - **2026-09-01 活体四面包夹（走查 #9 定性）**：events.mux 实况帧（两次 WS tap + 现跑 workflow 对照，仅 tool/code-dispatch* 渲染伴生）、session.history journal（39 页全翻 0 行，fresh run 亦不入）、session/projection（仅 permissions）、session/jobs（仅 bash 后台任务）四面皆无 → app 侧映射链（DshEventMapper:469 + DshMessageAssembler）为休眠代码路径，非缺陷；走查期「18 事件在 a6c4」不复现（疑当时另有来源/版本窗口）。重开丢卡=结构性（无服务器数据源），DSH synthetic 消息零持久化同因
  - **2026-09-02 复验（差距调研独立交叉确认）**：`docs/research/dsh-gap-2026-09-01/` 四路证据（fe 源码/Android 清点/Web 实测/服务端 api-gap）再次确认服务器事件面无 tool-workflow 运行事件——门维持关闭；聚合器设计参照 fe-inventory §2.17 client-ui-workflow-run
  - 2026-09-29 前提重验:0.1.7-rc.2 dist 仍无 tool-workflow 运行事件——前提不变,维持 P4
  - 2026-10-09 前提满足解封（P4→P2）：现网 0.2.0-rc.2 运行安装内发射代码齐备（dsh-tool-workflow lib/index.js:98/109/117/124 四事件 append，顶层调用默认记录无开关）+ session/follow 流原始事件帧（SessionEventEntry）透传在。149 journal（138M/7 工作区）+演示容器 10 journal 全解码 0 真实样本=顶层 workflow 从未跑过（宽松命中 5 处全为开发会话 grep DshEventMapper.kt 假阳性）——非版本缺口。2026-09-01「四面皆无」系当时 live 版本旧于 0.1.2-alpha.2（npm bisect：发射代码 08-30 起）。开工第一步=现网真跑一次顶层 workflow 冒烟取样本（follow/journal grep tool-workflow/）；聚合器消费 journal 原始事件帧非消息投影。证据：docs/research/2026-10-09-p4-upstream-preconditions-check.md
  - 2026-10-09 排程裁决：B1 第 2 项（B1.2）；第一步现网顶层 workflow 冒烟为关卡——不过则退回 P4 登记新证据（止损门）
  - 2026-10-09 冒烟关卡 PASS（B1.2 前置达成）：现网 headless 真跑 workflow 取到完整四事件样本（journal 解码实证，载荷事实与样本路径见 journal 2026-10-09-b1 批）——聚合器开工，agent-start/end 关联键=(runId,seq)，end 序与 start 序无关须容忍乱序回填
  - 迁入依据：冒烟关卡 PASS（现网四事件样本实证）+ 聚合器实现全量绿 + 真机 E2E 五判据全 PASS（验收委派裁决路线：无大 UI 改动，全部仪器可断言面）（backlog.sh migrate 2026-10-09）
