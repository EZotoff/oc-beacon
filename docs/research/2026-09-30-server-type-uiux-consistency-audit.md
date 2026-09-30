# 服务器类型 UIUX 一致性审计与模型切换键盘核实（2026-09-30，用户报告触发）

> 触发：用户报告「会话界面点切换模型时，当前服务器类型键盘收起、返回再弹出；印象中其他服务器类型保持键盘不收」，并定规——**后续任何改造若涉及多服务器类型（V1/V2/DSH）通用逻辑/UIUX，必须先说明影响面并确认改哪几端**（已入 memory）。
>
> 审计方法：共享组件 serverType/能力位门控全量 grep（Explore 代理 102 次工具调用系统性扫描 ui/ 全目录）+ 模型选择路径源码通读 + 真机三端键盘对照实验（v1:4199 / v2:4096 / dsh:3080，同构建 dev 0.3.0 2026-09-30 21:08）。承接 2026-09-07 `server-face-unification-audit.md`（彼时两面对照，本次补齐 V1/V2/DSH 三端矩阵 + 键盘/焦点专项）。
>
> 符号沿用：OK=已一致；CAP=能力位合理差异；BAD=违反统一待修；ASK=待用户裁决。

## 一、模型切换键盘行为——核实结论：三端一致，无按类型分叉

### 1.1 代码层（负结果，双路确认）

模型切换唯一路径三端完全共享，无任何能力位/类型分支：

输入行模型 chip（`AgentModelVariantSelector.kt:139-167`）→ `onShowModelPicker`（`ChatScreen.kt:840`）→ `ModelPickerDialog`（`dialog/ModelPickerDialog.kt:115-134`：`ModalBottomSheet` + `skipPartiallyExpanded=true` + 固定 75% 屏高）→ 选中走 `ModelConfigDelegate.selectModel`（纯本地 StateFlow 写）。

`ModalBottomSheet` 是独立 dialog 窗口，打开必夺窗口焦点 → IME 必收起；关闭焦点回归输入框 → IME 回弹。**该机制与服务器类型无关。**

### 1.2 真机三端对照实验（2026-09-30 深夜，houji 192.168.110.239:36339）

协议：debug intent 冷启注入对应服务器 → 进首个会话 → 点输入框（键盘开）→ 点模型 chip（「模型变体」图标定位）→ BACK 关抽屉。探针 = `dumpsys input_method` 的 `mInputShown`；截图与原始记录在 `/tmp/kb/{v1,v2,dsh}/`（会话级临时件，状态已固化入本表）。

| 阶段 | v1 (4199) | v2 (4096) | dsh (3080) |
|---|---|---|---|
| 点输入框后 | true | true | true |
| 点模型 chip 后（抽屉开） | **false** | **false** | **false** |
| BACK 关抽屉后 | true（回弹） | true（回弹） | true（回弹） |

抽屉打开确认：三端均检出「选择模型」标题。**三端行为逐态一致：键盘收起→抽屉→回弹。**

### 1.3 结论与用户记忆的解释假说

用户印象中「其他端不收键盘」在当前构建**不存在**。可能来源（按可能性排序）：

1. **旧版本记忆**：2026-08-12 之前模型选择是输入行锚定的下拉形态（Popup 类容器不夺焦点、键盘保持），当期改为抽屉式（`ModelPickerDialog` 头注：「2026-08-12 用户要求：模型选择改为抽屉式」）——三端自此统一为收起回弹；
2. **与保键盘控件混淆**：同屏 busy 发送气泡菜单按 #361 裁决显式 `PopupProperties(focusable=false)` 保键盘（`ChatScreenBottomBar.kt:726-733`）；DSH 权限预设 pill 则夺焦点收键盘（见 §四 D5）——「切换」类轻量操作在键盘策略上本就有两套并存；
3. **对照时键盘未开**：他端点 chip 时输入框未聚焦，无从收起，感知为「没动」。

### 1.4 裁决（2026-09-30 用户拍板）

**基准 = 不收键盘**：模型抽屉改为打开时不收起 IME。模型选择器是共享组件——**改则三端同改**（影响面已向用户说明并确认）。实现方向：把抽屉从 `ModalBottomSheet`（独立窗口）换为主窗口内非模态呈现（候选：`Popup(focusable=false)` 容器承载 sheet 面 / 布局内 `BottomSheet`），需保留返回键关闭、点外关闭、scrim、75% 固定高、#379 手势隔离与 #405 非滚动区拖拽封锁语义。→ **#493**。

## 二、A 类——能力位门控（刻意：端点缺席即隐藏/禁用，形态不变；27 条）

`rowmodel/MessageRowModel.kt:26-36` 明文约定「只产生整项隐藏差异，绝不改变形态」。全表：

| # | 位置 | V1 | V2 | DSH | 记录 |
|---|---|---|---|---|---|
| A1 | `ChatTopBar.kt:223` Terminal 菜单项（TERMINAL） | 显示 | 显示 | 隐藏 | 注释在（2026-08-31 走查前置修复） |
| A2 | `ChatTopBar.kt:240` 转后台（SESSION_BACKGROUND） | 隐藏 | 显示 | 隐藏 | v1-v2-differences #85 |
| A3 | `ChatTopBar.kt:274-298` Share/Unshare（SESSION_SHARE） | 显示 | 隐藏 | 隐藏 | v1-v2-differences #78 |
| A4 | `ChatScreenBottomBar.kt:136,513` shell 模式三入口 | 可用 | 可用 | 全停用 | #276 |
| A5 | 撤销/重做族（SESSION_REVERT） | 可用 | 可用 | 隐藏 | #276 |
| A6 | 权限预设 pill（PERMISSION_SWITCH） | 无 | 无 | 有 | DSH 专属（键盘问题见 D5） |
| A7 | Plan chip（PLAN） | 无 | 无 | 有 | #310③ |
| A8 | FAB 五入口（GOAL/SHELL/QUEUE 挂能力位；TODO/AGENT 无条件） | — | — | — | 2026-09-07 §三-2；TODO 例外见 D1 |
| A9 | busy 气泡「消息排队」项（QUEUE） | 仅立即发送 | 两项 | 两项 | #356/#361 |
| A10 | QueueSheet 编辑动词（QUEUE_EDIT） | 无入口 | 禁用 | 可用 | QueueSheet.kt:46 |
| A11 | 单条消息删除（MESSAGE_DELETE） | 有 | 有 | 无 | US#35 |
| A12 | 👍/👎 反馈（FEEDBACK） | 无 | 无 | 有 | #366 |
| A13 | cost / TTFT 字段（COST / TURN_TIMING） | cost | cost | timing | US#31/32 |
| A14 | 删除（SESSION_DELETE）vs 归档（SESSION_ARCHIVE） | 删除 | 删除 | 归档 | #353 终型 |
| A15 | 详情「Agent 预设」只读行（AGENT_PRESET） | 无 | 无 | 有 | UI-B |
| A16 | 服务器设置默认权限/预设区块 | 无 | 无 | 有 | 注释在 |
| A17 | SERVER_SETTINGS 动态配置插槽 | 无 | 无 | 有 | #391 切片5/9 |
| A18 | 断连 token 横幅（AUTH_TOKEN） | 无 | 无 | 有 | #317 |
| A19 | Workspace tab/文件打开（VCS/FILE_SEARCH/FILE_READ） | 全有 | 全有 | 仅目录树 | #276 |
| A20 | Goal 域（GOALS） | 无 | 无 | 有 | #286 |
| A21 | 转录内任务时间线（JOBS_PUSH） | 无 | 无 | 有 | #399 |
| A22 | 自定义 provider 目录区块 | 无 | 无 | 有 | 注释在 |
| A23 | 导出扩展名（exportIsArchive） | .json | .json | .zip | #279 |
| A24 | config 可写性（configEditable） | 可写 | 只读 | 走特权插槽 | #85 |
| A25 | 压缩语义（compactionAsync 等） | 同步 | 异步 | 异步+模型无关 | #217/#276 |
| A26 | 空态 Agent 预设卡（AGENT_PRESET+blank） | 无 | 无 | 有 | UI-A |
| A27 | 快速新建对话框预设下拉行 | 无 | 无 | 有 | 2026-09-07 §三-3 |

A 类结论：注释齐、与 ui-conventions 铁律一致。**缺口**：`docs/v1-v2-differences.md` 只覆盖 V1↔V2 子集，DSH 列散在代码注释与本档——三端矩阵以本档 §二/§三为准（后续维护点）。

## 三、B/C 类——硬编码类型判断（4 条，均在配置语义内，合理）与同容器不同源（16 条，多数刻意）

B 类（UI 层直接引用 ServerType 仅 4 处）：`ServerCard.kt:95`（DSH 徽标）、`ServerDialog.kt:100-114`（配置期类型选择）、`HomeViewModel.kt:293`/`HomeScreen.kt:300`（透传）、`PendingSheets.kt:289`（已清偿注记）。**类型分支→能力位的迁移已基本清零。**

C 类要点（同容器、数据源按类型分叉，语义等价性可接受）：C1 模型选择器数据源（provider catalog vs modelCatalog 分组）；C2 agent pill 循环（`AgentRepositoryImpl.kt:18-21` 端口路由，两端同交互）；C3 @补全会话源（DSH 独有候选域）；C4 斜杠命令源（DSH agent-scoped，懒建会话前回退静态表）；C5 V1 steer 静默降级为普通发送（注释明示，用户不可见差异——TOP6 关注）；C6 队列刷新时机（推送/拉取）；C7 AgentSheet 树双轨；C8 任务面板容器互补（V2 ShellSheet vs DSH EventCard 时间线）；C9 上下文环分子分母来源；C10 轮次编号（DSH 绝对 vs V1/V2 窗口相对，分页平移）；C11 新建会话目录三态；C12 会话列表组织（平面 vs 工作区分组）；C13 错误反馈对位；C14 压缩时序源；C15 provider 认证流（V2 integration 重构 ⏳ #84）；C16 子会话发送载荷。全部有注释锚定，判定 CAP/OK。

## 四、D 类——疑似意外不一致（8 条；#494-#497 立卡，D6 已实验闭环，D7/D8 入档暂不立卡）

### D1 → **#495** TODO 入口空态泄漏（V2/DSH）
`ChatFabActionsModule.kt:26-27` TODO 入口无能力位；`ChatViewModel.kt:275-299` `todoCapable` 探测全仓无消费（死状态）；DSH `getSessionTodos` 恒空表=探测成功 → 入口常驻恒空态。与 `v1-v2-differences.md` #85「V2 下隐藏 Todo 入口」直接矛盾——2026-09-07 审计 E-1 修复漏网项。

### D2 → **#496** 空闲长按发送键三端语义分裂（含 DSH 死手势）
`SendStopButton.kt:226-234` 空闲长按=切 SHELL；`ChatScreenBottomBar.kt:510-518` 按 `shellCommandSupported` 拦截。V1/V2=切 shell；**DSH=无声 no-op**。2026-09-07 矩阵 A-4 挂 ASK 至今未裁决。

### D3+D4 → **#497** 子会话底栏三形态分裂 + DSH composer 异步挂载焦点扰动
`SubagentComposerGate.kt:15-23` + `ChatScreenBottomBar.kt:437,653-672`：子会话底栏 V1/V2=整块空白（连 DSH one-shot 的只读提示行都没有）；DSH=只读提示行/可输入。DSH `subagentMode` 异步解析期 composer 先缺席后出现（`ChatTextField.kt:94-101` 无 FocusRequester）→ 组合销毁重建、焦点/键盘重置——**与模型键盘问题同族的「焦点行为按类型不同」真实结构源**。

### D5 → **#494** DSH 权限预设下拉夺焦点收键盘
`PermissionPresetSelector.kt:90-104` 默认 `DropdownMenu`（focusable=true）夺焦点收 IME；同屏 busy 气泡按 #361 裁决 `focusable=false` 保键盘。同一「输入区上方轻量选择」两套键盘策略，且权限 pill 仅 DSH 渲染——用户键盘不一致感知的**真正近亲**。

### D6 模型切换键盘按类型分叉——**负结果，实验闭环（§一）**
picker 容器三端同构，无分支；真机三端逐态一致。不立卡。

### D7 `setProviderEnabled` 死路径（入档，未立卡）
`ServerSettingsViewModel.kt:264-297` UI 侧无调用者；V2 路径报「更新失败」而非「禁用+原因」。若未来恢复 provider 启停开关需按 ui-conventions 处理；届时再立卡。

### D8 快速新建对话框行序冻结（入档，未立卡）
`NewSessionQuickDialog.kt:106-111` 已修（DSH E2E 发现的点选落位错行）；三端各自验证点选稳定性属回归项，随 regression-guide 例行。

## 五、键盘/焦点/IME 按类型分支专项清单

| 位置 | 机制 | 按类型分叉 |
|---|---|---|
| `ChatScreen.kt:421,430-438` | 滚动收键盘 | 否（共享） |
| `ChatMessageList.kt:2223` | 消息区点按收键盘 | 否 |
| `ChatScreenBottomBar.kt:726-733` | busy 气泡 focusable=false 保键盘（#361） | 否 |
| `PermissionPresetSelector.kt:90` | DropdownMenu 夺焦点 | **是**（仅 DSH 渲染）→ D5/#494 |
| `ChatScreenBottomBar.kt:437`+SubagentComposerGate | composer 条件挂载（焦点宿主销毁/重建） | **是**（DSH 子会话时序翻转）→ D4/#497 |
| `terminal/ChatTerminalView.kt:106-288` | Termux 直连 requestFocus | 是（TERMINAL 仅 V1/V2，A1 能力位合理） |
| `RenameSessionDialog.kt:39-67` / `SearchTopBar.kt:43-64` / `AnnotationInputSheet.kt:61-112` | FocusRequester autoFocus | 否 |
| `ChatTextField.kt:94-123` | BasicTextField 无 FocusRequester | 否（组合身份依赖，D4 风险载体） |

## 六、裁决与立卡映射（2026-09-30）

| 裁决/发现 | 卡 |
|---|---|
| 模型抽屉基准=不收键盘（三端同改，共享组件） | #493 |
| D5 权限 pill 夺键盘 vs #361 裁决冲突 | #494 |
| D1 TODO 空态泄漏（V2/DSH） | #495 |
| D2 长按发送键语义分裂（DSH 死手势） | #496 |
| D3+D4 子会话底栏分裂+焦点扰动 | #497 |
| D6 模型键盘按端分叉 | 无卡（实验闭环，本档 §一） |
| D7/D8 | 无卡（入档，触发条件见 §四） |

**流程裁决（用户定规，已入 memory）**：今后改造涉及 v1/v2/DSH 共享逻辑/UIUX，评估必须显式列「影响面：三端各自是否变化」并请用户裁决范围；审计发现的既有不一致单独立卡，不与功能修复混做。
