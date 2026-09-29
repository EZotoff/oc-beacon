# #477+#480：a11y 语义盲区定罪 + 脚本参数防御（2026-09-29）

> 状态：#480 已修复待验收；#477 定罪深化、修复方向待裁决
> 关联：backlog #477 / #480
> 来源：用户开工指令「开始 #477+#480 …先演示…修复与自测…call 我验收」

## #480 backlog-new-batch.sh 参数防御（已修复）

**现象（现场复现）**：`./scripts/backlog-new-batch.sh "docs/journal/2026-09-29-closure-audit.md"` 静默创建畸形文件 `docs/journal/2026-09-29-docsjournal2026-09-29-closure-auditmd.md`（kebab 化剔除 `/` 与 `.` 后拼接），零警告。实害：#476 批 fa91c578 清理的 mangled 文件即此因。

**修复**：参数含 `/` 或以 `.md` 结尾 → die 并提示只接批次名。

**自测三连**：
- 传路径 → 拒绝并报用法 ✓
- `'closure audit'` → kebab 化命中既有文件报「已存在 勿重复创建」✓
- 纯中文 → 兜底 batch ✓（测试残留已清理）

**顺手修复**：`scripts/miui-install.sh` 的 `adb -s $SERIAL` 未加引号——带空格的 mDNS serial（`adb-e69a99d8-yzT17Y (2)._adb-tls-connect._tcp`）被词法分裂为 `unknown command`。全部改为 `adb -s "$SERIAL"` 后，本批用它点穿 MIUI 弹窗装 androidTest 包成功（顺手件实证）。

## #477 长文 a11y 语义零暴露——定罪链与机制边界

### 现象（双源干净取证）
- **视觉**（zai 双路交叉 + 像素）：长文 turn（Yangtze essay，3585 chars）30-31 行连续英文正文满屏渲染完整、无空白（/tmp/d477_eyes.png、/tmp/d477_lw2.png）
- **语义**（uiautomator dump，同刻）：正文区 0 文本节点（连空 TextView 都没有，仅 1 个 h≈1104px 空 View = chat-message-list 视口壳）；同屏工具卡（Build/glm-5.3-flash/50.3s）、顶栏、输入条全部正常暴露
- **对照**：同屏表格 turn 单元格全暴露（39 节点）；同 turn 的短 part（993 chars）暴露；短回复（<2048）全暴露；user prompt（纯 Text 路径）暴露

### 分叉实证（A11yDiag 探针，DEBUG-only，已留树）
| 场景 | 路径（探针实录） | a11y |
|---|---|---|
| 表格 turn（巨型，分段） | `preParsed chunked=true`（Giant 多 LazyColumn item） | ✅ 暴露 |
| 短 part（993，全量） | `preParsed chunked=false stateType=Success` | ✅ 暴露 |
| 短回复（312/222） | `syncSmall` | ✅ 暴露 |
| **长文（3585，全量）** | `asyncTerminal Loading` → 161ms 后 `preParsed chunked=false Success` | ❌ **盲** |

时间线（真机 logcat 21:47:51）：`src=asyncTerminal len=3585 stateType=Loading` → `preParsed chunked=false len=3585 stateType=Success`——**最终稳态走全量 preParsed 且 Success，视觉完整而语义零暴露**。

### 已排除的假设（全部实证/源码级）
1. **覆写组件层**：`MarkdownBasicText`（库）= `BasicText` 纯委托；表格单元格同款组件+同款 `clickableMarkdown` modifier 却暴露
2. **库重载层**：`Markdown(markdownState=)` 仅 collectAsState 后委托 `Markdown(state=)`，两路径完全同构
3. **success 槽层**：默认 `MarkdownSuccess`（Column+forEach MarkdownElement）与项目 `chunkSuccessSlot` 逐行同构
4. **clearAndSetSemantics/语义清除**：全项目 grep 无
5. **流式完结态特有**：重进会话（进程重启）后历史长文仍盲
6. **item 高度超视口**（假设 H）：21:22 首日取证中**未分段**表格 turn（单 item，高 3674px > 视口 2594px）单元格照常暴露——超视口单 item 不必然盲
7. **裂变带/recent 集**：这解释了长文为何未分段（视口内 turn 受 F2 裂变带保护不建分段计划+recentStreamedTurnKeys 冷却），但不是盲的直接原因（未分段表格 turn 暴露）

### 剩余最强嫌疑（未定罪）
- **变量收窄到「单 part 纯文本长度」**：同 turn 内 993 chars part 暴露、3585 chars part 盲（同 item 同路径同组件）；表格 part（>3000 但结构为 GFM TABLE）不受影响
- `SPLIT_PARAGRAPH_THRESHOLD_CHARS = 3000`（normalizeForRender 拆段线）是最强分界候选：993 < 3000 不拆（暴露）、3585 > 3000 拆段（盲）；表格 part 拆段只动普通行、TABLE 结构不动（不受影响）。2500 chars 对照实验因真机滚动/dump 陈旧摩擦未完成
- 桥侧机制（Compose semantics→AccessibilityNodeInfo）黑盒：真机 androidTest 语义断言已写好（`LongMarkdownA11yTest` 三用例），但被 MIUI 安装拦截+Espresso idle 同步挂死（app 周期 ticker）双重阻塞，待环境解法

### 修复候选（需裁决，未盲改）
1. **A. 完结即分段**：流式完结帧（#472 换装重建同帧）把超阈值 turn 直接以分段形态进入 entries——裂变发生在本来就要重建的帧，理论上无感；但触碰 #300 裂变禁令边缘+高度引擎交互，深水区需专项验证
2. **B. 到达即分段兜底**：buildChatEntries 对「首次进入组合且 registry 已 Parsed 的 ≥3000 part」即时构建 segPlan——只保护历史 turn 首进，不解决流式完结的当屏盲窗口
3. **C. 拆段线对照定罪后修 normalize**：若 3000 拆段线定罪（2500 暴露/3585 盲的分界），修 `splitParagraphs` 的产出形态可能从根上解决——成本最低但需先完成对照实验
4. **D. 平台上报+文档化**：若定罪为 Compose/平台桥缺陷，app 层只留绕行（加速分段覆盖）+已知限制文档

### 影响面重申
- 自动化验证可靠性（dump 判读污染，#467 已注记）
- **TalkBack/无障碍**：盲窗口期内屏幕阅读器用户读不到长文正文（真 a11y 缺陷，主诉级）
- 视觉渲染、滚动、流式全部无恙（多轮 vision+像素+SGR 日志交叉）

## 环境债（本批撞上的摩擦，留档）
1. MIUI 全新安装（test APK）连 pm install 都拦——`miui-install.sh`（引号修复后）可点穿
2. connectedDevDebugAndroidTest 的编排会被 MIUI 拦+卸主包；绕行：手装两包+am instrument 直跑（文档 device-testing.md L90 已载）
3. ComposeTestRule waitForIdle/断言在 app 周期 ticker 下挂死——轮询 fetchSemanticsNodes 也同步等 idle；需 mainClock.autoAdvance=false 方案或瘦身 test Application
4. 真机滚动后 dump 三连同值=陈旧陷阱再现（收 IME+大滚动+rm 旧 dump 三件套才可靠）

<!-- 过程中的取证/验证证据直接写本文件；backlog.md 只留 ≤3 行卡片。 -->
