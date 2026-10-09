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
