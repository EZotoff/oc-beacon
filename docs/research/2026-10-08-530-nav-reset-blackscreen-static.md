# #530 会话页导航弹回 Home + 渲染面黑屏挂起——静态定罪线索（2026-10-08）

> 性质：纯代码级静态调研（零改动），为 backlog #530「会话页导航弹回 Home + 渲染面黑屏挂起」收敛根因假设与可证伪判据。所有结论标注【实锤】（代码/文档可直接验证）或【假设】（推理，附证伪实验）。
> 证据基准：demo 构建 = commit `e5612dc6`（2026-10-07 23:13，§10 fixpw1 轮）；行号以该时点工作树为准（当前 HEAD 与之无相关文件位移）。

---

## 1. 症状与时间线复述

来源：`docs/journal/2026-10-07-dsh-standard-bugs.md` §11（155-167 行）。

| 拍 | 操作 | 结果 |
|---|---|---|
| 1-2 | DSH 会话列表在线 → 服务端 rotate devpass→demo2026 | ✅ |
| 3 | force-stop+冷启 → 自动重连失败 → `token required` → **ACTION_OPEN_SESSION 深链**进会话列表 | ✅ 横幅上屏 |
| 4 | 点横幅 → 对话框 → **IME 输入** demo2026 → 提交 | ✅ |
| 5 | 恢复验证（横幅消失、连接恢复） | ✅ |
| 彩蛋 | 会话内发消息 | ⚠️ 中断 |

彩蛋阶段观察（§11:164-167）：会话页**两度自动弹回 Home（服务器列表屏）**，非用户操作；最终**渲染面全黑挂起**——进程活（PID 20939）、`mCurrentFocus` 正常指向 MainActivity，但画面全黑；force-stop 冷启恢复。序列特有物：多次深链导航 + IME 操作 + 服务端 rotate 密码 + 横幅恢复。§8/§9/§10 同链路的前序轮次已多次使用「force-stop + am start 深链」与对话框 IME 输入。

既往同类记录（重要）：
- **黑屏第二次出现**：`docs/journal/2026-09-25-437-streaming-md-stable-reveal.md:52`——「真机两次内容区黑屏（渲染层卡死，force-stop 恢复，低频，**出现在长操作序列后**）」，2026-09-25，与 #437 流式改动关联未证实。#530 是同款症状的第二次独立出现，两案共同点=长操作序列尾部、force-stop 可恢复。
- 弱相关：backlog #520/#513「冻结渲染形态」（流中 d=h 单步满高+零渐进，soak 判读）——流式路径的渲染冻结签名，与 #530（无流式）非同链路但同类输出（零绘制/冻结）。

---

## 2. 线 A：深链重入 → 弹回 Home

### 2.0 交付矩阵的事实底座【实锤】

**Manifest**（`app/src/main/AndroidManifest.xml:33-56`）：`MainActivity` **未声明 launchMode（=standard）**、exported、`configChanges="orientation|screenSize|screenLayout|keyboardHidden|smallestScreenSize|uiMode"`（:36，IME/旋转/uiMode 均不重建）、`windowSoftInputMode="adjustResize"`（:38）、`enableOnBackInvokedCallback="false"`（:21）、`largeHeap`（:22）。单 Activity 架构：MainActivity 恒为任务 root+top，**会话页/服务器列表页是同一 Activity 内的 Compose 导航目的地**（`NavGraph.kt:259-265`）。

**Android 官方文档**（developer.android.com/guide/components/activities/tasks-and-back-stack）：
- standard：「The system creates a new instance of the activity in the task it was started from and routes the intent to it」——**每个新 intent 恒建新实例**；CLEAR_TOP 遇 standard 也是销毁重建（"a new instance is always created for a new intent when the launch mode is standard"）。
- onNewIntent 仅在 singleTop/singleTask/singleInstancePerTask 或 intent 带 `FLAG_ACTIVITY_SINGLE_TOP` 时回调（"If the activity being started is the current activity, at the top of the back stack, then the existing instance receives a call to onNewIntent() instead of creating a new instance"）。
- 例外现实：从**非 Activity 上下文**（shell/桌面/通知）发起的启动走 ActivityStarter 任务复用分支，对已在顶的标准 Activity 可能仅把任务提前（`START_TASK_TO_FRONT`），**intent 被静默丢弃**（即 #500 实证的行为；官方指南不覆盖此分支，属 AOSP 实现行为）。

**投递三态**（总结）：外部 intent 打到运行中的 standard MainActivity 时，只有三种结局——
1. **丢弃**（任务提前、零回调）——#500 实证（backlog.md:95-99：无 `Debug channel requested` 日志、不切服务器）；
2. **onNewIntent**（仅当 intent 带 `FLAG_ACTIVITY_SINGLE_TOP`）——本仓通知链路全部如此设计（下述）；
3. **新建实例压栈**（standard 文档语义）——每实例各自独立的 NavGraph，起始目的地恒为 Home。

**本仓两条深链通道的 flags【实锤】**：
- **通知通道（生产路径）**：FGS 常驻通知 tap intent 与会话通知 PendingIntent 均带 `FLAG_ACTIVITY_NEW_TASK or FLAG_ACTIVITY_SINGLE_TOP`（`service/AppNotificationManager.kt:163-181` 常驻通知只带 `EXTRA_SERVER_ID`；`:651-672` 会话通知带 `sessionPath`+`sessionId`，使用点 `:295/:335/:377/:463`）→ **通知点击保证走 onNewIntent 到运行实例，不建新实例**。
- **am start 通道（调试/自动化路径）**：`am start -n pkg/.MainActivity [--es ...]` 不带 single-top flag → 落入三态中的 1 或 3，**由 ActivityStarter 分支决定，形态敏感**。

**处理点勘误核实【实锤】**：`MainActivity.onCreate` 调 `handleSessionIntent`/`handleShareIntent`/`handleDebugProfileIntent`（`MainActivity.kt:172/174/176`）；`onNewIntent` 同调三处理器（`:283/285/287`）。#500 卡片勘误所说 :184/:291/:298 为旧行号，现行为 :176/:287。两路径实现都在，问题不在「没接」而在「intent 根本没送达实例」（丢弃分支）或「送到了另一个新实例」（压栈分支）。

### 2.1 假设 A1（排序第一）：am start 深链新建 MainActivity 实例压栈，新实例起始目的地=Home 盖住当前会话页

机制链：
1. app 前台停在会话页（Chat），操作者执行一次 `am start`（debug_url 参数型或 ACTION_OPEN_SESSION 型）且未先 force-stop；
2. standard 启动模式按文档语义**新建 MainActivity 实例压在同一任务顶**（官方引证见 2.0）；旧实例（含 Chat 树）整体被压到下面、onStop；
3. 新实例 onCreate → `setContent` → NavHost **起始目的地渲染 Home（服务器列表屏）**（`NavGraph.kt:261` startDestination=Screen.Home）；
4. 深链/调试通道的导航是**异步迟到**的：
   - ACTION_OPEN_SESSION 会话深链：`_deepLinkFlow` collect 在 `LaunchedEffect` 中，且 `awaitServerReachable` 是**最长 10s 的轮询等待**（`NavGraph.kt:210` → `HomeViewModel.kt:417-439`：服务器清单水化等待 + 250ms 轮询 connectedServerIds）；
   - debug_url 型：`activateDebugProfile` 要先走 幂等落库→promote→sweep 断连→版本探测（网络）→FGS start，**全部完成后才 `_debugChannelNavFlow.tryEmit`**（`MainActivity.kt:437-533`，tryEmit 在 :533）；
5. 因此新实例在「Home 可见 → 数秒后才导航去 SessionList/Chat」之间有一个**肉眼可见的 Home 停留窗**。从用户/操作者视角=「会话页自动弹回 Home」；两次 am start=两度弹回。

与当晚记录的吻合性【假设，强】：§8/§9/§10/§11 全程大量使用深链 am start；§9「ACTION_OPEN_SESSION 深链→横幅稳定出现」证明**当晚前台 am start 深链是生效的**（未落入 #500 的丢弃分支）——生效即意味着走了新实例（onCreate 路径）或操作者每次都先 force-stop（冷启单实例）。彩蛋阶段若无 force-stop 的 am start，即触发本机制。

「弹回 Home」在代码里的第二来源核对【实锤】：全仓 `navigate(Home)` **仅一处**——`NavGraph.kt:213-216`（深链不可达分支，见 A2）；`popUpTo` 仅三处（`NavGraph.kt:214/231/439`）；debug 通道导航 `NavGraph.kt:186` 只 `launchSingleTop` 不弹栈（压栈不弹栈，从 Chat 上方盖 SessionList，非 Home）。**除此之外没有任何应用内代码能把导航栈重置回 Home**——这本身就是 A1 的排他性旁证：Home 要么来自新实例起始目的地，要么来自 A2 的唯一弹栈点，要么来自连续 BACK（A3）。

可证伪判据：
- logcat 证据签名：每次弹回时刻附近出现 `ActivityTaskManager: START u0 ...MainActivity`（新 start 请求）+ `MainActivity: Session deep-link`（`MainActivity.kt:297`）或 `Debug channel requested`（`:420`）**从 onCreate 路径打出**（新实例必打）+ 同一时刻窗 `NavGraph: Debug channel → SessionList`（`NavGraph.kt:185`）或 Deep-link 日志迟到数秒（等待窗特征）。
- 实验判据：前台 Chat 屏执行 `adb shell am start -n ... -a dev.leonardo.ocbeacon.OPEN_SESSION --es server_id ... --es session_path ...`（不带 force-stop），随后 `adb shell dumpsys activity activities | grep -c "MainActivity"`——**实例计数 ≥2 即实锤 A1**；屏幕应先见 Home。
- 反证：若弹回时刻 logcat 无任何 `START u0 ...MainActivity` 行且实例计数恒为 1 → 排除 A1。

### 2.2 假设 A2（排序第二）：会话深链 + 服务器不可达窗口 → 唯一的 navigate(Home)+popUpTo 弹栈分支

机制链（全部代码实锤，触发时机为假设）：
1. **会话**深链（带 sessionId）走 `NavGraph.kt:206-238`：先 `awaitServerReachable(serverId)`（:210）；
2. 服务器不在 connectedServerIds 时该方法触发 connectToServer 并**最长等 10s**（`HomeViewModel.kt:417-439`）；终态失败（connectionErrors 命中且 connecting 已撤，或超时）→ 返回 false；
3. false → `navController.navigate(HomeNav.route) { popUpTo(HomeNav.route) { inclusive=false }; launchSingleTop=true }`（`NavGraph.kt:212-218`）——**整个栈弹到只剩 Home，当前 Chat 被弹出**。日志签名：`NavGraph: Deep-link reconnect failed → server selection page`（:212）。
4. **不可达窗口由当晚序列自己制造**：
   - rotate 密码后、横幅恢复前：DSH 探测 AUTH_REQUIRED → `updateServerConnected(false)` + `markTokenNeeded(true)` + 挂起 `awaitCookie`（`service/SseConnectionManager.kt:528-537`）——connectedServerIds 长假阴性；
   - **debug intent 自己会主动断连**：`activateDebugProfile` 在连接前补发 `ACTION_DISCONNECT`（sweep 竞态修复，`MainActivity.kt:502-510`），并对被降级后端补发断连（:521-531）——**每次 debug 激活都会让当前服务器的 connectedServerIds 瞬时掉 false**（`HomeViewModel` 直接镜像服务侧状态流，`HomeViewModel.kt:158-177`）；
   - 重连期（横幅恢复后的 re-probe）也存在掉线间隙。
5. 触发前提：不可达窗口内有一个**带 sessionId 的深链 intent 送达运行实例**——会话通知点击（SINGLE_TOP→onNewIntent）或带 single-top flag 的 am start。注意：**无 sessionId 的深链（FGS 常驻通知 tap）不经过可达性检查**，直达 SessionList（`NavGraph.kt:239-243`）——所以仅常驻通知不会触发本分支。

可证伪判据：
- logcat 签名（决定性）：弹回时刻出现 `NavGraph: Deep-link reconnect failed → server selection page`（`NavGraph.kt:212` 字面量）→ A2 实锤；整晚无此行 → A2 出局。
- 实验判据：Chat 屏 + 服务端 rotate（或断 adb reverse 制造不可达）+ 触发一条带 sessionId 的深链（发消息等 turn 通知后点会话通知，或 `am start --activity-single-top` + 三 extras）→ 10s 内应落在 Home 且 Chat 出栈。
- 修复面判据（若 A2 定罪）：该分支改为仅当当前不在 Chat 时才 popUpTo，即可消灭这一类弹回。

### 2.3 假设 A3（排序第三）：自动化合成键盘事件触发伪 BACK，Chat→SessionList→Home 连弹两层

机制链：
- ChatScreen 的返回= `popBackStack()`（`NavGraph.kt:428-430`），SessionList 的返回= popBackStack 弹到 Home（`dev-nav-goto.sh` 头注「列表页 back 会退回服务器页」）；Chat 内 BackHandler 仅拦截 busy 菜单/FAB 菜单/终端模式（`ChatScreenBottomBar.kt:631`、`ChatFabMenu.kt:380`、`ChatTerminalView.kt:125`）——普通态下**两次 BACK=从 Chat 直达 Home**；
- 本仓已实证的伪影类：`docs/device-testing.md:138`——「禁 `input text`（**合成键盘事件触发预测性 back 伪影**）」，且 :145 的 tap 失活二分法本身就以 `keyevent 4`（BACK）为标准探针；
- 彩蛋=「会话内发消息」，自动化必然对 composer 做 IME/keyevent 操作（§10 已记录 IME 开态坐标位移坑）——若期间 IME 注入序列派生了 BACK，即发生「非用户操作」的双层弹栈。两度弹回=两次伪 BACK（每次一层或成对）。

可证伪判据：
- logcat：弹回时刻无 `START u0`（排除 A1）、无 `Deep-link reconnect failed`（排除 A2），且 NavController 目的地从 chat→sessions→home 逐层变化（可开 navigation debug 日志 `androidx.navigation` VERBOSE 或在 onNavigateBack 加打点后复跑）；
- 时序核对：调取当晚操作者 shell 历史（若有 /tmp 或 journal 附档），看弹回时刻是否有 keyevent 4 / input 调用落在同秒；
- 弱点：若操作者全程未发 keyevent，则 A3 出局（依赖当晚执行记录核查）。

### 2.4 假设 A4（低概率，登记备查）：服务器状态流驱动的其他导航副作用

已排查为**无**【实锤】：ChatScreen/SessionList 无 connectedServerIds/tokenNeeded 驱动的导航回调（ChatScreen.kt 全文无 isConnected/连接态导航联动；`SessionLifecycleDelegate.kt:103-128` 加载失败只记日志不退栈）；服务侧（OpenCodeConnectionService/SseConnectionManager）无法触达导航，只能发通知。debug 激活对**其他**服务器的断连（`MainActivity.kt:521-531`）不改变导航栈。结论：**服务器状态变化必须经由「深链事件」这座桥（A2）才能弹栈**。

---

## 3. 线 B：渲染面全黑挂起（窗口在/进程活/零绘制）

### 3.1 事实底座

- 观测签名：进程活（PID 20939）、`mCurrentFocus` 正常指向 MainActivity、画面全黑、不随时间恢复、force-stop 冷启恢复。
- 主题【实锤】：`themes.xml:3`——`Theme.OpenCode` = `android:Theme.Material.Light.NoActionBar`，**无 windowBackground 覆写、无 SplashScreen**。推论①：一个「从未画出首帧」的新窗口在浅色主题下应呈**白/浅色**而非黑——黑屏更符合「画了深色背景没画内容」或「surface 层失效」（推理）；推论②：若用户开 dark/AMOLED 主题（`MainActivity.kt:217-230` amoledDark→纯黑背景），则「只剩背景的一帧」=纯黑。
- 版本矩阵【实锤】：Compose BOM 2026.08.00 + **core 1.11.2 force**（`app/build.gradle.kts:203/315-318`）、activity-compose 1.13.0（:200）、navigation-compose 2.10.0（:226）、OkHttp engine（AGENTS 铁律）。
- **本机同矩阵零绘制先例【实锤】**：`docs/journal/2026-10-08-p2-assorted-batch2.md:22`——#493 轮次中 `Popup(focusable=false)` 子窗口在 houji/MIUI 上「**窗口在场（dumpsys frame 1200x1566）+ 组合三打点全出（composed/call site/content）但表面零绘制**——0 像素、scrim 0 像素、a11y 树无节点；疑与全屏/大面积 surface 或版本矩阵相关」。虽然该案时点（10-08 18:04，commit 9f004132）在 demo 构建（10-07 23:13）之后、不共享代码路径，但它证明**本机+本 Compose 矩阵存在「窗口在场、组合活着、表面零输出」的系统性失败类**。
- **同款黑屏既往案【实锤】**：2026-09-25 两次内容区黑屏（journal 见 §1）——长操作序列后、force-stop 恢复、低频。与 #530 构成「同症状两次独立出现」。

### 3.2 假设 B1（排序第一）：主窗口零绘制类（#493 Popup 零绘制的同族、主窗口变体）

机制：窗口对象存活（焦点正常）、Compose 组合与主线程可能都活着，但 **RenderThread→SurfaceFlinger 的帧输出归零**。#493 先例证明该失败类存在于本机矩阵；若命中主窗口（或 A1 新实例的首帧从未产出），表现即「全黑、进程活、焦点正常、不恢复」。

可证伪判据（决定性的是前两条）：
- `adb shell dumpsys gfxinfo <pkg>` 两次采样：**帧计数零增量** → 主线程没在产帧（偏向 B2）；帧计数在涨但屏幕黑 → 帧没到屏（偏向 B1/surface 层）；
- `adb shell uiautomator dump`：**a11y 树有节点但截图全黑** = #493 同签名（组合活、零绘制）实锤；a11y 树也无 = 更深层的窗口/进程冻结；
- `adb shell dumpsys window windows`：目标窗口的 surface/isReadyForUiDump 状态；`dumpsys SurfaceFlinger --latency <layer>` 零提交。
- 复现实验见 §5.3 soak。

### 3.3 假设 B2（排序第二）：主线程忙/帧产冻结，末帧停在 crossfade 半程（深色背景下=黑）

机制链：
- 全部导航转场是 fadeIn/fadeOut（`NavGraph.kt:262-265`，tween MEDIUM）。**fadeOut 半程的一帧=旧内容 alpha≈0+新内容未入**，只剩 Surface 背景色；深色/AMOLED 主题下背景≈纯黑。若帧产出恰在此刻冻结且不再恢复，屏幕「永久黑」但窗口/焦点一切正常；
- 冻结源候选（均为假设，需 jdb 取证）：多 MainActivity 实例叠栈（A1 副产品——每个实例各自 NavGraph+ChatScreen+HomeViewModel 全家）叠加重连风暴重组、IME adjustResize 全窗重排（彩蛋正处 IME 序列后）、`largeHeap` 大堆 GC 停顿（只能解释暂时卡，解释不了永久挂——列为弱因子）；
- 主线程阻塞路径排查【实锤·排除项】：demo 路径上的网络均为 Ktor suspend（`activateDebugProfile→testConnection→checkHealth`，`ServerRepositoryImpl.kt:76-78`→`ServerDataStore.kt:141-196`，无 runBlocking）；仓内 runBlocking 均在 IO/销毁路径（`AppLogger.kt:138`、`ServerTerminalWorkspace.kt:423` 等），ChatViewModel 构造期已无主线程 runBlocking（`ChatViewModel.kt:146-147` 注释）。**静态排查未发现该序列上的主线程同步 IO/锁**——若 jdb `where all` 显示主线程阻塞，阻塞点大概率是快照系统/重组风暴而非显式 IO。

可证伪判据：
- 挂起时按 `docs/device-testing.md:177` 配方取 `jdb where all`：主线程栈顶在 Choreographer/RenderThread 提交、snapshot 写、或 recomposer → 实锤；全线程 sleeping 且无锁 → 转向 B1/B3；
- `/data/anr/` 无 trace 且用户触摸无 ANR 弹窗 → 说明**没有输入事件送达或 ANR 计时未触发**（旁证主线程可能根本空闲=非忙循环，削弱本假设）。

### 3.4 假设 B3：多实例叠栈 + 被覆盖实例 surface 撕毁后未重绘

机制：A1 每次压一个新实例；被覆盖旧实例在 MIUI 上 surface 可能被回收；BACK/finish 回到旧实例时恢复失败（或恢复极慢——386KB journal 的 ChatScreen 冷重组）→ 黑屏。与 A1 是共生假设：**叠栈既是弹回 Home 的机制（A1），又为黑屏准备了多窗口/surface 撕毁的土壤**。

可证伪判据：挂起时 `dumpsys activity activities` 中 `MainActivity` 实例数 ≥2 + `dumpsys window` 显示焦点实例与黑 surface 实例不一致；或实验复现叠栈后 BACK（§5.1）。

### 3.5 假设 B4（低概率）：全屏遮罩/Dialog 残留假黑

排查【实锤】：demo 路径上的弹层有 DshTokenDialog（成功自动关：`DshTokenBannerExtension.kt:50-57` Exchanging→Idle 自动 dismiss）、ConfirmDialog、AgentPreset 系列——均为窗口内 Dialog，**scrim 半透明不会全黑**，且 Dialog 获焦时 `mCurrentFocus` 应指向 DialogWindow 而观测值仍指 MainActivity → **观测本身即弱反证**。PerfHudOverlay 需 `debug_perf` extra（`MainActivity.kt:186`）当晚未开。无 SplashScreen/FileViewer 路径。判定：基本排除，除非后续实验推翻焦点观测。

### 3.6 假设 B5：外部已知 bug（IME/adjustResize × Compose × MIUI）

外部证据弱：公开检索未见「窗口在/进程活/adjustResize/Compose 黑屏」的精确匹配公开 issue；最接近的公开讨论为 Kotlin Slack「Compose app opening/closing keyboard visual glitches」（slack-chats.kotlinlang.org，2023-10）与 issuetracker.google.com/issues/239702594（black screen 渲染类，非 Compose 特定）。**列为背景可能，不作为主假设**；若 §5.3 soak 中「无叠栈、无深链、纯 IME 开合」也复现黑屏，则升格并去 issue tracker 定向检索（ComposeView black screen IME animation）。

### 3.7 与「横幅恢复/重连风暴」的关系（线 B 第 2 点排查结论）

重连链无 UI 异态路径【实锤】：AUTH_REQUIRED→recoverAuth→awaitCookie 挂起等 cookie（`SseConnectionManager.kt:528-537`），无自旋；横幅显隐由 `markTokenNeeded` 布尔驱动（:531/537/567），UI 侧只是 banner+dialog（`DshTokenBannerExtension.kt:59-69`），无导航联动。重连风暴本身不产生黑屏/弹栈——它的作用是**为 A2 制造不可达窗口**（2.2 第 4 点）。

---

## 4. 交叉关联

### 4.1 #500 与 #530 是否同根因？

**同根因家族、不同分支**【推理，两分支均为实锤记录】：
- #500 = 投递三态之①「丢弃」：前台 am start（无 action 的 extras 型）→ 任务提前、零回调、静默失效（backlog.md:95-99）。
- #530 弹回 = 投递三态之③「新建实例压栈」：生效型 am start 深链 → 新实例起始 Home（A1）。
- 两者共用同一结构性根因：**MainActivity launchMode=standard + 单 Activity 架构下，外部 intent 的投递分支不受控**（三态由 ActivityStarter 的 intent 形态/flags 分派决定，同一台机器两种形态分别落入①③，与 #500 实证、§9 实证自洽）。矛盾点消解：#500 说「不生效」、§9 说「生效」，不冲突——**不同 intent 形态走了不同分支**；「为何未走新实例 onCreate」(#500 遗留问) 的答案即①与③的分派条件，需 §5.1 实验定罪。
- 统一修法候选（修 A1+A2 通道顺带治 #500）：MainActivity 改 `launchMode="singleTop"`——#500 卡片已列此候选；singleTop 下③永久消失、②成为唯一生效路径（onNewIntent 已接好），A1 弹回随之消灭。A2 分支独立于 launchMode，需另行评估（见 2.2 修复面）。

### 4.2 与既往 MIUI/Compose 案例的关系

- **#493 Popup 零绘制**（journal 2026-10-08-p2-assorted-batch2.md:22）：同机同 Compose 矩阵「窗口在场+组合活+表面零输出」——B1 的直接先例（不同窗口类型，同一失败类）。
- **2026-09-25 内容区黑屏 ×2**（journal 2026-09-25-437...md:52）：同症状第二案，「长操作序列后」共同点成立。
- **MIUI 已登记特性**（docs/device-testing.md）：tap 注入间歇丢弃（:145）、合成键盘事件预测性 back 伪影（:138，A3 的类先例）、后台弹出界面 DeviceGuard 拦截（:176，与本案前台场景无交集）、Compose 弹层 a11y 盲区（:139，影响取证手段而非成因）。「greeze 冻结」一词**仓内文档未检索到**（journal/research/ 全文无该词），不能作为仓内证据引用；最接近的已登记物是上述四条。
- 挂死取证现成配方：device-testing.md:177（debuggable→jdwp→jdb `suspend`+`where all`）——§5 的探针直接复用。

---

## 5. 复现实验设计建议

### 5.1 实验一（最优先）：深链投递矩阵定罪——一次实验同时裁决 #500 分支条件与 A1

前置：真机 houji（e69a99d8），dev debug 包，DSH 靶机（复用 #512 插件服务器 dsh-pw-demo），入口一律 `./scripts/debug-entry.sh` 恢复基线。

序列（app 前台停在 Chat 屏后执行，每步之间回到 Chat）：
1. `am start -n pkg/.MainActivity --es debug_url ...`（无 action，#500 原样复刻）→ 观察；
2. `am start -n pkg/.MainActivity -a dev.leonardo.ocbeacon.OPEN_SESSION --es server_id <id>`（带 action 无 session）；
3. `am start -n ... -a ... --es server_id <id> --es session_path <p> --es session_id <sid>`（带 action 带 session）；
4. 同 3 + `-f 0x20000000`（SINGLE_TOP）；
5. FGS 常驻通知 tap；6. 会话通知 tap（先发一条消息等 turn 通知）。

观测点（每步）：
- `am start -W` 输出（Status/Activity/TotalTime）；
- logcat：`ActivityTaskManager: START u0`（含结果码）、`MainActivity: Session deep-link`/`Debug channel requested`（出现=送达，配合 onCreate/onNewIntent 区分——可临时在两处理器加一行路径打点或看紧随的 `NavGraph` 日志时刻）；
- `adb shell dumpsys activity activities | grep -A40 "Task{"`——**MainActivity 实例计数**（≥2 即 A1 实锤）；
- 屏幕录屏/连拍：Home 闪现时长（A1 等待窗）。

预期判读：步 1 丢弃（#500 复现）；步 2/3 若新建实例→A1 定罪且拿到 #500/#530 的分支条件（action 有无）；步 4/5 走 onNewIntent（对照）。

### 5.2 实验二：A2 唯一弹栈分支的定向触发

序列：Chat 屏在线 → 服务端 rotate（或撤 adb reverse）制造不可达 → 10s 内触发带 sessionId 的深链（步 5.1-6 通知 tap 或步 4 命令）→ 观察。
判读：logcat 出现 `NavGraph: Deep-link reconnect failed → server selection page` + 屏落 Home 且 Chat 出栈 = A2 实锤；该日志整晚（含 10-07）缺失 = A2 对 #530 出局（分支存在但当晚未走）。

### 5.3 实验三（soak）：当晚序列重演 + 黑屏自动取证

节律（循环 ≥2h，driver 挂 run.sh 风格脚本）：
每轮 = [随机取一：debug-entry 冷启 / 前台 am start 深链（5.1 各形态，含不 force-stop 的压栈形态）] → [横幅密码输入（type.sh keyevents）] → [rotate 每 N 轮一次] → [进 Chat 发消息] → [BACK/导航混合]。

探针（每 5s 采样）：
- `dumpsys window | grep mCurrentFocus`；
- `screencap` 缩样均值亮度（<10/255 持续 >10s = 黑屏判定）；
- `dumpsys gfxinfo <pkg>` 帧计数增量；
- `dumpsys activity activities` MainActivity 实例数；
- logcat 常开：`ActivityTaskManager`、`MainActivity`、`NavGraph`、`SseConnManager`、`MIUILOG`。

黑屏自动处置（自动重试逻辑）：判定成立 → 先取证后恢复——`adb jdwp`/`jdb where all`（device-testing.md:177，注意会冻结进程，取证毕 resume）→ `bugreport` 存档 → `am force-stop` + `debug-entry.sh` 复位 → 继续循环并记录轮次编号。取证档案按轮次归档到 journal，供 B1（帧涨/黑=a11y 树在）vs B2（jdb 主线程栈）vs B3（实例数≥2）三分裁决。

### 5.4 低成本代码防线（若实验定罪后实施，另立卡走 backlog）

- `MainActivity` 改 `singleTop`（消灭分支③，兼治 #500）——改动前按 #500 卡评估返回栈语义；
- A2 分支护栏：`NavGraph.kt:212-218` 在当前位于 Chat 目的地时改为不 popUpTo（或改为提示条）；
- `NavGraph`/`MainActivity` 送达路径打点（onCreate/onNewIntent 各一行），为后续 soak 提供直接证据。

---

## 附：关键证据坐标索引

| 证据 | 坐标 |
|---|---|
| launchMode 未声明（standard）/adjustResize/configChanges | app/src/main/AndroidManifest.xml:33-38 |
| 深链/调试 intent 双路径处理 | MainActivity.kt:172-176（onCreate）、:283-287（onNewIntent） |
| activateDebugProfile 主动断连+sweep | MainActivity.kt:437-540（断连 :502-510、:521-531；tryEmit :533） |
| replay=1 双 SharedFlow | MainActivity.kt:100、:107 |
| debug 通道导航（无 popUpTo） | NavGraph.kt:181-188 |
| 深链不可达→唯一 navigate(Home)+popUpTo | NavGraph.kt:206-218 |
| 无 sessionId 深链直达 SessionList（无可达检查） | NavGraph.kt:239-243 |
| NavHost startDestination=Home+fade 转场 | NavGraph.kt:261-265 |
| 通知 PendingIntent NEW_TASK\|SINGLE_TOP | AppNotificationManager.kt:163-181、:651-672 |
| awaitServerReachable 10s 轮询 | HomeViewModel.kt:417-439（状态镜像 :158-177） |
| DSH AUTH_REQUIRED→断连+TokenNeeded 挂起 | SseConnectionManager.kt:528-537 |
| checkHealth 纯 suspend（非阻塞） | ServerRepositoryImpl.kt:76-78 → ServerDataStore.kt:141-196 |
| ChatScreen 无连接态导航联动/加载失败不退栈 | SessionLifecycleDelegate.kt:103-128 |
| 主题（无 splash/windowBackground） | app/src/main/res/values/themes.xml:3 |
| 版本矩阵（BOM/core 1.11.2 force） | app/build.gradle.kts:200/203/226/315-318 |
| #493 Popup 零绘制先例 | docs/journal/2026-10-08-p2-assorted-batch2.md:22、:72 |
| 黑屏既往案（09-25 ×2） | docs/journal/2026-09-25-437-streaming-md-stable-reveal.md:52 |
| 伪 BACK/tap 丢弃/挂死取证配方 | docs/device-testing.md:138、:145、:177 |
| 官方 launchMode/onNewIntent 语义 | https://developer.android.com/guide/components/activities/tasks-and-back-stack |
