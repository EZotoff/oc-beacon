# 441-sse-stall-docker-e2e（2026-10-01）

> 状态：进行中
> 关联：（spec 路径，若有）·（issue 编号，若有）
> 来源：用户反馈 / grilling / E2E / 顺带发现

<!-- 过程中的取证/验证证据直接写本文件；backlog.md 只留 ≤3 行卡片。 -->

## §1 定位修正 + 批次范围（诚实前置）

**定位修正**：昨日评估「修法已设计、未实现」系误读 journal 2026-09-30-467 §2 的「待用户裁决」旧文——同文件 §4 与 commit `d7b3ffd5`（09-30 04:23）**已交付** StreamStallWatchdog 字节级 45s 执法 + V1/V2 双接线 + CancellationException 翻译防线（单测 4/4 虚拟时钟、全量 3698 绿、SIGSTOP E2E 五场景）；§6 另交付 DSH 期望播种修复（docker pause 92s 冻结恢复已验）。

**本批实际范围**（用户裁决「开始修复 #441 + Docker 容器测试 + 测过即关」）：SSE 路径的 **docker pause 黑洞 E2E**（§4 用 SIGSTOP 冻真机服务、§6 pause 只测 DSH WS 路径，SSE×docker pause 组合未测）+ 双方言心跳契约实测 + 收官迁移。**零代码改动**。

## §2 Docker 隔离环境 + 心跳契约实测

**容器**（colima VM，node:22-bookworm-slim，原生 ELF 不挑基础镜像；宿主 3081 ssh 隧道遗留避开，自选端口）：
- `v1-e2e`：真 opencode-ai **v1.18.32** ELF（~/oc-v1/node_modules ro 挂载）+ 隔离 XDG 副本（~/v1-e2e/{config,data2}，不触碰 live oc-v1-env），--network host 绑 VM 127.0.0.1:**4299**，colima 自动转发宿主 loopback → `adb reverse tcp:4299` → app debug intent 冷启（`debug_server_type opencode`，探测正确判 V1 方言）
- `v2-e2e`：~/.opencode/bin/opencode ELF（v2.x）+ OPENCODE_SERVER_PASSWORD 自设临时值（不涉真实凭据），绑 **4298**，同链路接入（V2 方言判定正确）

**心跳契约实测**（curl 32s 计拍，45s 阈值安全性前提）：
| 流 | 心跳 |
|---|---|
| v1 全局 /global/event | 10s/拍（JSON heartbeat） |
| v1 目录流 %2F、%2Froot、并发双流 | 均 10s/拍 |
| live 4096 v2 /api/event | 10s/拍（`: heartbeat` **注释帧**——readSseFrame 空帧返回路径已打点） |

→ 40s 行级（#108）/ 45s 看门狗（StreamStallWatchdog）双阈值对健康连接 **4x 余量**，零误杀前提成立。

## §3 六场景 E2E 结果（真机 + docker pause 黑洞）

app = master@3a5193fb devDebug 重装（含 d7b3ffd5 看门狗）。执法触发判据 = logcat `SSE read timed out after 40000ms`（V1）/ `V2 SSE read timed out`（V2）。

| # | 场景 | 操作→判读 | 结果 |
|---|---|---|---|
| A | v1 sanity | 冷启双连接（全局+目录）均 200 text/event-stream | ✓ |
| B | v1 健康流式 | CountFrom1to15 → 全渲染（big-pickle 8.7s），零超时 | ✓ |
| C | v1 空闲黑洞 | pause@05:30:23.4 → **36.0s 检测**（最后心跳先于 pause 4s + 40s 窗严丝合缝）→ 梯子（黑洞期 attempt 间隔 ~45s = #305 socketTimeout 封顶连接尝试主导）→ unpause → Connected + `backfill: +2 msgs (SSE_PRIORITY)` | ✓ |
| D | v1 **流式中黑洞**（主场景） | 发送+6s 冻结@05:33:50.7 → **38.3s 检测** → 黑窗 115s → unpause → Connected + `backfill: +4 msgs` → 终态三证：UI 全渲染 1→60 / REST 服务端 4 msgs 全量 / Room 三件套（integrity ok）text part 170B 尾「58 59 60」 | ✓ |
| E | v2 空闲黑洞 | pause@05:40:54.8 → **25.1s 检测**（该连接 pause 前 ~15s 已被 reverse 链伪影饿死，执法照常接管；非时钟偏移，实测 device-host = **-0.78s**）→ 梯子 → unpause → Connected@05:42:04，此后零超时 | ✓ |
| F | v2 空闲 soak | 05:43:07 起 ≥10min：零 read timed out、零 stalled（收尾补记实测数） | 待补 |

**B 探针二分判据实测生效**：场景 D death-snapshot `lastEventAgoMs=40015`（活跃流中断=小值）vs 场景 C `211846`（空闲连接死=巨值，心跳不计入 lastEventAtMs 的设计语义）——与 B 线设计「小值=传输断/巨值=静默型」完全吻合。

## §4 副产物观察（归档不追）

- **reverse 链双连接偶发饿死（伪影族新样本）**：v1 首启双连接 100s 仅收 connected+1 心跳→40s 超时→重连后稳定单流 3min+；v2 首启未复现，但运行中一次连接在 pause 前 ~15s 饿死（场景 E 25.1s 异常的真相）。宿主侧并发 curl 双流心跳正常 → 损耗点在 phone↔host adb WiFi 传输段。归 §4-B 线既有定性（adb 测试隧道专属伪影；日常 LAN 直连不经 reverse；历史全部日志 read timed out=0 与生产稳定一致）——**不追**，判据已埋（复发时 read timed out 在场 + death-snapshot 可二分）。
- 「重启即恢复」再次过时：双方言黑洞后均免重启自动恢复（Connected+backfill）。
- 恢复时刻日志判读注意 device-host 时钟偏移（-0.78s）。
- 换 debug 通道的 force-stop 会杀全部连接——soak 窗需单一通道内完成（本批 v1 soak 被换 v2 打断一次，正式 soak 移至 v2 通道）。

## §5 收官结论（2026-10-01 05:53 soak 收口）

**场景 F 补数**：v2 连接 05:43:07→05:53:48（10.7min）空闲零 `SSE read timed out`、零 stalled/force-cancelling、零断连；期间 05:44:24/05:49:24 两组 provider/model.updated 事件旁证字节流持续。（方法学：grep 判据须锚定 `SSE read timed out` 全串——裸 `stalled` 会命中 MIUI 系统进程的 Installed/isInstalled 子串，本批 5 条假命中教训。）

**#441 关闭依据**：
- 检测精确性 3/3（v1 空闲 36.0s / v1 流式 38.3s / v2 空闲 25.1s，均 40s 行级窗内触发；25.1s 例外已归因 reverse 伪影先饿死）
- 恢复三验 2/2（v1 空闲/流式两轮均 Connected+backfill+持久化；v2 空闲一轮 Connected）
- 误杀 0（v1 3.2min×3 段 + v2 10.7min soak + 双容器全程无健康断连）
- 双方言覆盖（V1 v1.18.32 + V2 2.x 原生二进制容器）；DSH WS 路径由 §6 播种修复覆盖（docker pause 92s 冻结恢复已验）
- 修法主体=已提交代码 d7b3ffd5（V1/V2 StreamStallWatchdog）+ §6 播种批次，本批零代码改动，纯收官验证

**残余（观察项非阻塞）**：①野生复发判据已埋（force-cancelling / silence-watchdog trip / death-snapshot 二分），A3 网络切换 kick 观察窗至 2026-10-19 无反馈即关；②启动期 reverse 链双连接偶发饿死伪影归档不追；③看门狗 onStall 强杀路径在真实僵尸形态仍属单测级证明（该形态 4 次定向复现失败，机制未定）——判据在野复发时自动落袋。

**环境去向**：v1-e2e/v2-e2e 容器保留运行（对齐 dsh-e2e 先例；后续网络类验证直接可用，unpause 态）；~/v1-e2e、~/v2-e2e 隔离数据目录保留；Room 三件套证据 /tmp/441_ocbeacon.db{,−wal,−shm}（integrity ok）留存。

## 已完结卡片迁入（2026-10-01）

### **#441 app SSE 长连接随机断连：输出期间渲染静默（服务端正常）** `bug` `dsh`
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
  - 2026-10-01 Docker 收官批次（journal 2026-10-01-441-sse-stall-docker-e2e）：真 v1.18.32/v2.x 二进制双容器 + docker pause 黑洞六场景全绿（空闲 36s/流式 38.3s/v2 25.1s 均 40s 窗内执法→梯子→unpause 免重启恢复+backfill；soak 10.7min 零误杀；Room/REST/UI 三证闭环）；心跳契约双方言实测 10s/拍=4x 余量；B 探针二分判据实测生效；零代码改动（修法主体 d7b3ffd5 已在）
  - 迁入依据：Docker 受控收官六场景全绿（用户预授权'测试没问题关卡'）：检测精确性 3/3 + 恢复三验 + 误杀 0 + 双方言覆盖 + soak 10.7min；修法主体 d7b3ffd5（09-30）+ §6 播种；残余=野生复发观察（判据已埋）+ A3 观察窗至 2026-10-19（backlog.sh migrate 2026-10-01）（backlog.sh migrate 2026-10-01）
