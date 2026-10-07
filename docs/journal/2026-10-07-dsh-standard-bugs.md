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

## §5 V1/V2 影响面核查（2026-10-07 20:12-20:14，用户红线指令）

### 改动面枚举（a7f8d413 共享文件 hunk 级）
- CardExpandReveal.kt（V1/V2/DSH 共用）3 hunks：①import；②**降级分支**（流式/无 listState 早退块——机件路径零字节改动）；③onPlaced/onGloballyPositioned isAttached 守卫（仅跳过原本必崩的读取）。
- ToolCardRenderer.kt 1 hunk：extractToolInput 别名（`!containsKey(camel)` 才补——camel 键在场永不覆盖）。
- DshEventMapper.kt：DSH 专属管线，V1/V2 不经此路径。

### 实证一：Room 全历史键集普查（别名链对 V1/V2 是死代码）
V1/V2 域 read=`filePath[,offset,limit]`、edit=`filePath,oldString,newString`、write=`filePath,content`、bash=`command`——**零 snake_case 键**；DSH 域恒 snake_case。别名触发条件（snake 键在场）在 V1/V2 永不成立。

### 实证二：真机 V1（4199）思考卡回归
三卡两轮 toggle：`expand (7,128)+194 host=9` ↔ `close-post (7,322)`、`expand (7,322)+128 host=11 norm->8:84` ↔ `close (7,322)` 镜像平衡；贴底构型 `pinned=true` 语义保留；零 [DEBUG-526]、零崩溃、进程存活。

### 实证三：真机 V2（4096）回归
读取卡「读取 · .bashrc」标题/路径渲染正常（extractToolInput live 证明）；思考卡多轮 toggle `expand fii=0 fiso=0 +H=194 host=7 norm->7:194 pinned=true`——与修前签名逐字段一致；零崩溃。

### 诚实披露：V1/V2 唯一行为面变化
#526 降级保顶对 V1/V2 **流式期**展开思考卡同样生效（此前该场景=裸 AV+贴底跟随可能上推，现为保顶下展）——与用户「往下展开上方不动」的期望同向；完成态（V1/V2 日常 99% 场景）机件路径零变化。单测全量绿（含 V2 #485 OrderTest 锚定回归护栏）。若 V1/V2 流式期观感异常，[DEBUG-526] 探针可定位，再议是否按服务器分域。

## §6 #529 思考卡展开偶发高度跳变（2026-10-07 21:11-21:12 用户实测 + 21:2x 增补取证）

**脏样本（用户真手指产生，41 秒日志窗口内仅 2 例）**：
- E1（实例首展开集）`topY 1326→1520（+194=H）fiso 4460→4654`，无校正帧；
- E1 `topY 1838→2036（+198≈H/2）fiso 3871→4263（反射+392）→4461（集后 +198 校正帧）`，topY 在校正后钉住。
**干净对照**：全部 E3/E5/E7（同实例后续集）topY 恒定、fiso+H、item 账本分毫不差（4155↔+1344→5499↔4155 等）。
**结论级事实**：
1. 跳变=竞态类，圈死在「回收重组合后新实例的首个展开集」；同实例第二次起恒干净——与收起侧 2026-09-28 已修的回收竞态（close-anchor consumed=0 兜底）同族但不同位。
2. 全程零 steady-report（#430 配对未触发）——迟到增长不在本案。
3. 增补探针实证：settle 期间 reveal 盒实占高=finalHCache 抬底值（boxH=194 @ f=0.001, rep=0）——抬底使 item 在锚点捕获窗内已有 +194 槽位；宿主尺寸跨遍测量传播（subcompose 多 pass）与反射待定区消费的交错是竞态面。
4. 15 轮机器复现（慢节奏 6+快节奏 fling 即点 9）均未触发——脏节奏需真手指的滚动余尾+立即点击（或预热在途点击）。
**下一步（待用户再遇）**：PLACED 已含 boxH/host 字段，用户复现后 `adb logcat -d | grep -E "PLACED|expand-anchor"` 一条命令即可解码交错序列；修法方向=抬底不进锚点基（floor 与反射原子窗对齐）或锚点捕获排除非静止视口。

## 已完结卡片迁入（2026-10-07）

### **#525 DSH 上下文注入排序在提问下方** `dsh` `uiux`
  - journal 里 runtime-context(user/message kind!=user) 与 agent 指令(system/message)信封时刻 ≥ 提问;#524 把提问回拨到真实发送时刻后注入族整体落到提问下方
  - 修法:DshEventMapper 轮内注入台账+repack 校正事件(handler earliestCreated min 合并兜底)
  - → journal: docs/journal/2026-10-07-dsh-standard-bugs.md
  - 迁入依据：用户验收 OK（2026-10-07 晚）（backlog.sh migrate 2026-10-07）

### **#526 DSH 展开收起方向不稳定(有时向上推动)** `dsh` `uiux` `scroll`
  - standard agent 下思考卡展开有时向上推;怀疑降级路径(流式 turn)或锚归一化 host 键失配
  - 待真机复现+[DEBUG-466]探针定罪
  - 迁入依据：用户验收 OK（2026-10-07 晚）（backlog.sh migrate 2026-10-07）

### **#527 DSH 工具卡实参键 snake_case 失配(读取卡标题/路径全空)** `dsh` `uiux`
  - DSH wire 用 file_path/old_string/new_string;卡片读 filePath/path/oldString——read/write/edit 标题、打开按钮、内容全灭
  - 修法:extractToolInput 单点键规范化(V1/V2 行为不变:无 snake 键不受影响)
  - 迁入依据：用户验收 OK（2026-10-07 晚）（backlog.sh migrate 2026-10-07）

### **#528 DSH 压缩报错后会话崩溃(CardExpandReveal 布局坐标)** `dsh` `crash`
  - 压缩报错(summary not smaller)后布局期 onPlaced 对已脱离节点 positionInRoot 抛 IllegalStateException 致 FATAL
  - 修法:isAttached 守卫(CardExpandReveal.kt:1449/1453)
  - 迁入依据：用户验收 OK（2026-10-07 晚）（backlog.sh migrate 2026-10-07）

## §7 #512 真机验收矩阵（dsh-pw-demo@8181，app=dev 包真机 + 宿主 curl 驱动服务端）

| 场景 | 方法 | 结果 |
|---|---|---|
| 基线四端点 | curl：status 无凭据/带密铸/configured/根路径 | ✅ 401 / {ok,v:1} / {configured:true,stale:false} / 303→/login（根路径弹跳实测） |
| revoke 绝对语义 | 带票 POST /revoke → 发起者旧票立即 401 | ✅ {ok,v:1}；点击者本人同灭 |
| 形态一：活 WS 存活 | revoke 后 3s logcat 观测 | ✅ 零断线/零错误，进程存活（admit 只在升级时查=设计边界实证） |
| 形态三：冷启自动重铸 | revoke 后 force-stop+冷启（密码未变） | ✅ 2s entry OK，Debug channel activated，journal fold 已流出（回落链：旧票 401→带密铸新票） |
| rotate 服务端四态 | rotate devpass→pw512test 后三探 | ✅ 旧密 401 / 新密 200 / 旋转前旧票 401（全员下线） |
| rotate app 侧·旧密码拒绝 | 冷启携旧 devpass | ✅ logcat `DshConnRegistry: token exchange rejected: HTTP 401`；UI 缓存壳（会话列表缓存呈现，无崩溃），错误面截图 /tmp/v512_authfail.png |
| rotate app 侧·改密者存活 | 冷启携新 pw512test | ✅ 2s entry OK |
| 基线恢复 | rotate 回滚 pw512test→devpass + 冷启 | ✅ devpass 铸票 200；app 2s 恢复连接 |

**留待用户主观面**：①浏览器书签页/设置面板（PC 侧，实现期 49 断言已覆盖，建议眼验一次）；②app 内手改密码字段的 UX（本矩阵以 debug intent 为代理）；③新密码下真手指发一条消息。

## §8 #512 真机端到端（2026-10-07 22:05-22:40，手工 UI 路径 + curl 扮演另一操作者）

| 段 | 操作（全部 app 真实 UI） | 结果 |
|---|---|---|
| A 手工配置 | Home→添加服务器→DSH 类型表单（名称/URL 两字段；IME 开合态坐标漂移×3 踩坑后成功）→保存→连接 | ✅ 保存成功；连接命中**回落链第一级**（同 URL 共享缓存 Cookie，probe authenticated=true） |
| A′ 重复后端守卫 | 与已连 DSH-8181 同 URL 连接 | ✅ `HomeViewModel: shares backend…rejecting`（#34 守卫如实呈现红字「该服务器已连接」） |
| B WS 全链 | 新配置下进「你好」会话发消息 | ✅ 消息发出→流式回复+思考卡+工具卡渲染→journal 落盘（386KB） |
| C rotate 打断 | curl rotate devpass→e2epw2（旧密 401 实证）→ force-stop 正常冷启 | ✅ 回落链逐级打光有日志：`session mint 401(free)`→`token exchange rejected 401`→`auth recovery failed`→`SseConnManager: token required — waiting for token input`；UI 断线横幅+HTTP 401 错误行，无崩溃；`debug_profile` 纯导航腿系文档漂移（未实现），改用生产路径 ACTION_OPEN_SESSION 深链可达会话列表（横幅挂 SESSION_LIST_HEADER） |
| E 清理 | rotate 回滚 devpass（铸票 200 实证）+ 删除 DSH-PW-E2E 条目（datastore 0 残留）+ debug-entry 基线恢复 | ✅ 全清 |

**遗留观察（登记待查，不阻塞 #512）**：TokenNeeded 态下（rotate 后无存储密码），Home 卡片无「会话」入口（isConnected 门控）、卡上无齿轮（serverSettingsReadyIds 需 providers 成功）——设计路径=FGS 常驻通知点击（ACTION_OPEN_SESSION）进会话列表看「输入令牌」横幅；远端自动化三次尝试未在可视窗内捕到横幅（tokenNeeded 集合时机性清空嫌疑：SseConnectionManager:327 路径）。**用户手指验收点**：rotate 后从通知点进会话列表，横幅应出现且可输新密码。若复现不出=真 UX 缺口另立卡。

## §9 #512 演示干跑（2026-10-07 22:36-22:40）

**干跑链**：rotate devpass→drypw1 → app 冷启 → `token required — waiting for token input: DSH-8181` → ACTION_OPEN_SESSION 深链 → **横幅稳定出现**（y=188「此服务器需要访问令牌」+输入钮，E2E 时的缺席系状态时机非可达性缺陷）→ 对话框输 drypw1 提交 → **横幅不消失、无恢复**。

**根因**：`DshTokenEntryViewModel.submit` → `extractDshToken(raw)` + `DshConnectionRegistry.exchangeToken`（仅 legacy `GET /?token=…` 303+Set-Cookie 交换，DshConnectionRegistry.kt:333）。插件服务器密码在此路径无解——**#512 验收阻塞项**：spec §H「现有 token/密码手输 UI 兼作两者入口」的对话框侧未实现（回落链消费侧已实现）。

**附带**：干跑还撞见 monkey 启动落 LeakCanary/BACK 落系统设置两坑（均未触碰设置退出）。

**基线已恢复**：drypw1→devpass（铸票 200），app debug-entry 2s 重连。

## §10 #512 密码输入侧修复（2026-10-07 23:05-23:15）

**改动**：
- `DshConnectionRegistry.mintSessionWithPassword`（公开包装既有 #512 铸票；cookie 入注册表→awaitCookie 轮询自动续行）
- `DshTokenEntryViewModel.submit`：插件密码铸票**优先**（成功即存 ServerConfig.password——回落链持久凭据源；不 trim 精确字节）→ 失败回落 legacy 令牌交换（无插件服务器）→ 两败=Rejected
- 文案中性化 5 键（凭据/密码或令牌/密码优先说明/表单配对行压缩）×15 语言，i18n-check 917 键全过
- 单测 5 例（优先序/回落/双败/非令牌短路/同值免写）+ 全量套件绿

**真机重演（fixpw1 轮）**：rotate→冷启 TokenNeeded→深链横幅（新文案「此服务器需要访问凭据」）→对话框输 fixpw1→`mintSession` 执行→**横幅消失恢复**→force-stop 正常冷启**零令牌提示自动重连**（密码持久化实证）。
**坑位记录**：IME 开态对话框按钮位移（关态 1636→开态 1175）——自动化须开态实时坐标；StrictMode 主线程读响应体警告（legacy exchangeToken 同款模式，polish 项不阻塞）。
**基线已恢复**：devpass + app 存储同步。待用户「演示」口令。

## §11 #512 登录流程演示（2026-10-07 23:22-23:29，用户口令后执行）

| 拍 | 内容 | 结果 |
|---|---|---|
| 1 | 开场在线（DSH 会话列表，devpass） | ✅ |
| 2 | 服务端 rotate devpass→demo2026 | ✅ {ok} |
| 3 | force-stop+正常冷启→自动重连失败→`token required`→深链进会话列表 | ✅ 横幅「此服务器需要访问凭据」上屏 |
| 4 | 点横幅→对话框（新文案）→输 demo2026→提交 | ✅ |
| 5 | 恢复验证 | ✅ 横幅消失、连接恢复（用户全程真机观看） |
| 彩蛋 | 会话内发消息 | ⚠️ 跳过：导航两度弹回 Home + 应用渲染面黑屏挂起（窗口在/进程活）；核心五拍已完整，彩蛋系已验证路径（E2E B 段）非本次修复面 |
| 收尾 | demo2026→devpass 回滚 + debug-entry 基线 | ✅ 2s 恢复 |

**新登记嫌疑（未定罪）**：会话页在多次深链导航+IME 操作后弹回 Home ×2、最终渲染面黑屏（进程 20939 存活、mCurrentFocus 正常）——force-stop 后恢复。与今日改动无交集路径（纯导航/渲染），另立观察。

## §12 表单密码演示（2026-10-08 00:00，用户口令后执行）

五拍全绿：首页+表单（密码框+新文案上屏）→ 当面填 名称/URL/密码 → 保存 → 断开旧条目后连接 → **probe authenticated=true 直连零横幅**（日志实证）→ 会话列表进入 ✓。收尾：DSH-DEMO 删除（datastore 零残留）+ 基线 2s 恢复。
