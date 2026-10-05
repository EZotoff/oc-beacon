# 490-dsh-echo-race-double-send（2026-10-01）

> 状态：已交付待验收
> 关联：#490（卡片）· #356（pending echo 播种机制来源）· #437 验收十五轮（写序根修，同域前科）
> 来源：用户反馈（2026-09-30 #458 演示两度复现）

<!-- 过程中的取证/验证证据直接写本文件；backlog.md 只留 ≤3 行卡片。 -->

## 一、定罪（双通道证据链）

### 1.1 服务器侧直查（live 3080，session/page 权威转录）

token 交换后逐页拉取 09-30 全部 `session-*` 演示会话，数 `user/message` 记录：

| 会话 | 发送时刻（app 日志） | 服务器 user/message | source.rpcId |
|------|---------------------|--------------------|--------------|
| session-fe568b61（22:21 E2E send） | 22:21:31 | **1 条**（seq=45927） | 20b6f5df… ✓ 与播种一致 |
| session-7e017102（22:34 / 23:19） | 两轮 | 各 **1 条**（seq=11 / seq=28） | 各自一致 |
| session-6753dda6（23:29 / 23:32） | 两轮 | 各 **1 条**（seq=42510 / seq=42522） | 各自一致 |

**服务器从未双收**——双提交、服务器双播假设双双排除。（唯一同文双行 seq=42390/42401 相隔 8s、
rpcId 不同=用户真实连发两轮「没用就清理掉吧」，非本症。）

### 1.2 app 侧 logcat 取证（hitl3 捕获恰好覆盖演示时段）

五轮 DSH 发送的换装时序逐轮判读（`[send-seed]` / `[msg] MessageUpdated` /
`[dispatch] MessageRemoved` 毫秒级时间戳）：

| 时刻 | 播种 vs 持久回显 | 拆除时机 | 结果 |
|------|----------------|---------|------|
| 22:21:31 | 播种 .864 **>** 回显 .842 | .843（无行 no-op） | **幽灵** |
| 22:34:21 | 播种 .246 < 回显 .247 | .248（有行，正常换装） | 正常 |
| 23:19:35 | 播种 .634 < 回显 .916 | .917（有行） | 正常 |
| 23:29:41 | 播种 .475 **>** 回显 .458 | .458（无行 no-op） | **幽灵** |
| 23:32:48 | 播种 .344 **>** 回显 .312 | .316（无行 no-op） | **幽灵** |

### 1.3 根因（一句话）

**pending echo 换装握手是顺序敏感的**：拆除事件（`MessageRemoved(pending-<requestId>)`）
由 mapper 附着在 WS 持久回显帧上发射；DSH 0.2.0-rc.2 下服务器广播可**先于** prompt RPC 的
HTTP 响应到达（实测提前 17/22/101/948ms）——拆除时刻幽灵尚未播种（幂等 no-op），HTTP 返回
后的本地播种（`ChatRepositoryImpl.promptAsync` → `[send-seed]`）成为**永不拆除的持久幽灵**。
幽灵 + durable = 单发双消息；双写 Room = 重进仍在（同进程内存态恒在；跨进程则视回放而定）。

v2 通道免疫的机制解释：admission.id 即 durable id（同源同 id），SSE 回显按 id 幂等合并
（idx≥0 替换），**与到达顺序无关**。DSH 的合成 `pending-<requestId>` ≠ durable seq id，
必须依赖握手——而握手假定「播种先于拆除」，该假定在 0.2.0 线面上不成立。

Room 终态佐证（10-01 04:58 三件套直查）：`cached_messages` 中 `pending-*` 行 = **0**
（演示期幽灵行已被后续重进的历史回放拆除清除）；演示会话同 created 多行 =
user + agent-instructions/runtime-context/skill-catalog 注入行（#385/#387 折叠语义），非重复。

## 二、根修（两层，commit 单项）

修改点全部在 `data/repository/handler/MessageEventHandler.kt`：

1. **握手顺序无关化**（根因消除）：新增 `preDemolishedEchoes` 已拆待播台账
   （`LinkedHashMap`，FIFO 上界 32——requestId 每次发送新铸 UUID，淘汰永不误伤）。
   - `handleMessageRemoved`：pending-* 拆除时刻登记（无论行在场与否）；
   - `handleMessageUpdated`：pending-* 播种命中台账即丢弃（不进内存不落 Room，
     探针日志 `[echo-drop]`，DEBUG-only）。终态恒等式：「pending-* 在拆除后必不存在」
     与到达顺序解耦。
2. **存量幽灵自愈**（防御纵深）：`upsertMessages`（全 REST 刷新策略汇入点）追加
   `sweepStalePendingEchoes`——REST 快照不含 pending-* 行，刷新时仍在场且超
   `STALE_PENDING_ECHO_MS`(120s) 宽限的 pending-* 行 = 拆除丢失的幽灵（WS 断连窗口/
   历史版本残留），复用拆除原语（`handleMessageRemoved`：内存三清 + Room 待删单写协程）
   清淤，探针日志 `[echo-sweep]`。新鲜 pending 行（宽限内）存活，不误伤在飞发送。

不采的方案（用户「补偿≠根修」红线对齐）：播种延迟等 WS 先行（定时器补偿）、文本比对
去重（同文真实连发存在，误伤）、RPC 响应取 durable id（服务器不回，且 id 为客户端
seq 派生）。

**测试**：新增 `MessageEventHandlerEchoRace490Test` 5 例——拆除先于播种丢弃幽灵且不落库 /
播种先于拆除正常换装（回归）/ 非 pending 重放不受台账影响 / 清淤删超龄行（Room delete
入队）/ 清淤保新鲜行；既有 `MessageEventHandlerRemovedPersistTest` 5 例（#437 写序域）
零改动通过。全量 `testDevDebugUnitTest --rerun` BUILD SUCCESSFUL（2m35s）。

## 三、真机验证（V5，dsh020-live @ houji，2026-10-01 05:00-05:04）

dev 包 install -r（保数据）→ debug intent（dsh + token）→ 会话列表 ✓ → 进入
session-6753dda6（演示幽灵宿主）→ 连发 4 轮测试消息（`490 race test …`）：

| 轮次 | 落序 | 证据 | 终态 |
|------|------|------|------|
| reply one word | 播种 .174 → 回显 .262 → 拆除 .262 | 正常换装 | UI ×1 |
| round 2 | **回显 .063 → 播种 .164（迟 101ms）** | **`[echo-drop]` 命中** | UI ×1 |
| round 3 | 播种 .364 → 回显 .369 | 正常换装 | UI ×1 |
| round 4 | **回显 .142 → 播种 .090（迟 948ms）** | **`[echo-drop]` 命中** | UI ×1 |

- 4 轮 UI 计数全部恰 1 条（uiautomator dump 全文计数）；round 2/4 旧版必成双发
  （正是演示期 3 次幽灵的同款落序）。
- 重进会话（BACK→列表→再入）：各消息仍恰 1 条（持久化无双）；Room `pending-*` = 0。
- 会话 6753dda6 的旧演示消息（answer…2 plus 2 / 好）重进后各 1 条，无历史幽灵。
- 探针 `[echo-drop]` 两度实弹命中（W 级，grep 签名 `echo-drop|echo-sweep`）。

测试代价注记：4 条 `490 race test*` 消息留在了 live 服务器的 job-helper 会话
（session-6753dda6）转录中（该会话即本项目工作会话，消息自标识可辨）。

## 四、遗留与边界

- **WS 回显彻底丢失**（断连且重连后不补投）时 pending 行依赖清淤兜底（宽限 120s 后
  任意 REST 刷新清除）——不再有「永不拆除」路径。
- 台账/清淤均为进程内存态：进程死亡竞态窗口内（RPC 未返回）播种与拆除同灭，无幽灵
  可生（播种在 RPC 响应处理器内，同进程生命周期）。
- follow/历史回放重投 user/message → 拆除重登记（幂等 put），台账 FIFO 上界 32 防潮。

## ## 五、用户验收（2026-10-01）


- 用户真手测试（05:10:32，session-7e017102，dsh020-live）：单发一条——logcat 落序为
  **竞态败者**（WS 持久回显+拆除先于 RPC 响应），`[echo-drop] pre-demolished pending echo
  pending-be5a5831` 实弹拦截，UI 单条；用户确认「ok，没问题了」→ 验收通过。
- 累计真机证据：agent 4 轮（2 竞态 2 正常）+ 用户真手 1 轮（竞态）= 5 轮全单条，
  两种落序均有实弹覆盖。

## 已完结卡片迁入（2026-10-01）

### **#490 单次发送显示两条相同消息并持久化（复进入仍在）** `chat` `bug`
  - 2026-09-30 #458 演示中两度复现：单次 tap 发送键后显示两条相同用户消息，重进会话仍在=已持久化；触发环境=agent 侧 input text 注入+tap（dsh020-live 通道），真手指是否复现待用户确认
  - 定罪方向：①IME 注入伪影 vs 真发送链路双提交②本地乐观双播种 vs 服务器双收（重进在=至少一侧持久化）③dsh 通道特有 or 通用（v2 通道对照待采）
  - 诊断锚点：MsgEventHandler/Room 直查看服务器是否两条；本批未动发送链路（纯 0.2.0 适配），回归嫌疑低但需仪器定罪
  - 2026-09-30 通道对照证据（模块 B 演示采集）：v2 2.0.19 通道同一注入手法（input text+tap 单击）单次提交——V2Api [prompt] 日志仅 1 次、POST /prompt 仅 1 次（200/142ms）、UI 显示 1 条；对照 dsh020-live 通道两轮均双发+持久化 → 双发为 DSH 通道特有（发送链路分叉在 DshApiClient prompt 路径或 dsh 事件回流双播种），v2 路径排除
  - 2026-10-01 根修交付待验收：定罪=换装握手顺序敏感（WS 持久回显+拆除先于 prompt RPC HTTP 响应到达，实测提前 17-948ms，hitl3 三次幽灵落序铁证；服务器直查全部 1 条=非双收）；修复=已拆待播台账（播种命中即丢 [echo-drop]）+ REST 刷新超龄 pending 清淤 [echo-sweep]（MessageEventHandler 单文件）；真机 4 轮两竞态两正常全单条+重进持久化 ✓、5+5 单测+全量绿。详 journal 2026-10-01-490
  - 迁入依据：2026-10-01 用户真手验收通过（手测轮恰撞竞态落序被 [echo-drop] 拦截，单条显示「ok，没问题了」）；根修两层（握手顺序无关化+超龄清淤）+ 5+5 单测 + 全量回归 + 真机 5 轮两种落序全单条（backlog.sh migrate 2026-10-01）
