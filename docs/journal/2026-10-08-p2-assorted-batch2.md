# p2 assorted batch2（2026-10-08）

> 状态：进行中
> 关联：（spec 路径，若有）·（issue 编号，若有）
> 来源：用户反馈 / grilling / E2E / 顺带发现

<!-- 过程中的取证/验证证据直接写本文件；backlog.md 只留 ≤3 行卡片。 -->

## ## §1 #532 复核：实现已在库（4bf42f1f）+ 真机 E2E 补验

- 实现盘点：app 侧（ServerDialog ℹ️ 图标+弹窗+ACTION_VIEW 链接，4 键×15 语言）与 README 八节重写（dsh-password-login@84ff754 已推送 GitHub）均已完成——本批只欠验证。
- 编译 `:app:compileDevDebugKotlin` PASS；assembleDevDebug 成功；真机（houji 无线 adb，mDNS 双 transport 用带空格全 serial 指定）装 dev debug 包。
- E2E 路径：debug-entry.sh 冷启 → 会话列表 → BACK 回 Home → 添加服务器 → 切 DSH tab：
  - DSH 形态正确（server_type_dsh_hint 提示行 + DSH URL 示例 + 用户名字段隐藏 #276）；
  - 「密码插件说明」ℹ️ 图标在 DSH tab 右侧（bounds [785,645][929,789]）；
  - tap → 弹窗三键在场：标题「密码插件」/文案（插件简述+README 指引）/「安装说明（GitHub）」/取消；
  - tap 链接 → ACTION_VIEW 发出（MIUI ConfirmStartActivity 拦截确认=intent 已出系统级跳转路径通）；BACK 返回 app 弹窗仍持位。
- 结论：#532 验证面全绿，转 `[~]` 待用户验收。

## ## §2 #493 模型抽屉键盘保持——布局内 overlay 落地（两轮方案+真机全判据）

- **方案一轮（Popup 候选）被真机否**：9f004132 ModalBottomSheet→Popup(focusable=false)。真机（houji/MIUI，Compose core 1.11.2 矩阵）取证：popup 窗口在场（dumpsys frame 1200x1566, ty=APPLICATION_SUB_PANEL, NOT_FOCUSABLE）+组合三打点全出（composed/call site/content）但**表面零绘制**——wrap 小蓝面 0 像素、scrim 0 像素、a11y 树无节点；无动画版/带动画版双构建同症，排除动画与 fillMaxSize 测量。busy 菜单（#361，wrap 小气泡）同款 popup 在同机正常——疑与全屏/大面积 popup 或版本矩阵相关，未深究（已有可行替代）。
- **方案二轮（布局内 overlay，用户裁决的另一候选）落地**：ChatScreen 根 Box(Modifier.fillMaxSize()) 挂载层（Scaffold 之后 z 序在上；FileViewer 等 Dialog 组件进 Box 无影响）；ModelPickerDialog 根=Box(fillMaxSize){scrim+sheet}，无独立窗口不夺焦点。语义保留：BackHandler 返回关闭/自绘 scrim(scrim@0.32 同 M3 1.4.0)点外关闭/75% 固定高/手柄拖拽收起（跟手位移+越 30% 高松手收起，Animatable）；出/入场动画 MutableTransitionState+滑出落定再 onDismiss；#379/#405 隔离语义随 M3 anchoredDrag 检测器消失自动满足（原两 modifier 摘除）。
- **真机 E2E（big-pickle 会话，V1 腿）全判据**：
  1. IME 保持×3：聚焦输入框 mInputShown=true → 开抽屉 true → 选模型关闭后 true（核心判据）；
  2. IME 态显示：scrim 在场（采样≈169=232×0.68）/sheet 面/模型列表 a11y 全出（选择模型/管理模型/Big Pickle/GLM-4.5/Ling 族/免费标签）；sheet 75% 全屏高越过可视顶属预期（adjustResize 下窗口高 1566）；
  3. 无 IME 态显示：scrim（y≤600≈169）+sheet（y≥850 内容区）正常；
  4. 选模型链：tap 模型行→滑出动画→组合移除→选中回显、composer 焦点未丢（hint 隐藏）；
  5. 返回键：IME 开时 BACK①收键盘（系统标准序）、BACK②BackHandler 关抽屉；app 不退前台；
  6. 点外关闭：tap scrim 区 (600,300) → 抽屉关。
- 边角注记：被键盘遮挡的列表项（GLM-4.5 bounds y≈2533 落键盘后）tap 落键盘上无效——属 IME 遮挡常识非缺陷；手柄拖拽收起的真手指体感（跟手/回弹）留用户验收清单（注入手势非真手指流）。
- **三端共享**：组件单点（ChatScreenDialogs 唯一调用点），V1/V2/DSH 同改自动生效；改动纯 Compose 容器层无端差异逻辑，V1 腿全覆盖组件行为。commit：9f004132（Popup 轮，保留为取证史）+ 布局内轮（根 Box 挂载）。

## ## §3 #519 定罪：服务器 attempt 隔离语义——失败尝试的思考内容不进权威消息（app 无罪，与官方 web 一致）

- **三通道静态定罪（10-06 quicksort 轮，session-26add722）**：journal 落盘（9 帧 zstd 循环解压）assistant/message seq=19 = [reasoning 224, text 2667]；session/page（history 通道，经 token 首跳铸 dsh-auth-* cookie + 斜杠端点 session/page{address,throughSeq} 复现）读回同形状；dsh-session append 源码 deepFreeze 完整事件广播（无投影过滤）——服务器常规轮三通道 reasoning 全在场。
- **live 复现轮（10-08 session-7bc2474a，bubble vs quicksort）复现原形状**：流式期 app 收到 dsh-t1s1_reasoning_ord_0（B2-bus/B3/B4 日志实证渲染）；完结后 journal 权威 assistant/message seq=21 = [text 159] **零 reasoning**；该事件 stream 字段（完整 chunk 流）零 reasoning-delta。关键：journal seq=18 assistant/attempt + seq=19 llm/retry + seq=20 llm/retry-started + seq=21 assistant/message——**流中 reasoning 属第一次 attempt（流过半失败重试），权威消息=第二次成功 attempt 的块**。
- **机制定罪（dsh-agent-loop 0.2.0-rc.2 源码）**：每 attempt 独立 AssistantStreamAttempt；失败 attempt 落 assistant/attempt 事件（含完整 stream 但不进消息装配）；settle(assistant/message) 只 append 最终成功 attempt 的 blocks()。10-06 quicksort 轮同构：3 text（4564/390/4555）=最终 attempt 输出（模型回声系统提示词怪癖），reasoning 443 字节在失败 attempt——思考卡随换装消失是**服务器 attempt 隔离语义的客户端呈现**。
- **官方 web 对照（dsh-client-ui-chat 源码）**：assistant/attempt → closeOpen()（关闭开放流，重试重开）；终态呈现跟随权威 assistant/message——**官方 web 完结/重进同样无该思考内容**。app 行为与官方一致，非缺陷离群。
- **app 链路无罪链**：DshRemoteMuxEngine end 帧只清 attempt 记录（不带 content）；权威 parts 走 journal 事件广播→mapSessionEvent（reasoning 分支在场 1356/1155）→fold/Assembler golden 测试锁定（"thinking..."→Part.Reasoning）。「重进亦无」=journal fold 不装配 assistant/attempt（v3 payload 文档裁决：attempt 维持静默）——与官方一致。
- **裁决点（未动工）**：A 维持现状（对齐官方权威语义）；B app 换装保留流中 reasoning（attempt 事件可恢复，需标注尝试期思考+处理重进一致性，偏离权威对账语义）；C 上游反馈（服务器归并 retry 间 reasoning 进权威或加标志）。按修复方针「分层不明确先呈现分析请裁决」——呈现用户定夺。
- 附：复现轮顺带实证 live 端到端（真机 debug intent 直连 3080：token 交换 ok→新会话→流式思考卡在场→完结→重进）；服务器三通道 API 面全通（session/list{args:{_request}}/session/page{args:{request}}）为后续 DSH 域取证可复用资产。

## ## §4 #493 code-review 收口 + 复验

- 双轴复审（Standards/Spec 并行 subagent）发现并修三处：①ui-conventions 硬违规×2——scrim 0.32f 与 tween 散值 token 化（SheetTokens.ScrimAlpha/DragDismissFraction + AppMotion.SHEET_ENTER/EXIT 新增，数值不变纯重构）；②Spec 真缺陷——BackHandler(enabled=targetState) 在 dismiss 动画 180ms 窗内放行第二按 BACK 穿透到页面级返回，改组合期恒拦截（79304216 收口 commit 前的 9f/c884 后续）。
- 复验（收口 commit 后真机）：装包→开抽屉→250ms 间隔双 BACK 快速连按——抽屉关闭且仍在会话页（穿透修复实证）；全量单测 3932 绿；i18n 874×14 PASSED。
- 接受项（留人工验收清单）：手柄拖拽收起的真手指体感（跟手/回弹，快速轻拂小位移弹回 vs M3 fling 不完全等价）；dismiss 动画期 scrim ~200ms 渐隐残留（动画语义本身）；75% 固定高在 IME 态顶溢出被裁（真机已验 scrim/sheet 可见性正常）。
- 顺带登记：#512 批次遗留 lint 门 5 error（SseConnectionManager ServerTypeWhitelist 越白名单，b8cda845 引入，非本批）——新卡待裁。

## 已完结卡片迁入（2026-10-08）

### **#519 DSH 完结换装后思考卡消失——权威消息 content 无 reasoning part，流中思考内容完结即蒸发** `dsh` `streaming` `completion`
  - 真机（2026-10-06 quicksort 轮）：流中 reasoning part（dsh-t1s1_reasoning_ord_0，443 字节 payload）正常渲染+计时；完结 seq 换装后权威消息（seq-...b7e40eebd）parts 仅 3 个 text（4564/390/4555 字符）零 reasoning——思考卡随换装消失，重进亦无。与 #501（正文换装砸出）同族的 reasoning 面：流中可见完结即失
  - 待查：DSH 服务器权威 content 是否本就不含 reasoning（服务器侧形状）vs app 端 DshEventMapper 换装丢弃；对照 DSH 0.2.0 wire 文档 block 序列
  - 定罪完结（2026-10-08 journal §3）：根因=服务器 attempt 隔离语义（失败尝试 reasoning 落 assistant/attempt 不进权威；live 复现轮 journal seq18 attempt+seq19/20 retry+seq21 权威[text]零 reasoning 实证）；官方 web 同行为（closeOpen+终态跟随权威）；app mapper/fold 无罪（golden 锁定）。裁决点 A 维持现状/B app 保留流中 reasoning/C 上游反馈——待用户拍板
  - 2026-10-08 用户裁决 A：维持现状（对齐官方权威语义——attempt 隔离为服务器设计，app 忠实权威正确）；据此完结
  - 迁入依据：定罪+裁决 A 完结（journal §3：服务器 attempt 隔离语义，官方 web 同行为，app 无罪；用户 2026-10-08 拍板维持现状）（backlog.sh migrate 2026-10-08）

### **#493 模型抽屉键盘保持——切模型不收键盘（三端共享改造）** `keyboard`
  - 真机实测三端一致为「收起→抽屉→回弹」（ModalBottomSheet 独立窗口必夺焦点）；2026-09-30 用户裁决基准=不收键盘。
  - 实现方向：换主窗口内非模态呈现（Popup(focusable=false) 容器或布局内 BottomSheet），保留返回键/点外关闭、scrim、75% 固定高、#379/#405 手势隔离语义；共享组件=三端同改（影响面已裁决）。
  - → docs/research/2026-09-30-server-type-uiux-consistency-audit.md §一
  - 实现落地（布局内 overlay 方案，Popup 轮真机零绘制被否）：真机 E2E V1 腿全判据过（IME 保持×3/双态显示/选模型链/BACK 两段/点外），手柄拖拽体感留人工验收 → journal 2026-10-08-p2-assorted-batch2 §2
  - 2026-10-08 用户裁决：不需要键盘保持功能——选择模型时键盘收起、跟之前一样；代码已回滚（四文件还原 f8161e81），真机复验原行为；据此完结。两轮方案取证（Popup 子窗口真机零绘制/布局内 overlay 全判据）留 journal 供未来重启参考
  - 迁入依据：用户裁决撤销需求（回滚完结）：键盘保持不需要，恢复 ModalBottomSheet 原行为；实现+验证+回滚全程 journal §2/§4（backlog.sh migrate 2026-10-08）

## ## §5 #519 裁决 A + #493 撤销回滚（2026-10-08 用户拍板）

- #519：用户裁决 A 维持现状——attempt 隔离为服务器设计/官方 web 同行为，app 忠实权威正确；完结迁移。
- #493：用户裁决**不需要键盘保持**——选择模型时键盘收起、跟之前一样；回滚 9f004132/c884355f/68f0b79d 净效果（四文件还原 f8161e81：ModelPickerDialog 回 ModalBottomSheet、ChatScreen 根 Box 摘除、Motion/SheetTokens 新增 token 常量清退）。编译绿+全量单测绿+真机复验：ModalBottomSheet 抽屉正常打开、mInputShown=false 键盘收起=原行为恢复。完结迁移。
- 资产留档：Popup(focusable=false) 子窗口在本机矩阵零绘制（组合在场表面无输出）的取证、布局内 overlay 的完整实现形态（git 历史可考古）——未来若重启键盘保持方向先读 §2。

## 已完结卡片迁入（2026-10-09）

### **#532 DSH 表单 tab 密码插件提示图标 + 插件 README 安装说明** `dsh` `uiux` `docs`
  - 新建/编辑服务器 DSH tab 旁 ℹ️ 图标→弹窗简述插件+GitHub 链接；插件 README 八节重写（安装三式/首设三途/rotate·revoke/十项注意事项）
  - ⚠️ GitHub 仓库尚未创建——链接暂用 LeoNardo-LB/dsh-password-login，需建仓推送后生效
  - GitHub 仓库已建并推送（2026-10-08）：github.com/LeoNardo-LB/dsh-password-login（public，master）——app 链接与 README 安装命令即刻生效
  - 迁入依据：用户验收（2026-10-09「532 ok」）：实现确在批前已落地（app 侧 4bf42f1f+插件仓库 README 84ff75f 均已推送），本批补真机 E2E 全绿后转 [~]；GitHub 仓库公开可达，链接与安装命令即效（backlog.sh migrate 2026-10-09）

## ## §6 #514 关卡前专项复核（2026-10-09）

- **清退面零残留**：DshPairingParser/handlePairDeepLink/pairRequestFlow/pairPrefillUrl 代码 grep=0；scripts/dsh-pair.sh 已删；manifest ocbeacon://pair intent-filter=0；server_pair_help_{title,manual,adb,deeplink} 文案=0（#532 批顺带补清的 prefillUrl 残留亦确认干净）。
- **保留链全在**：exchangeToken（DshConnectionRegistry:342）、debug_token 注入通道（MainActivity 3 处）、token 对话框/横幅文案（「Password or token」两形态）、cred-probe.sh——与 §1 影响面清单预判保留项一致。
- **活证据（10-08 #519 调查顺带）**：清退后 DSH 全链真机 E2E——debug_token 注入→exchange ok→会话列表→新建会话→发消息→完结→重进（journal §3 复现轮），即保留链（token 交换+注入通道）实证可用。
- 编译绿+全量单测 3932 绿。#514 关卡。

## 已完结卡片迁入（2026-10-09）

### **#514 DSH legacy token 基建退役（#512 批次④）——捞日志/adb 注入/深链配对清退** `dsh` `auth` `infra`
  - 前置：#512 dsh-password-login 插件用顺后另裁范围（用户已裁决方向，2026-10-05）。
  - 清退候选：宿主侧 token 镜像脚本（~/.dsh/sync-dsh-web-token.sh）、scripts/dsh-pair.sh 配对/adb 注入链、DshPairingParser 深链解析。
  - 预判保留：app 内 legacy 兜底（exchangeToken/token 持久化/输入 UI=回落链末级，服务无插件服务器）。开工时先列影响面清单请裁决再动手。
  - spec：docs/specs/2026-10-05-512-dsh-password-login-design.md（§K 批次④）；实现证据：docs/journal/2026-10-05-512-dsh-password-login.md
  - 迁入依据：关卡复核全绿（用户预授权「没问题就关卡」2026-10-09）：清退零残留/保留链全在+活证据（#519 调查 DSH 全链 E2E）+编译单测绿；上批执行与验证记录 §1-§2/§4（backlog.sh migrate 2026-10-09）
