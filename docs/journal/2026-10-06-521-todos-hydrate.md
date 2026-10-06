# 521-todos-hydrate（2026-10-06）

> 状态：进行中
> 关联：（spec 路径，若有）·（issue 编号，若有）
> 来源：用户反馈 / grilling / E2E / 顺带发现

<!-- 过程中的取证/验证证据直接写本文件；backlog.md 只留 ≤3 行卡片。 -->

## 实施（commit 655b4498）

- 摸底（live 0.2.0-rc.2 服务端源码 @linuxbrew dsh node_modules）：
  - dsh-tool-todo 注册 todos 投影：stateSchema 数组整值，apply = todo/write→data.todos / **turn/start→null（turn 级清空语义）**，wire 视图直发；SessionControlController.control() 流（RPC `control`，Remote stream）onOpen 即发 baseline {projections:{sid:{asOfSeq,values}}} + 增量 {type:"projection",sessionId,key,value,seq} 帧；snapshot(session) 无 keys 参数=全 wire 键（todos 在内）。
  - session.list 行内嵌 projections:{asOfSeq,values}（api-session-controller 1871-1880）——REST 读取面成立（0.1.2+ 无 session.get，list 是唯一权威源）。
  - app 端链路已全在库：DshRemoteMuxEngine.onControlValue 把 baseline/增量投影统一镜像为合成 session/projection 帧（emitProjections 全 key 透传，JsonNull 跳过）——唯一缺口 = DshEventMapper key 分派无 todos 分支（落 PROJECTION ignored）。
- 实施（双通道）：
  1. DshEventMapper session/projection 增 "todos" 键：JsonArray→TodoUpdated(mapTodoItems 单源映射，与 mapTodoWrite 共用)；JsonNull→TodoUpdated(空表)（服务端 turn/start 清空，同 goal/permissions/plan tombstone 语义）；其余 MALFORMED。
  2. DshApiClient.getSessionTodos 桩→真读：sessionRowOf（session.list 行查找，自 sessionListItemOf 拆出空容错变体）→ projections.values.todos 数组→TodoItem(medium)；行缺席/投影缺席/值 null→空表。**修掉关键竞态**：旧桩成功返回空表 → repository onSuccess hydrateTodos(sid,[]) 无条件覆写，会把 baseline 刚补灌的 todos 清空（probeTodoCapability 与 WS baseline 时序不定）。
  3. 冷进补灌覆盖：WS onOpen control baseline（全会话，零额外 RPC）+ probeTodoCapability REST 同位 hydrate（V1 流程同构）。重连回填 backfill dispatch 全量 fold 产物本就含 TodoUpdated。
- 单测：DshEventMapperTest +3（todos 整值/null tombstone/malformed；unknown-key 测试样本 todos→title）；DshApiClientTest 重写降级注释 + 新增投影读取测试（含 null tombstone 同归空表）。定向 243 绿；全量 :app:testDevDebugUnitTest 3907/3907 绿。

## E2E 与二轮根修（commit dd957af0）

- **服务器腿实证**（演示容器 dsh-pw-demo@8181，真实 dsh 0.2.0-rc.2 + 宿主凭据拷入）：
  - journal 注入两次失败教训：宿主 zstd 单帧大块被 scanZstdFrames 判 corrupt（服务端 journal=多帧链，每 append 一独立帧）；帧链修复后会话进列表但 asOfSeq 停在 cache 水位（list 行投影=纯持久 cache 读 cachedSnapshot，journal 不折叠）。
  - 转真实提交路线：容器 prompt「用 todo_write 建任务清单」→ deepseek-flash 真调工具 → session.list 行 projections.values.todos=[3 条]（asOfSeq 31）+ WS /api/remote.mux control 流 baseline 同值——两通道服务端实证。
- **E2E 定罪（模拟器 ocbeacon-e2e + PW-Docker 10.0.2.2:8181，dev intent 冷启）**：
  - 首轮 TodoSheet 空（「No TODO items」）但服务器 REST 冷读同刻返回非空——插桩三探针（TodoUpdated dispatch / setTodos / probe 结果）复跑。
  - 第二轮全绿（dispatch last-wins n=3、setTodos n=3、getSessionTodos ok n=3）→ 首轮=竞态：服务器 dsh 重启丢未 flush 的投影 cache 行（kill -9 复现实证）→ session.list 冷读 todos 键缺席 → getSessionTodos 空表 → repository onSuccess 无条件覆写，若晚于 backfill/投影帧即空终态。
- **二轮根修（dd957af0）**：
  1. 三态语义：todos 键**缺席**（cache 冷 miss）→ 抛 IllegalStateException（repository 失败即跳过 hydrate，无覆写；DSH 能力位静态声明不受 probe 失败影响）；值 **null**（turn/start 清空）→ emptyList（覆写正确）；数组 → 真值。containsKey 区分（kotlinx Json 缺键与 null 值同返 null，首版漏判被单测抓出）。
  2. listMessages 首页兜底：before=null（最新窗口）时 transcriptEvents 附带 fold 内最后一笔 TodoUpdated（journal 源，免疫 cache 丢失；takeLast(1) 防新→旧翻页倒退）。
- **复现实验终态 PASS**：删 cache todos 行 + 重启 dsh（REST 冷读 KEY-ABSENT 复现）→ 冷进会话 → logcat 无空覆写（probe fail 不 hydrate）+ n=3 存活 → TodoSheet 视觉断言 3 条完整（接入 todos 投影帧✅ / 写映射单测✅ / 真机验证冷进补灌🔄 in_progress 紫点）+ FAB TODO 角标"1"。常态场景（cache 热接）此前一轮已 PASS（setTodos n=3）。
- 单测：DshApiClientTest 135（+三态/键缺席/首页兜底）、DshEventMapperTest 101（+todos 键 3 例）；全量 3909/3909 绿。
- **真机腿挂起**：小米 houji 无线 adb 死亡（mDNS 记录残留 192.168.1.110:41571 拒连，多轮重试无端口轮换，USB 未插；按定规不动系统设置）——待设备恢复后补真机冷进一验即可收口（模拟器已全链实证）。
- 探针（#521-probe ×3）按 keep-probes 裁决保留 DEBUG-only。
