# pre500-cards-batch（2026-10-06）

> 状态：进行中
> 关联：（spec 路径，若有）·（issue 编号，若有）
> 来源：用户反馈 / grilling / E2E / 顺带发现

<!-- 过程中的取证/验证证据直接写本文件；backlog.md 只留 ≤3 行卡片。 -->

## 四卡实施与验证证据（2026-10-06）

批次范围：#489 / #494 / #95 完整实施 + #499 范围内实施（见 #499 注记）。
方法：盘点核实 → 实施 → compileDevDebugKotlin → 定向单测 → 全量单测。

### #489 FileViewer 高亮尾字符多染（4eb9b0b9）
- 修复：HighlightBuilder.kt `(h.location.end + 1).coerceAtMost(maxIndex)` → `h.location.end.coerceAtMost(maxIndex)`（PhraseLocation.end exclusive 语义，与 #488 批1 HighlightedCode.kt:281 同款）。
- 回归测试：HighlightBuilderTest 新增差分断言（span 端点 == min(loc.end, len)，修复前必为 end+1）+ 文本保留断言；10/10 绿。
- 注意：FileViewer 视觉面（末字符多染消失）需肉眼验收——仪器不可自动断言色块边界。

### #494 DSH 权限预设下拉夺焦点（61fdcbe7）
- 修复：PermissionPresetSelector.kt DropdownMenu 加 `properties = PopupProperties(focusable = false)`（#361 busy 气泡同款，ChatScreenBottomBar.kt:733 先例）。
- compileDevDebugKotlin 绿；键盘保持行为需真机/模拟器肉眼验收（IME 可见性时序）。

### #495 TODO 入口能力位门控（5af71b8c）
- 新增 ServerFeatures.TODOS（core.todos）。
- 三端信号源：V1 = OpenCodeServerAdapter.privateFeatures 静态补位（行为与今天一致，无闪烁）；V2 = 静态缺席 + ChatViewModel 端点探测成功经 combine 折入（beta 无路由隐藏 / dev 分支有路由显示——静态判不了，运行时探测是唯一准确信号，V2ApiClient 404 探测缓存既有）；DSH = DshApiClient.getSessionTodos 桩改抛 UnsupportedServerCapability（退出「V1 常量降级家族」：唯此方法喂能力探测，空表成功=假阳性=入口泄漏）。
- 死状态退役：public todoCapable StateFlow 删除（原全仓无消费）；ChatScreen:650 死收集删除；探测内部 _todoCapable 保留为折入信号源。
- 注册表：chatFabTodo 贡献挂 TODOS 位；ChatFabActionsModule 注释更新（「TODO/AGENT 两面通用」→ 仅 AGENT 两面通用）。
- 测试：ServerCapabilitiesDerivationTest +4（V1/V2/unknown/DSH 位断言 + withTodoCapability 折入三态 9/9 绿）；ServerActionRegistryTest +1（真实模块接线钉死 4/4 绿）；DshApiClientTest +1（todo 桩抛异常断言 133/133 绿）。
- 行为变化（预期内）：V2-beta/DSH FAB 菜单不再出现 TODO 入口；V1 不变。

### #499 旗标退役·范围内实施（b933d882）
- 退役：STREAMING_MD_PILOT / STABLE_REVEAL_PILOT 两旗标（三 flavor 全 true，含 stable 2026-09-30 放行）+ StreamingMarkdownPilot 对象 + rememberPilotStreamingMarkdownState 四处 gate 分支死路径（首跑/增量两 else 旁路删除、shard/held 两条件解包）+ MarkdownContent 旗标判式三处 + build.gradle 六行。
- **范围修正（重要）**：JankHoldGate 与 STREAM_DELTA_BUS **不随本批退役**——实测 build.gradle stable 两者均 false（B案未升 stable），JankHold 冻结链在 stable 仍是承重路径（B案开=旁路、stable=活体）。等 B案（SHARD/DELTA_BUS）升 stable 后再清。
- OCBEACON_STREAM_FLAGS_OFF 属性仅 build.gradle 自用（无脚本/CI 依赖），MD pilot 旗标移除不影响其 DELTA_BUS 用途。
- AGENTS.md SSE 铁律第 1 条措辞同步（试点开关 → 唯一路径）。
- 全量单测 3904/3904 绿（0 failures 0 errors）。

### 验证状态
- 编译/单测：全绿（compileDevDebugKotlin ×3、定向 3 组、全量 3904）。
- 真机 E2E：待跑（设备在线性待查）；#494/#495 属仪器可断言面（dump 验证入口消失/键盘状态），#489 属肉眼色块面。

## #495 DSH 面勘误（2026-10-06，用户质询触发）

**用户问：「DSH 为啥没有 TODO？理论上应该需要 TODO吧」——用户判断正确，我的 DSH 面决策错误。**

错误根源：实施时信任了审计 D1 与卡片摘要的「DSH getSessionTodos 恒空表」表述，把它当成服务器侧事实——实际那只是**客户端桩**的行为（DshApiClient.getSessionTodos = emptyList()），我未对服务器源码核实「DSH 是否有 todo 域」就下了「无此域」结论。

源码实证（live 0.2.0-rc.2 brew 树）：
- `dsh/node_modules/@deepseek-ai/dsh-tool-todo`（dsh package.json:90 依赖）存在；其文档注释明说「每次调用向 session 追加 todo/write 快照，replay last-write-wins，**UIs render from session events**」，注册 todos session projection（todosProjectionSchema/apply/view）。
- app 侧早已接通 live：DshEventMapper.kt:741 `"todo/write" -> mapTodoWrite` → SseEvent.TodoUpdated → EventDispatcher → sessionTodos → TodoSheet。**DSH 流式期 TODO 面板本来就工作**。
- 真实缺口=冷进 hydrate：服务器无 REST 端点（api-session-controller 零 todo 路由），DshHistoryFolder 不折 todo 事件——冷进会话 TodoSheet 空到下一次 todo/write。

修正（commit「fix: #495 勘误」）：
- DshServerAdapter.privateFeatures 静态声明 TODOS（入口恢复显示）；
- getSessionTodos 恢复空表降级（V1 常量降级家族归位，注释改勘误说明）；
- 派生网 DSH 断言翻正（assertTrue TODOS）+ DshApiClientTest 家族测试恢复含空表行（132/132 绿）；
- ServerFeature.TODOS 文档更新；
- 冷进补灌另立 **#521**（journal 折叠方向）。

V1（静态）/V2（探测折入）两面不受勘误影响。教训：能力位「服务器有没有某域」的结论必须锚服务器源码，客户端降级桩不是证据。

## 真机 E2E 四腿验证（2026-10-06 03:23–03:42）

设备：小米 houji（无线 adb，192.168.1.110，serial `adb-e69a99d8-yzT17Y._adb-tls-connect._tcp`；mDNS 自动连接）。APK=当日 assembleDevDebug（含 #495 勘误），`adb install -r` 覆盖装。入口全部走 debug intent 冷启（force-stop 后 am start，规避 #500 热启动失效面）；V1 经 debug-entry.sh（4199 自动拉起 opencode-v1.service），V2/DSH 手工同款 extras。每腿前置 force-stop beta 孪生包。

### #495 三服务器 FAB 菜单断言 — 全 PASS
- V1（4199，Lighthouse 会话）：菜单展开=TODO/智能体/Shell(+第4项半遮挡)——**TODO 在** ✓（视觉判读 /tmp/v1_fab1.png）
- V2（4096，打招呼问候会话）：菜单=智能体/Shell/排队队列——**TODO 无** ✓（/tmp/v2_fab.png；三条目与 V2 能力位逐项吻合：AGENT 通用/SHELL=TERMINAL/QUEUE=QUEUE）
- DSH（3080 live，Quicksort 会话，~/.dsh/token 交换 rejected 但存量 Cookie 有效）：菜单=TODO/智能体/目标/排队队列——**TODO 在** ✓（/tmp/dsh_fab.png；无 Shell=TERMINAL 缺席正确）
- 注：三腿菜单文字节点在 uiautomator dump 中全部假阴性（Compose 弹窗），截图+视觉判读为地面真相（既有教训再现）。

### #494 DSH 权限下拉键盘保持 — 全 PASS（关键断言 dumpsys 仪器级）
- 聚焦输入框 → `mInputShown=true`
- 点「完全访问」pill（下拉开：只读/工作区写入/完全访问 三档，视觉确认）→ **`mInputShown=true` 不变** ✓（修复前 focusable=true 夺窗焦点必收键盘）
- 同屏双证：下拉与软键盘共存（/tmp/dsh_perm.png 视觉确认）
- 选「工作区写入」→ pill 标签翻转生效（dump 唯一命中工作区写入）+ `mInputShown=true` 仍持 ✓；测毕还原「完全访问」（dump 3×完全访问/0×工作区写入）
- 坑：下拉选项对 dump 持续失明（3 retry），选项坐标经截图视觉定位（1200x2670 与设备像素 1:1）；一次误 tap 落菜单外触发 onDismissRequest（可作为 focusable=false 下拉仍正常开合的旁证）。

### #499 流式烟雾（V2 4096，SQL 注入指南 800 词轮）— PASS
- MDPilot 全程流动（113 行：append 34ch/10ch/4ch 批节奏 + gate release + held size 毕业交接）
- **RESETKEY=0**（零重建风暴）；TurnFin 正常落账；无 FATAL（唯一 AndroidRuntime 命中系他进程 VM exit 0 假阳性）
- MDResize 30 行仅 1 负向：`card=SiDT… h=256 d=-128` 与 TurnFin 同刻、**非流式卡**（流式卡 hW3… 全程 +66~68px 正单调至 1748）→ 定性为推理卡完结收缩（合法换装形态，非 #499 回归；非 #515 fire 形态——本轮零 shard fire）
- 终态渲染完好（视觉：自检表格/六章大纲/行内码样式正常，无空白块/乱码）

### #489 FileViewer 高亮（V2 MCP 排查会话，read .bashrc 工具卡）— PASS
- 触发链：prompt 令模型 read ~/.bashrc → 「读取 · .bashrc」工具卡 → 卡右侧⧉钮（onOpenFile→TOOL_SNAPSHOT）→ FileViewerOverlay 全屏打开（首行 `Read file /home/leonardo/.bashrc, lines 1-179`）
- **四色 shell 高亮实证**：注释灰/关键字(case·esac·shopt·return)橙/字符串与参数展开蓝/默认白（/tmp/v2_viewer2.png 视觉判读）——修复后 HighlightBuilder 渲染路径活体正常
- 边界说明：end+1 的 1 字符级差异仪器与视觉均不可分辨（该精度由单测差分断言钉死）；真机面=渲染路径无回归+多色着色在位。

### 环境痕迹（如实记录）
- V2 用户既有会话新增两条测试消息：打招呼问候（SQL 指南轮，正文含 6 节+2 Kotlin 代码块+1 表格）+ 微信MCP排查（read .bashrc 轮）；宿主 /home/leonardo 根目录 6 分钟窗内零新文件（模型未落盘）
- DSH live 会话权限档测毕已还原「完全访问」
