# 探针开销审计 — 2026-10-09（#473 裁决依据）

> 问题（用户 2026-10-09 提）：探针开销高不高？是否太耗资源/空间/性能？有没有后手（压缩日志、设置里给用户关闭选项等）？
> 方法：代码层（AppLogger 存储模型 / 门控形态抽查 / R8 规则）+ 实证层（10h soak 宿主侧 logcat 样本 cap_005.log：2026-10-07 01:20:40→01:40:23 共 20 分钟 72165 行）。

## 1. 结论一览

| 面 | 画像 | 判定 |
|---|---|---|
| release（stable/beta 用户） | 探针族发射点全部 `if (BuildConfig.DEBUG)` 门控（抽查 MDPilot/GapDiag/CardExpandReveal 形态一致），release 零执行零输出 | **零成本** |
| dev 构建性能 | soak 实测探针 ~11.7 行/s，占 logcat 总量 19.4%；远低于渲染路径量级；10h soak 201 轮零崩溃+高度判据全绿为性能无害实证 | **可接受** |
| 设备侧空间（用户） | logcat 走系统环形缓冲不落盘；探针多为 d 级**默认不入诊断库**（minimumLevel 默认 INFO）；MDPilot 等 i 级在 dev 构建会入库但量小（~0.56 行/s 量级） | **无用户面增长**（stable/beta 无探针） |
| 诊断库（Room） | 有用户 `clear()` 入口，**无自动裁剪**——INFO+ 级业务日志长期累积（与探针无关的既有事实） | 有管理入口，可加自动裁剪为后手 |
| 宿主侧空间（开发机） | 10h soak 抓流证据 166M（cap_NNN 轮转文件）——非用户面，cap 轮转已在 | 可压缩归档（文本 zstd ~10x） |

## 2. 代码层事实

- **AppLogger 模型**（`logging/AppLogger.kt`）：每条日志恒发 logcat；持久化侧入队有界 Channel（容量 500，DROP_OLDEST），单消费者批量落库（50 条/批）；`minimumLevel` 跟随用户设置（默认 **INFO**——d 级探针默认不落库）。
- **门控形态**：探针发射点统一 `if (dev.leonardo.ocbeacon.BuildConfig.DEBUG) { AppLogger.i/d("Tag", ...) }`（MDPilot 上下文 7 处全门控；GapDiag `MessageCardAssistant.kt` 门控+注释「DEBUG-only，正常路径零成本」；CardExpandReveal `:212` 同形态）。全仓 AppLogger.d 共 ~405 站点；逐站点级验证未做完（辅助函数/多行条件门控形态多），但**带 campaign 标记的探针族**（本审计对象）抽查全部命中门控。
- **R8 无 Log 剥离**：`app/proguard-rules.pro` 无 `assumenosideeffects android.util.Log` 条目 → 未被 call-site 门控的业务级 `AppLogger.d` 在 release 仍会执行字符串构建+logcat 写（不落库）。这是探针之外的边角，见后手 ①。
- **诊断库清理**：`DiagnosticLogRepository.clear()` 存在（Diagnostics 屏入口）；无按条数/时间自动裁剪。

## 3. 实证层事实（soak 样本 cap_005.log）

- 20 分钟 72165 行（~60 行/s 全 logcat 含系统噪音），探针 14008 行 = **19.4%，~11.7 行/s**。
- 分 tag：**GapDiag 11489（82%，均 ~9.6 行/s）** ≫ MDPilot 677 / flng 603 / A11yDiag 440 / ScrollDiag 379 / MDResize 333 / ChunkDiag 49 / hflick 27 / CodeHL 11。
- GapDiag 是 #517 批次埋的零高槽位探针，在 soak 语料（重表格/长代码轮转）下高频命中——它是唯一的「吵」源。

## 4. 后手清单（按建议顺序）

1. **R8 剥离 Log.d/v（推荐，低风险）**：proguard 加 `-assumenosideeffects class android.util.Log { public static int d(...); public static int v(...); }`——release 侧连未门控业务 d 站点也清零；持久化路径（queue.trySend）不受影响（d 级本就不默认落库，用户调低阈值时 trySend 副作用保留行为不变）。
2. **GapDiag 节流（可选）**：同因由多条连发时聚合为「1 条+计数」（先例：流式日志 50-90 条/s 的 #135 优化），把 82% 的吵源压到 ~1 行/s 级。
3. **用户关闭选项：已存在，无需新增**——Settings 诊断级别调节即持久化开关（调回 INFO 即不入库）+ Diagnostics 屏 clear()；logcat 侧无需用户选项（release 已零）。
4. **宿主侧证据压缩归档**：soak cap 文件定期 `zstd`（文本 ~10x），或 review 后清理历史轮目录（logs529-archived-run1/2 等已是先例）。
5. （可选，非探针问题）诊断库自动裁剪：保留最近 N 天/N 条，clear() 保留。

## 5. 对 #473 的落点建议

维持 keep-probes 裁决（探针永久保留 DEBUG-only）：开销画像 release 零 / dev 可控且实证无害；用户关心的三个后手里两个已存在（级别调节+clear）、一个值得顺手做（后手 ① R8 剥离，~3 行配置+发版验证）。#473（清扫归档）可按「wontfix-by-ruling + 后手 ① 落地」收口，待用户拍板。
