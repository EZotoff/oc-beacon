# P3 观察卡可模拟性分析 — 2026-10-09（#486/#464/#454/#477）

> 问题：四张「等复现」观察卡能否通过 E2E / 集成测试系统性模拟，把「被动等复现」转成「主动狩猎」？
> 方法：测试基建实况盘点（human-sim-10h driver、mockllm、androidTest fakes、maestro、既有 journal 取证链）+ 逐卡触发链 vs 测试层能力匹配分析。
> 结论先行：**四卡全部可模拟**——#486/#454 加仪器层确定性用例，#464/#477/#454 加 10h soak 狩猎器（三件套可同批一次机时）。

## 0. 测试基建实况（本次盘点确认）

| 层 | 资产 | 能力边界 |
|---|---|---|
| 10h soak | `human-sim-10h/driver/driver.py` + `run.sh`/`review.sh` | 真机 e69a99d8 长跑：uiautomator dump 解析、jitter tap/swipe、keyevent 打字（禁 input text）、破坏性目标黑名单、屏幕态识别、foreign-top 逃逸、dialog 处理、宿主侧 logcat 流、actions.jsonl 全量操作台账、evidence/ 截图落盘 |
| mock LLM | `human-sim-10h/mockllm/server.js`（:4290，OpenAI 兼容） | 零成本完全可控流式源（12 类压力语料+7 种速率画像含 abort-mid）；现服务 v1-e2e:4299，真实 opencode 事件管线原样驱动 app |
| 仪器 Compose | `app/src/androidTest/.../chat/`（BaseChatTest + ChatScrollStabilityTest 等 8 文件 + Fake ChatRepository/Network/Domain + builders） | **repository/状态层**驱动（growText 改状态触发流式重组），非 SSE 线缆层；可确定性构造「流式增长+用户滚走」等场景（`userScrollsAway_streamingGrows_viewportStaysPut` 先例） |
| 单元 | ChatViewModelSendTest 等全套 delegate 测试 | 纯逻辑（发送门/parts 转换/协程）确定性 |
| 取证 | `pull-app-db.sh`（Room 直查）、logcat 探针族（DEBUG-only 永久保留）、vision/像素 | 判据仪器现成 |

## 1. #486 SSE retry ever=false 重建帧 — E2E ✅ 可模拟；仪器层 ✅ 更强（确定性）

**关键新事实**：09-30 调研（journal 2026-09-30-471-4）实验 A（服务端断连重启）/B（链路掐+服务端续写）**都没有跑「滚动离屏」第三要素**——两实验共同点 partId 稳定→组合位不销毁→ever 保持。三联场景（流式中断连+滚动离屏+重连）因 longcat 限流**当日未完成**——即理论盲区本体从未被真正试验过，watch 的「0 触发」是「0 试验」。

- **E2E 层**：断连制造两法在案（`adb kill-server`=链路死服务端活；`systemctl --user stop/start opencode-v2`=双死）；滚动离屏 = driver swipe 把目标 turn 滑出 StepGroup 窗口；重连后滚回观察。曾经的阻塞（内容源不可控）可用 mockllm 思路解决或换可控 provider；竞态窗口用循环 10-20 轮扫（探针判据：`path=render src=asyncTerminal stateType=Loading`、MDResize 负 d、RESETKEY）。
- **仪器层（推荐先行）**：Fake ChatRepository 状态序列可确定性构造盲区形状——灌 >2048 字符流式 part → 滚出视口触发 registry remove（或直接换 partId 模拟组合位销毁）→ 全量替换（backfill 语义）+ force-complete → 断言无 Loading 零高帧/无负向 MDResize。**测试先行可判死活**：仪器层都构造不出塌缩=另有防线、watch 维持有据；构造得出=防御缺口实证，直接走 09-30 方案 a（msgId 级 LRU 账本补 ever=true，~30 行+单测）。
- 成本：仪器用例约半天（含 Fake 扩展 backfill 事件形状）；E2E 脚本约半天+机时。

## 2. #464 暖态点击偶发失效 — 仪器层 ❌（系统层竞态不在 Compose 沙箱）；E2E soak ✅ 狩猎可自动化

vibrator 在=InputDispatcher 送达且 haptic 触发，但业务 onClick 未生效——嫌疑在框架/系统层（overlay 拦截、重组换节点、窗口焦点态），Robolectric/androidTest 触不到。唯一正确的层是 10h soak：

- **缺的是效果断言环**：driver 已整天随机点可交互元素，加「tap → dump 校验预期状态变化（屏幕态/新节点）→ 失配即抓现场」：`dumpsys input`（InputDispatcher 最近事件与各窗口消费/焦点态——直接看事件被谁吃了）、logcat 全量、a11y dump、`dumpsys meminfo`、uptime。
- 已知干扰：#477 dump 全盲会让 dump 断言假阳性 → 失败判定走 dump+截图双通道。
- 命中即定罪分叉（overlay vs 重组换节点 vs 业务门）；actions.jsonl 台账可回放命中时上下文（IME 开/关、长滚动后、切会话后分轴统计）。
- 成本：driver 扩展约 1 天 + 与 #477 同批 10h 机时。

## 3. #454 v1 IME 换行注入后 prompt 未发出 — 单元 ◐ 先排除纯逻辑面；E2E ✅ 直接循环扫

- **单元层（低成本先行）**：ChatViewModelSendTest 在——`sendMessage("Run\n\n")` 断言 sendPrompt 调用与 parts 形状。若 bug 在文本→parts 转换 / isSending 门 / 协程取消，红用例确定性复现；若在 IME 事件层时序，单测触不到（预期）。
- **E2E 层**：driver `type_ascii`（keyevent 打字）注入 `Run` + 两次 ENTER（换行入输入域）→ dump 定位发送键 tap → 断言 logcat prompt POST/prompt_async + Room 状态（pull-app-db.sh 直查 WAL 三件套）。循环 50-100 次扫时序面（ENTER 后立即 tap vs 延迟 tap 变节奏）。mock 链路零成本。
- 成本：单测 1-2h；E2E 循环并入 soak 批。

## 4. #477 长跑进程 a11y dump 全盲 — 仪器层 ❌（跨进程 a11y 桥无法 in-process 模拟）；E2E soak ✅ 完美匹配

4h+ 单一进程晚期新内容 dump 零文本节点（渲染正常已有 vision+像素双证）。10h soak 本身就是 4h+ 进程且持续创建新内容——**缺的只是盲 dump 检测器**：

- 周期性（如每 15min）对最新 turn dump，与 ground truth（mock 语料已知文本 / Room 直查）对比：dump 零文本节点而 Room 有内容 = 盲例命中 → 当场抓 logcat a11y 错误（AccessibilityNodeInfo/ViewRootImpl E 级）+ `dumpsys accessibility` + meminfo + uptime。
- 这正是卡面「再撞上即抓」的自动化版；命中一次即可分叉定罪（a11y 缓存退化 / 窗口 attach / 内存压力）。R 假设（内容创建时刻 vs 进程年龄）可同批验证：同一语料在进程早期与晚期各注入一次对比暴露性。
- 成本：检测器约半天 + 与 #464 同批 10h 机时。

## 5. 汇总：一次狩猎批的组成（建议）

| 组件 | 覆盖卡 | 载体 | 新增工作量 |
|---|---|---|---|
| 仪器确定性用例 | #486（重建帧）、#454（发送纯逻辑） | androidTest fakes 扩展 + 单测 | ~1 天 |
| soak 狩猎三件套 | #464（tap 断言+现场抓取）、#477（盲 dump 检测器）、#454（换行注入循环） | driver.py 扩展 | ~1.5 天 |
| 一次 10h 机时 | 三卡同批 | run.sh | 机时 |

全部复用现有基建，无新框架。#486 若仪器层构造出塌缩 → 顺势根修（LRU 账本方案在案）；构造不出 → watch 维持升级为「有测试护栏的 watch」。
