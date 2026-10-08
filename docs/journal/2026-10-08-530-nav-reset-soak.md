# 530 nav reset soak（2026-10-08）

> 状态：进行中
> 关联：（spec 路径，若有）·（issue 编号，若有）
> 来源：用户反馈 / grilling / E2E / 顺带发现

<!-- 过程中的取证/验证证据直接写本文件；backlog.md 只留 ≤3 行卡片。 -->

## ## §1 静态调研 + 深链投递矩阵定罪

- 调研报告：docs/research/2026-10-08-530-nav-reset-blackscreen-static.md（后台 agent 全链静态分析：MainActivity/NavGraph/通知 PendingIntent/连接状态流/MIUI 既往案例；假设排序 A1-A4/B1-B5 各带可证伪判据）。
- **矩阵实验（真机 houji，Chat 屏前台基线实例=1）**：
  | 形态 | 投递结果 | 实例 | 深链处理 | 感知 |
  |---|---|---|---|---|
  | 无 action+debug extras（am start） | START result=3 DELIVERED_TO_TOP | 1 | **静默丢**（onNewIntent 不回调） | 无变化=#500 复现 |
  | action OPEN_SESSION（无 flag） | 新实例 result=0 | **+1 叠栈** | 新实例 onCreate→起始 Home→深链转 SessionList | **弹离当前屏**（10-07「弹回」实测复现） |
  | +session_path | 同上 | **+1 叠栈** | 同上 | 同上（叠至 3 实例验证） |
  | action+SINGLE_TOP(-f 0x20000000) | DELIVERED_TO_TOP | 不变 | **onNewIntent 正常处理** | 正常深链导航 |
- BACK 逐层退栈（3→1 实例）观测：亮度 204-243 全程无黑屏——B3（叠栈 surface 撕毁）小样本未复现（低概率，soak 布防）。
- **定罪**：#530 线 A「弹回 Home」= A1 机制（standard 模式 action 深链新建实例）；#500「热启动失效」= 同根因分支（无 action 投递到顶不回调）；修法候选=MainActivity singleTop（报告 §5.4，兼治两卡，需评估返回栈语义）。通知 PendingIntent 自带 NEW_TASK|SINGLE_TOP——通知 tap 不触发叠栈，**shell/脚本深链是叠栈源**（10-07 演示=脚本 am start 序列 ✓ 吻合）。

## ## §2 driver-530 + soak 启动

- driver-530.py：复用 driver.py 基建+动态 mDNS serial 自愈（pick_serial 每次 adb 调用重解析——无线闪断耐受）；行为 8 形态加权（deep_action×3/deep_action_session×2/deep_singletop×2/deep_noaction×2/ime_session×3/bg_deep×2/unreachable×1/back_mix×2）+栈复位护栏（≥3 实例 BACK 清栈）；探针线程 5s 周期（亮度/实例数/mCurrentFocus→probe.log），黑屏判定=3 连续<10/255（~15s）→四件套取证（screencap/window/activity/gfxinfo）→force-stop 复位续跑。
- 6min 校准：29 周期、nav_reset×8（弹回机制每分钟~1.3 次稳定复现）、IME×7、栈复位×4、probe_err=0、零崩溃。
- 坑修：logcat 判定先 -c 再全量 -d（SSE 22 会话恢复日志会把 -t 200 窗口的 MainActivity 行冲掉——冷启假 FAIL）。
- 3h soak 已 setsid 脱离（20:45 启动，PID 1469821，台账 reports/530/）。

## ## §3 3h soak 终盘（20:45-23:45，正常结束）

- **统计**：895 周期/1026 动作——deep_noaction 113/deep_action 151/deep_action_session 91/deep_singletop 109/bg_deep 101/ime_session 146/unreachable 59/back_mix 125/stack_reset 131。
- **弹回（nav_reset）290 次**（台账 after>before 口径；driver 计数 251）——**~1.6 次/分钟稳定复现**，3 小时无一例外落在 action 深链形态（ui=session_list 盖顶），与矩阵定罪完全一致。
- **黑屏 0 次**：亮度探针全样本（>1800 采样）无一次 <10/255——B 线（黑屏挂起）在 3h 深链×IME×不可达重压下未复现，定性维持「低概率偶发」（10-07 单例+09-25 两例既往案），假设 B1/B3 未被推翻；黑屏自动取证机制已就位（driver-530 可复跑，复发即取四件套）。
- **零崩溃/零探针错误/零人工恢复**——无线 adb 全程稳定（动态 mDNS serial 自愈未触发需求）。
- **结论**：线 A 定罪闭环（机制+频率+3h 稳定复现）；线 B 留观（低概率，取证布防在库）。进入根修：MainActivity singleTop（兼治 #500）。

## ## §4 根修落地与复验（singleTop）

- **修复**：AndroidManifest MainActivity + android:launchMode="singleTop"（一文件一行）——同时消灭「action 深链新建实例叠栈」（#530 弹回）与「无 action 投递顶层不回调 onNewIntent」（#500 debug 通道热启动静默失效）两个分支。
- **矩阵复验（修复包）**：形态1 action 深链=实例 1 不变+Session deep-link onNewIntent 正常处理；形态2 无 action=**Debug channel requested 恢复**（#500 修复实证）；深链进 SessionList 后 BACK→回 Chat（单实例单栈，修复前叠实例层层的重复栈消失）。
- **30min 复验 soak（driver-530 同参数重跑）**：9 分钟点弹回事件 **0**（修复前同期基线 ~12 次）、黑屏 0、异常 0——终盘数据待跑完补记。

## ## §5 30min 复验终盘 + 状态流转

- **终盘**：163 周期（deep_action 31/action_session 23/singletop 20/noaction 20/bg_deep 11/ime 26/unreachable 10/back_mix 14）——**叠栈弹回 0 次**（唯一 nav_reset 为 0→1 进程不在时合法冷启路径；修复前同参数基线 ~1.4 次/分钟≈应 14+ 次）；stack_reset 0（护栏零触发=栈不再叠的旁证）；黑屏 0；异常 0；probe_err 0。
- **对照总表**：修复前 3h soak 弹回 290 次 vs 修复后 30min 0 次——线 A 根修关闭；#500 热启动失效同修（Debug channel requested onNewIntent 实证）。
- **流转**：#530 转 [~]（线 A 根修+双 soak 验证；线 B 黑屏 6.5h 重压未复现维持低概率观察，driver-530 自动取证布防在库复发即抓）；#500 同修实证补记转 [~]。
