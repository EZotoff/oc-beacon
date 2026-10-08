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
