# dsh-standard-bugs（2026-10-07）

> 状态：进行中
> 关联：（spec 路径，若有）·（issue 编号，若有）
> 来源：用户反馈 / grilling / E2E / 顺带发现

<!-- 过程中的取证/验证证据直接写本文件；backlog.md 只留 ≤3 行卡片。 -->

## §1 四症状取证（2026-10-07 19:21-20:10，standard agent，会话 7300b09e）

**取证源**：journal 解压（dsh-pw-demo 容器 /e2e/home/sessions/--e2e--/session-7300b09e-.../session.v4.jsonl.zstd，283 条）+ Room 直查（含 -wal）+ logcat crash buffer。

### #528 崩溃（压缩报错后会话 FATAL）
- crash 栈顶：`IllegalStateException: LayoutCoordinate operations are only valid when isAttached is true` @ `CardExpandReveal.kt:1449`（onPlaced → positionInRoot），主线程布局相（markNodeAndSubtreeAsPlaced 级联中）。
- 服务端侧：`compaction/end error="summary is not smaller than the shadowed content (452 >= 129)"`——压缩报错是服务器合法拒绝；崩溃在报错后 ~40s 的布局抖动窗（surface 折叠/重排期对拆除中节点派发放置回调）。

### #525 上下文注入在正文下方
- journal 时序（turn 1）：system/message(agent 指令, seq9, t=...062) → user/message(提问, seq10, t=...062) → user/message(runtime-context, seq11, t=...063)——注入信封时刻 ≥ 提问。
- #524 已把提问 created 回拨到真实发送时刻（更早）→ 注入族整体落到提问下方（Room 实证：sys 062 / prompt 062 / ctx 063，prompt 未回拨系进程重启后 promptAdmitTimes 内存丢失=已知残余，不影响本卡定罪）。

### #527 读取卡字段缺失
- Room 实证 read part 数据完整（tool="read"、input{file_path}、output 全文）——数据层无罪，渲染层键失配。
- journal 实证 DSH 实参键恒 snake_case：read→{file_path[,limit,offset]}、write→{content,file_path}、edit→{file_path,old_string,new_string}；卡片层读取链为 filePath/path/oldString（V1/V2 惯例）→ read/write/edit 标题、路径、打开按钮全灭。

### #526 展开方向不稳定（有时向上推动）
- 机制定罪（代码链+探针）：DSH chunk 宿主流式期 completed=null 判定正确 → 输出流式期点「思考完毕」横幅走 CardExpandReveal **降级路径**（LocalInStreamingTurn=true → 裸 AnimatedVisibility，无反射锚定）；贴底跟随族把展开增量当流式增长滚掉 → 卡头连同上方整体上推 H。完成后走机件路径反射锚定=向下展开。两态交替=「不稳定」。
- 完成态机件路径数学全净（真机探针）：中部构型 `expand fii=11 fiso=269 +H=128 host=12 norm->12:53` ↔ `close-post fii=11 fiso=269` 镜像平衡；贴底区构型 `expand fii=10 fiso=8448 +H=598 host=10` ↔ `close-anchor mirror consumed=598 ledger=598` 平衡——机件路径无罪。

## §2 修复明细（4 处）

1. **#528 崩溃**：`CardExpandReveal.kt` onPlaced/onGloballyPositioned 两处 `isAttached` 守卫——已脱离节点的坐标无意义，跳过写入（episode 协程随组合销毁同归于尽）。
2. **#527 键失配**：`ToolCardRenderer.kt extractToolInput` 单点 camelCase 别名（file_path→filePath、old_string→oldString、new_string→newString；原键保留；仅补缺席键不覆盖——V1/V2 无 snake 键行为不变）。跨服务器影响面：read/write/edit/registry/contextGroup 五处卡片一次修复。
3. **#525 注入排序**：`DshEventMapper` 轮内注入台账（turn/start 武装制）——提问落位/新注入到达时 repack，按首见序分配 created = T_p−n…T_p−1，值变小者补发 MessageUpdated 校正（复用已发射消息副本保 role/injectionKind；消费端 #524-guard earliestCreated 取 min，旧值无害）。turn/start|turn/end 清台账；未见轮边界的流保持原行为（测试与中途接入天然隔离）。
4. **#526 展开保顶**：降级路径（流式 turn）toggle 时记 (fii,fiso) 与盒高，AV 尺寸稳定（3 帧静止，上限 40 帧）后按 Δ 反射回锚 `requestScrollToItemNoCancel(fii, fiso+Δ)`——与机件路径 (fii, fiso+H) 同式；恢复后视口自然离底，后续流式增长按读历史族免派发。首组合同值非 toggle 不触发（回收重组安全）。降级 toggle 探针 [DEBUG-526] 常驻。

## §3 验证矩阵（真机 192.168.1.2:34935，build 19:47/19:52 两版）

| 项 | 方法 | 结果 |
|---|---|---|
| #525 注入排序 | 重进会话 journal fold + a11y y 坐标判定 | ✅ 注入(362/477)→你好(622)→压缩卡(908)→思考(1280)→回复(1322)；repack 探针 sys 1062→1060 / ctx 1063→1061 |
| #527 read 卡 | 真机可视 | ✅ 「读取 · essay-3000.txt」「写入 · essay-3000-v2.txt」标题+路径恢复 |
| #528 崩溃 | 重发 /compact（本轮压缩成功）+ surface 替换布局抖动 | ✅ 进程存活 pid 不变、crash buffer 零条目、「会话已压缩」呈现 |
| #526 完成态机件 | 中部+贴底两构型各 4-6 次 toggle 探针 | ✅ 数学全净（norm/close 镜像平衡）——本构建与上构建该路径字节一致 |
| #526 流式降级保顶 | 4 轮远端自动化尝试 | ⚠️ 未获视觉证据：deepseek-flash 思考窗 <1s、横幅随输出上移、dump ~1.5s、vision 超时。修复机制与已验证机件路径同式，探针已武装——**待用户真手指复验**（用户可感知场景：输出流式期点思考横幅） |
| 单测 | DSH 全包 555 + 全量套件 + 新增 6 | ✅ 全绿（525 repack 三态 + 527 别名三态） |

## §4 已知边界
- #525：多提问同轮（罕见）注入按最新提问 repack；流中接入（无 turn/start）保持原行为。
- #526：保顶窗内（~650ms）用户主动滚动会被回锚覆盖——真手指复验若观感不佳再议窗口缩短。
