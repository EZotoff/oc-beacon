# 488-syntax-highlight-shell（2026-10-01）

> 状态：进行中
> 关联：（spec 路径，若有）·（issue 编号，若有）
> 来源：用户反馈 / grilling / E2E / 顺带发现

<!-- 过程中的取证/验证证据直接写本文件；backlog.md 只留 ≤3 行卡片。 -->

## 实现落点（影响面分析 §1.1 全对齐）

- 新增 `ui/screens/chat/markdown/CodeSyntaxTheme.kt`：`ColorScheme.toCodeSyntaxTheme()` 纯函数（9 角色：code=onSurface/keyword=primary/string=tertiary/literal=secondary/comment+multilineComment+punctuation=onSurfaceVariant/metadata+mark=tertiary；key="ocbeacon"）。
- 新增 `ui/screens/chat/markdown/HighlightedCode.kt`：fork 自 -code v0.45.0 `MarkdownHighlightedCode.kt`（头注标注 fork 基线）。四处差异：①theme 纳入 produceState 键（上游只键 code，切主题旧值残留——本轮真机已证我们修复生效）；②每作业新建 Highlights.Builder（上游共享 builder 是可变对象，多代码块并发高亮竞态）；③区间守卫（end exclusive 语义 + start<0/越界/反向跳过，#75/#415 防御）；④AppLogger DEBUG 打点（tag=CodeHL）。
- `MarkdownContent.kt` 三点位：codeSyntaxTheme 构建（remember 键=5 个参与映射角色）+ components remember 键追加 + codeFence/codeBlock 覆写（置于 #488① custom 钩子之前）。
- `build.gradle.kts:248-249` 注释改写（「Markdown 代码块使用默认渲染器」的不实陈述移除，零依赖行变化）。

## 实现期发现（单测驱动出的两个引擎事实）

1. **getByName 未命中返回 null**（不抛）：highlights 1.1.0 字节码 aconst_null 分支直证；大小写不敏感（"Kotlin"="kotlin"）。
2. **无语言构建有噪声着色**：单测 `未知语言静默纯色` 首跑失败——builder 不带 language 时引擎按 DEFAULT 语言集仍对任意文本产出内容相关 span（"some code" 有、"plain text" 无）。修正设计：`getByName == null → 纯文本直返`（不走引擎）——「未知语言=现状等价」的承诺行为由构造保证；顺带把 `getOrDefault(emptyList())` 换 `getOrNull().orEmpty()`（null 返回值会穿透 getOrDefault，平台可空双防）。
3. KDoc 里写 `*/path/*` 会提前终止块注释（测试文件首版编译失败根因）。

## V1（编译+单测）

- compileDevDebugKotlin 绿；新测试 14 例（CodeSyntaxThemeTest 4 + HighlightedCodeSpanSafetyTest 10）全绿。
- 全量 `testDevDebugUnitTest --rerun`：**3764 通过 0 失败**（3750 基线 + 14 新例）。
- 关键钉死用例：end exclusive（"1e+10"→(0,5) 五字符全染）、end 越界钳制、反向区间（issue #75 形态）跳过、Bold→FontWeight、kotlin 真实引擎链路着色、未知/null 语言纯文本、alpha 通道保全、AMOLED 免特判（纯函数等值）。

## V3 真机 E2E（houji dev 包 0.3.0+，v1:4199 通道）

**环境坑三连（journal 留档）**：①beta 包（模块 E 遗留进程）与 dev 共连同一 v1 服务器同会话，SSE 镜像渲染且两度抢前台——前两轮 prompt 全发进了旧版 beta（零 CodeHL 的假阴性来源）；**杀 beta 进程后日志归属才无歧义**。双包同服务器测试时必须 `dumpsys window mCurrentFocus` 逐步校验。②uiautomator 坐标系=1200x2670（非 1080x2400）。③EditText text 属性 dump 假阴性（文本已入但 dump 不暴露，#464/#477 族）——关键输入必须截图验证。

**完结路径**：会话载入即着色——CodeHL `len=1464 spans=216 ms=53`、`len=2032 spans=289 ms=47`（各两次=首组+重组合）；像素聚类：黑(默认)+蓝系(关键字)+青系(字符串)+中灰(注释) 四族群（单色渲染不可能的分布）。

**流式路径（30 行 Kotlin lunar lander，01:41:05-14）**：
- MDPilot 行级放行 147→262→451→…→2090（#487 语义不变）；
- **CodeHL 逐批着色 13 次**：len=114→234→388→636→745→900→1040→1193→1524→1821→1866→1965，spans=11→326，**ms=2-27（全部 < 48ms 批间隔——小中块批间即完成着色，优于「EOF 才着色」的保守预期）**；
- 流中帧像素（01:41:13）：蓝系 14546+青系 6757 文本像素=**流式期实时多彩**（与 CodeHL 时序互证）；
- MDResize 单调性：h=96→544→904→…→3948 严格递增，d 全正（96…840…24），零负向零重排——**Bold advance 风险③未触发**；
- 守卫：MDPilot reset/nonPrefix=0；ScrollDiag 负向/LEAP=0；gate 末态 releasedTotal=2090=snapshotTotal。

**主题三态（cmd uimode 注入，代码块在组合中执行）**：
- 每次切换 CodeHL 即时重放（01:44:32 dark / 01:44:38 回亮）——produceState theme 键设计生效；
- 像素：亮色关键字 rgb(80,80,112) 深蓝 → 暗色 rgb(144,192,224) 天蓝 → 回亮**逐位复原** (80,80,112)——残留旧色风险⑤消除。

**未支持语言**：json 围栏 CodeHL `lang=json spans=0 ms=1` ×3——静默纯色=现状等价（承诺行为真机闭环）。

**八调用面覆盖（诚实边界）**：assistant 完结 preParsed ✓（两块）、流式 pilot ✓、工具卡（写入·Rain.kt，无围栏内容）✓ 渲染无异常、reasoning 块（无围栏）✓；预览对话框/压缩卡/通知卡未构造场景（components 单例共享=覆写全局面生效，批 1 无逐面分支）；用户观感面（着色时机/pop 观感/配色满意度）留 V6 验收。

**副作用**：v1 会话内模型行为（写 /tmp/opencode/Rain.kt 两次+代码块三轮）——服务器侧 /tmp 临时文件，仓库零污染（git status 仅本批改动）。

## 判据汇总（批 1 验证门槛逐项）

| 判据 | 结果 |
|---|---|
| compileDevDebugKotlin + 全量单测 | ✅ 3764 绿 |
| 流式 MDResize 严格单调/零负向 | ✅ d 全正 |
| RESETKEY/nonPrefix=0 | ✅ |
| CodeHL 着色时机（流式 vs EOF） | ✅ 批间即着色（ms≤27） |
| Bold advance 无单块重排 | ✅ |
| 主题切换无残留 | ✅ 三态像素+重放互证 |
| 未知语言静默纯色 | ✅ spans=0 |
| i18n 面 | ✅ 零文案零 a11y 变更 |

## 批 1 验收（2026-10-01）

用户验收通过（「ok 可以」）——批 1 最小着色收口。#488 剩余：②批 2 观察/批 3 可选、③数学标注（本批续开）。

## 实现落点（4 文件 + 15 i18n + 2 文档）

- `MarkdownMathFallback.kt`：`appendBlockMath` 产物围栏标签 `tex → math`——math 是数学降级专属识别位（渲染期归一化不入库，无存量双写）；AI 手写 tex 围栏不受影响（仍走 SafeHighlightedCode，引擎无 tex 语言→纯文本=现状等价）。KDoc 同步。
- `HighlightedCode.kt`：`MATH_FENCE_LANGUAGE="math"` + `SafeHighlightedCodeFence` lambda 内 `language.equals(math, true)` 分流 → 新组件 `SafeHighlightedMathBlock`——`MarkdownCodeBackground` 同款底壳（徽标+内容统一圆角块）+「公式」徽标行（labelSmall+Medium+onSurfaceVariant）+ 等宽轻着色内容（horizontalScroll）；**静态完结内容同步构建 + remember**（键=code+三色角色——数学变换只在完结渲染期发生，流式期无 math 围栏，无 produceState 需求）。纯函数 `buildMathAnnotatedString`：`\命令`（反斜杠+连续字母整段）tertiary / 花括号 onSurfaceVariant / `^`_` secondary；`\`后非字母（`\,` `\%` `\\`）LaTeX 转义字面量保持原色；区间由扫描器顺序产出（非重叠、界内，无引擎区间守卫需求）。
- i18n：`math_block_badge`（en=Formula / zh-rCN=公式 / ja=数式 / ko=수식 / ru,uk=Формула / de=Formel / es,pt-rBR=Fórmula / fr=Formule / it=Formula / id=Rumus / pl=Wzór / tr=Formül / ar=صيغة）×15；`i18n-check.sh` PASSED（917 keys × 14 languages）。
- 测试：`MarkdownMathFallbackTest` 全部 ```tex 断言改 ```math（NormalizeTaskListMarkersTest 只有 ```text 不涉及）；新增 `MathBlockHighlightTest` 12 例（命令整段含反斜杠/大写命令/非字母转义不染/末尾孤立反斜杠越界守卫/花括号单字符/上下标单字符/纯文本空串零 span/综合区间有序不重叠界内/原文保全/路由守卫（null·Math·tex·mathematics）/transform 产物=math 围栏）。
- 文档：regression-guide 域 6 增 6c 行（数学降级块判据）；ui-conventions Syntax theme 节补数学块手写三角色映射说明。

## 环境事实（E2E 中撞上并定罪）

- 新会话默认模型 **zhipuai/glm-5.3-flash** 在 v1 隔离环境无凭据（`~/oc-v1-env/{data,data2,config}` 三处 auth.json 全缺）→ provider 侧 `AI_APICallError: 身份验证失败`（服务端 opencode.log 18:14Z 定罪，app↔server Basic 认证正常——会话列表可加载）。批 1 会话用的是 **opencode/big-pickle**（服务端日志 providerID=opencode）→ E2E 改在既有 big-pickle 会话进行，模型问题非本批改动引入，**不登记新卡**（v1 环境凭据配置属服务器运维面，若用户后续要跑 zhipuai 再处理）。
- 设备经网络 adb（192.168.110.239:36339）+ USB 双传输挂同一手机；hitl3 抓包在网络传输上存活 → 本批全程**未执行 `adb logcat -c`**（debug-entry.sh 复刻序列手工执行，成功标志改用 mCurrentFocus+uiautomator dump 校验）。

## V1/V3 验证证据

- V1 单测：compileDevDebugKotlin 绿；`testDevDebugUnitTest --rerun` 全量绿（BUILD SUCCESSFUL，含新 12 例与改标 transform 断言）。i18n 检查 PASSED。
- V3 真机（e69a99d8 网络传输，dev 包 02:11 构建 adb install -r 覆盖安装，debug intent 冷启 Host-4199）：
  - prompt「reply in chat only… quadratic formula… double dollar sign delimiters… backslash parenthesis delimiters」（type.sh 纯 keyevent，dump 头+截图尾互证完整入框）→ big-pickle 4.3s 完结回复；
  - **块级降级**：完结后 dump 出 `Code block, math`（a11y desc，[36,925][1164,1074]）+ `text='公式'` 徽标行 + 等宽公式行 `x = \frac{-b \pm \sqrt{b^2 - 4ac}}{2a}`（可横滚）——transform 产物 math 围栏被 SafeHighlightedMathBlock 精确接管 ✓；
  - **着色像素取证**（暗色 1200×2670 直接采样）：公式行 y=1027 双族色——蓝紫系 (192,192,216)×23+(24,24,48)×53（\frac/\pm/\sqrt=tertiary）+ 中性灰 (216,216,216)/(192,192,192)（花括号/上下标=onSurfaceVariant/secondary）vs 正文白；徽标行 y=970 灰系 (192,192,192)；
  - **vision 判读**（裁块放大）：徽标=「公式」白字无独立 pill；命令淡紫薰衣草 vs 普通字符白；花括号+^ 第三色弱灰（三色方案确认）；徽标与公式同一深灰圆角块（统一容器）；
  - **亮色重建**（`cmd uimode night no`，块仍在组合）：公式行像素族翻转为深蓝紫 (72,72,120)×21+(144,144,168)×8 + 深灰正文 (24,24,24)×17 + 灰蓝 (48,72,72)/(120,120,144)——remember 键纪律生效，无旧色残留；切回 night yes 恢复原状；
  - **守卫**：正文单美元 `$ax^2 + bx + c = 0$` 保持原文不误伤（货币防护，恰为模型实际输出形态）；模型未按指示用 `\(...\)` 而用单美元 → 行内降级路径本条未触发（③ 未改行内路径，#312② 既有单测覆盖；单美元不误伤反而是本条的实际验证点）。
- 流式期原文如实未单独截图取证：③ 零改流式路径（math 围栏只在完结归一化管点产生，#312② 已单测钉死 + V6 完结跳变项沿用）。

## 遗留与边界

- `\{`（转义花括号字面量）会被当分组花括号着色——轻着色纯增益路径的已知化妆品级偏差，不值守卫成本。
- 徽标色 full-alpha onSurfaceVariant；块底壳较深，亮暗两主题实测均可读（vision+像素双证）。
- 批 2（观察精修）/批 3（可选语言标签+复制）仍挂卡上待观察窗结论；③ 就此收口待用户验收。

## 批 ③ 验收（2026-10-01）

用户验收通过（「可以 我觉得可以暂时用这样的效果」）——③ 数学降级+标注升级就此收口；批 2（观察精修）/批 3（可选）维持挂卡，随观察窗推进。

## 批 2 观察精修——完结/冷看色 pop 根修（2026-10-10 晨，3056fcbc + 1859ef22 + review 修复）

**观察证据（pop 实存）**：批 1 遗留「初值纯文本→异步着色 1-2 帧纯色 pop」在冷滚动回看路径每次重付（组合位销毁→produceState 重建→再异步）。模拟器实证（修复前基线）：同块冷看 CodeHL 日志 len=112 ms=8（异步作业可见）。

**测量定参（HighlightedCodeSyncCapProbeTest，JVM）**：引擎 ~1.3µs/char 线性——459c=0.67ms / 1883c=2.5ms / 4763c=7ms / 29567c=30ms。Android ×3 折算对 ~4ms 帧预算 → **同步阈值 CAP=400 字符**（研究文档预想的「immediate=true 覆盖 <2000c」被实测否决：1883c JVM 2.5ms → Android ~7.5ms 超帧）。

**机制（双保险）**：
1. 同步初值（≤400c 且首组合，`initialHighlightedFor` 纯函数）——小块首帧即着色；
2. 进程级 `CodeHighlightCache`（LRU32/单条 64KB 上限/theme 身份入键）——任意大小块重看零成本（异步作业/同步产物均回写）；
3. >400c 首看维持异步（1-2 帧纯色后着色落缓存）——接受面：400-2000c 冷看首帧仍 pop 一次（研究文档「chunk <2000c 主线程 1-3ms」假设被基准证伪，400 是帧预算内的诚实上限）。

**code-review 双轴回执**：Standards 轴抓到真回归一处——skip 守卫初版用「文本相等」判定，主题切换（文本不变）会误跳过冻结旧色（批 1 的「key 含 theme 重启换新色」行为回归）；已改**同一性判定**（value===initialHighlighted——re-key 必产生新初值实例）+ SideEffect 后置 consumed + cacheKey 单源提取。Spec 轴确认缓存为「等效+」机制（研究处方 immediate 的合理替代，多覆盖冷回看），流式首批 ≤400c 同步属有据拓宽（48ms 批节奏节流 ≈4% 主线程）。

**验证链**：单测 16 例新增（缓存 5+初值策略 6+探针 1，全绿）；仪器化新增冻帧回归测（同消息/同 part id 追加改写不冻结——mainClock 语义等价流式批）；全量 JVM 3969/0；lint 门禁绿（含 #536 SpillNotice 正名去 Dsh 前缀修复——B1 遗留 5 errors 会挡 CI）；模拟器 live 实证：冷看 python 块 CodeHL=0（同步路径）+ 着色视觉确认（关键字蓝/f-string 紫/PYTHON 顶栏）+ 三屏离开回看 0 增量（缓存命中）。

**批 2 余项状态**：大块流式 CPU 量化（§2.j 真机面）/metadata·mark 视觉试验/AMOLED 真机面板目检/Bold advance 宽度测量——挂真机空闲窗（夜跑基建已死于无线 adb 闪断 02:47，见 B2 journal）。400-2000c 冷看首帧 pop 残余接受面 + §2.j 量化合并留观。

## 批 2 §2.j 量化——模拟器腿（2026-10-10 晨，真机腿挂设备回归）

- **流式重启成本（解析模型 + 基准）**：作业线性 1.3µs/char（JVM）× ≤20 作业/s（48ms 批节流）× 作业均长随流增长——9KB 大块全程重高亮总功 ≈300ms（Android 折算）摊在数十秒流上，且全在 Default 线程；主线程新增仅首批 ≤400c 同步（~2ms/批，节流后 ≈4%）。
- **live 实证两条**：① 200 行 Kotlin 聊天块冷组合 **CodeHL=0**——模型先经 Write 工具产出同内容（工具卡渲染面已高亮入缓存），聊天块跨渲染面缓存命中（缓存面覆盖 8 调用面的意外红利）；② 同块滚走 4 屏回看 **CodeHL delta=0**——>CAP 大块冷重入零成本，缓存设计目标在 7KB 级实测成立。
- **残余（真机腿，设备回归后补）**：simpleperf/CPU profiler 的 Default 线程占比实测 + 流式期间真机滚动 MDResize 单调性复查（本轮模拟器流中未滚动，buffer 已轮换无探针数据）。
