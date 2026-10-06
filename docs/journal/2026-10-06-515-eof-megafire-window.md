# 515-eof-megafire-window（2026-10-06）

> 状态：进行中
> 关联：（spec 路径，若有）·（issue 编号，若有）
> 来源：用户反馈 / grilling / E2E / 顺带发现

<!-- 过程中的取证/验证证据直接写本文件；backlog.md 只留 ≤3 行卡片。 -->

## §1 系统性调研（2026-10-06，三轮真机复现 + card2b 复盘）

**环境**：真机 e69a99d8（120Hz）· dev 包（dd957af0 构建，SHARD 旗标 true）· V1@4101 docker · LongCat 2.5 Preview Free（与定罪日同服务器同模型）· 配方 prompt（8 节长文档、节内无空行、节间单空行+收尾句）。三轮全部复现 fire：origin=2192/2290/2437 单块 tail=33/64/68ch——与 2026-10-05 定罪同形。

### §1.1 旧 journal §3 勘误（#515 卡原文归因修正）

- **「1.9s 慢速重灌=refeedPacing 首跑限速、非 #H4 快灌」不成立**：fire 后 tail pilot 仅接收 13 批自然 delta（13→104ch，模型收尾句按 token 节奏到达）。冻结前缀（2021ch）根本不经 pilot 重灌——由 #g0 StreamChunk 静态渲染，换装帧内即测得全高（5956px@card2b / 3408/3653px@复现轮，fire→#g0 测高 23-43ms）。A2 注释「Fire 重建仍走 #H4 快灌」与实测**一致**（fastRefeed=true 路径仅灌 13-68ch 尾块，1-2 帧完成）。
- **「EOF 单发巨批 fire」表述修正**：fire 并非发生于流 EOF，而是**文档内容侧最后一个空行 run 落地、冻结累计首次过 2000 门槛**的批（card2b：total=2034 全放行时 boundary 0→2021；复现轮同型）。流仍在继续（tail 后续自然增长）。巨批的本质=**小节累计 <2000 直至文档尾**（章节 ~300ch × 8 节 ≈ 2400），门槛(2000)设计使然，非 bug。

### §1.2 机制链定谳（换装帧原子性 + 真实可见件）

**原子性（无塌洞）**：换装全链（fire→帽 hardReset→ENTRIES n+2→tail 重置→#g0 组合同步 parseMarkdown→#g0 测全高→首帧绘制）在**单一重组+测量 pass** 内完成。证据：对照轮（无录屏）fire 18:32:02.654 → MDResize 96@02.673 → #g0 测高 3408@02.697 → VDRAW h=96 绘制@02.701——**fire→完成 47ms**，无中间态绘制。card2b 同型（08.876→08.933，54ms）。

**真实可见件两个**（4Mbps 录屏逐帧差分 + 带状定位）：
1. **冻结区重排涟漪**：换装帧起，#g0 静态渲染与原 pilot 流式渲染对同一文本**布局不等价**——接缝下方 ~9-10 文本行（y732-1299）换装帧即变，接缝上方 ~6 行（y328-695）随后续 2 帧继续沉降；零整体位移（互相关 shift=0）。视觉=文档尾部数行文字「闪/涟漪」。终态与流式渲染一致（标题/段落齐全，Room 无损）。
2. **~40-50ms 主线程 hitch**：换装帧同步 parse+组合+测量 2400ch（120Hz 下 5-6 vsync）。gfxinfo 全轮 p90=20ms，hitch 为孤立帧。

**观测污染警示**：12Mbps/55fps screenrecord 使同一 fire 的显示冻结放大至 **850ms**（编码器抢资源+帧积压；4Mbps 降到 ~460ms；无录屏 47ms）。10-05 定罪日无录屏记载——用户目击为内禀件；但**任何带录屏的验收轮**测得的「冻结时长」不可直接引用。

### §1.3 证据文件

/tmp/515-repro.log（三轮完整 logcat）· /tmp/515-fire.mp4（12Mbps 轮）/tmp/515-r3.mp4（4Mbps 轮）· 帧抽取与差分脚本产物（/tmp/515-frames、/tmp/515-r3-k*.png、/tmp/515-r3-triple.png）。仓库内长期归档：无（/tmp 临时；关键结论已入本节）。

## §2 根修设计（2026-10-06）

**根因**：fire 把**正在连续渲染的文档前缀换成新渲染器实例**（fresh parseMarkdown + fresh 组合）——涟漪（布局不等价）与 hitch（换装帧 parse+layout）皆源于此。方向②（压窗）与方向①（渐进冻结防聚集）都是补偿；**消除源 = 冻结片渲染器交接（state handover）**：

- **F3 状态交接（主修）**：fire 时把 pilot 的**现行 StreamingMarkdownState 实例**随 PublishedShards 发布；#g0 StreamChunk 渲染 `Markdown(streamingMarkdownState=该实例)`——同一实例=同一 AST 同一布局节点，冻结区像素**构造性不变**（零 parse、零布局差、涟漪与 hitch 的 parse 分量同时消失）。tail 仍走新 state（既有 fastRefeed 路径）。
- **F2 缓存接线（辅修，回收路径）**：StreamShardContent 的 `remember(text){parseMarkdown}` 接 MarkdownParsedStateCache（#442 A2 注释「后续可接 SyncParseCache」的兑现）——item 回收重组合/冷启时命中缓存，免重复 parse。fire 热路径由 F3 覆盖后此路径仅服务回收。
- **不动**：毕业门槛（2000/4000）维持——复现实测单次 fire 块 ≤~2400ch 有界，「EOF 聚集」非独立缺陷（§1.1）；refeedPacing 无涉。

### §1.4 harness 首轮意外捕获：完结期换名重建塌缩（V1 mock 路径，2026-10-06 18:55）

mockllm eof-fire 语料（[p:line] 快画像）流先于毕业 fire 完结（Arm 后无下一批驱动 Fire）→ 完结期事件链：**card 4058→96（d=-3962）→ u_msg_110db0b2 与旧 turn 条目短暂入场（244ms 双渲染瞬态）→ shard-unreg + 全新 pilot 从零重铺（append total=200 起步）→ 单步 +4024 回涨至 4120**。服务器侧消息落库 id（msg_110db121f…）≠ 乐观直播 id（msg_110db0b2…）——完结 reconcile 走了整树换装而非 #509/#510 的「原地换名」；harness 事件证据 /tmp/515-repro.log 18:55:57.8-59.5。**与 #515 fire 域无关（无 shard fire 行）**，疑似 V1 完结换名路径回归或 mock 线特有形态——立待独立定性（暂记本节，勿混入 #515 判据）。

## §3 根修实施（2026-10-06，F3 状态交接受阻 → 落地 F2 预热+缓存）

**F3（状态交接）受阻事实**：StreamingMarkdownState 为 append-only（无截断 API）；fire 时 state 内容=[0,released) 恒大于冻结前缀 [0,boundary)（overshoot=tail 13-68ch），交接必然双渲染尾部或冻结线中段落（换 seam 破坏零间隙前提）——库约束下不可行，弃。

**落地（F2）**：①`StreamingSplitMachine.previewFirePlan()`——武装边界 Fire 同参预演（与 onBatch 触发分支同函数同参，块逐字节一致）；②pilot `SplitAction.Arm` 分支：预演块文本经 `prewarmShardParse` 后台（Dispatchers.Default）解析入 `MarkdownParsedStateCache`（幂等、fire 先于完成则 miss 回落同步解析——行为不变）；③`StreamShardContent` 接缓存（A2 注释「后续可接 SyncParseCache」兑现；item 回收重组同键命中免重复 parse）+ cached 探针；④`shard arm` 探针（origin/prewarm 规模）。

**验证**：单测 +3（预演=实发逐字节一致×2、预热幂等），全量 `testDevDebugUnitTest --rerun` 绿。真机（dd957af0+本修，mock eof-fire 确定性 harness）：`shard arm origin=2149 prewarmChunks=1` → 146ms 后 `shard fire` → 换装帧 `shardContent len=2149 cached=true`（免同步 parse）→ #g0 全高 34ms → 首帧绘制 64ms——换装链全绿、原子性保持（无塌洞）。

**hitch 余量诚实记录**：换装帧总时长 ~64ms vs 修复前 ~47-60ms——短英文语料 parse 仅 ~5-15ms 非主分量（布局/组合为主）；预热的确定性收益在回收路径、CJK/更大块与慢设备。**涟漪（r3 两帧沉降带）机制仍未钉死**（mock 轮未显著复现，疑语料/推理前缀相关）——残留观察面。

## §4 E2E 发现：mock 路径完结振荡（§1.4 同族细化，新卡登记）

TurnFin（52.629）对齐起 **tail 96↔158 振荡 6 个 d=-62 周期（~1.6s）**：每周期 shard-unreg + pilot held=-1 复位 + 同一 60ch 重灌——完结 reconcile 的条目身份churn（V1 mock 形态：完结消息换 id 走整树换装而非 #509/#510 原地换名）。#g0 冻结片不受扰（cached 重组稳定）。LongCat 轮（r1-r3）无此振荡——mock 特有或结构触发。**与 #515 fire 域无关**；独立缺陷面，已立卡 #523。

## §5 状态

#515 本批：调研定谳（§1 勘误+三事实）+ F2 修复落地验证 + harness 基建（mockllm eof-fire 语料/mockmd provider/确定性配方）。**未达完结**：①涟漪机制待钉死（观察面）；②hitch 余量 ~60ms 是否可感知待用户裁决；③#523 完结振荡独立追。验证门槛：完整 E2E（模拟器+真机）+ 用户验收。

## §6 路线 B 落地（2026-10-06，用户裁决 B+C 并行）

**校准**：GRADUATE_MIN_CHARS 2000→4000。依据（注释内同文）：①#437 S16/S20 实测单容器 ~4000 字每批组合/测量 19-27ms=O(总长) 失衡点；≤4k 单容器每批 ≤~20ms 且滚动期 appends 暂缓（#437 R3）只打静止帧（无可掉动画帧）；②2-4k 档（#515 类）自此流中零 fire——完结走 pilot 即终态+离屏静态分片，类别级消除换装可见件；③4k+ 保留毕业保护，fire 频率减半。单一真相源（machine 默认引用常量；测试全显式传参）。

**E2E（真机，门槛版）**：①eof-fire 语料（2209ch）**零 fire**（全程单容器；TurnFin 时刻 d=-4024 为 #523 mock 完结 reconcile 域，非本修）；②multi-section（~8KB）`shard arm origin=4062 → fire chunks=2（3488+574）`——双块 **cached=true**（F2 预热经 previewFirePlan 正确预测多块）。全量单测绿。

## §7 路线 C 接力计划（涟漪定罪，未开工）

对象：4k+ 文档残余 fire 换装（B 后唯一仍有 fire 的类别）的涟漪（r3 两帧沉降带）+hitch 余量（~布局/组合成本）。步骤：
1. 锚定破雾：gfxinfo framestats（帧完成 ns 时间戳）× VDRAW logcat 关联 × 视频帧三方对齐——此前两轮视觉判读冲突+锚点±0.7s 是定位阻塞点；
2. 复现面：mock multi-section [p:slow]（唯一确定 4k+ fire 源）+ 4Mbps 录屏；
3. 候选面（按嫌疑序）：terminalSuccessSlot 尾随空白跳过在 seam 的间距差（#517 语义只对终末块成立，mid-stream shard 尾随空白是否该跳？）/ MarkdownElement includeSpacer 空白子节点间距 / Turn 条目与 StreamChunk Box 宽度-内边距差 / **442 spec §2 影子态双喂未实现**（换装帧尾块「内容恒等⇒高度恒等」是设计内承诺，A2 简化掉——尾块出生空帧与涟漪的同源候选）；
4. 修法约束：F3 状态交接已证不可行（append-only）；影子态双喂 = spec 原设计回归，先在 harness 量化涟漪量级再定是否值得。
