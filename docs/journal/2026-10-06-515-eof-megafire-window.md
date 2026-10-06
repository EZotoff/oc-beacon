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

## §8 路线 C 定罪（2026-10-06，涟漪证伪）

**方法**：mock multi-section（8.4KB，双 fire 4062/2块+8423/4块，全 cached=true）+ 4Mbps 录屏 99fps；**互相关锚定**（269 条 MDResize 墙钟 × 192 个视频变化帧扫 W0 匹配率最大化）替代拍脑袋 t0——锚定 W0=20:26:39.4（残留 ±0.4s 混叠容差）。

**结果**：①全视频最大像素变化帧（d=17-19）均不在任一 fire 时刻；②两换装帧邻域变化（d=10-13）与普通流式增长步（每 ~600ms 一次、d=10-13）**同级不可分**——换装帧无独立可见异常；③r3「涟漪带」重新归因：尾部每步增长 ~66px 上移产生的行带差分（正常流式位移），叠加一次换装帧 seam 带变化——非渲染器交换闪。

**C 终局结论**：确定性 harness 上 4k+ 残余 fire 的换装**无可复现可见缺陷**（像素差低于正常增长包络；hitch 为孤立一帧 ~40-64ms，120Hz 下单帧超限）。442 spec §2 影子态双喂（尾块出生空帧）在现有壳+帽覆盖下无可见缝隙——按证据**不需要回归实现**。#515 可见性面就此收口；残余可见件唯一在册项 = mock 完结 reconcile 塌缩（#523，独立域）。

**判读纪律沉淀**：单帧级视觉定罪必须先互相关锚定（增长节奏 × 变化帧匹配），±0.5s 拍估 t0 在 10ms 帧距下不可用；「异常帧」判定基准=同视频正常增长步的 d 分布（本例 10-13），非绝对阈值。

## §9 系统性 E2E 矩阵（2026-10-06，用户令：分支 case+竞态+拟人全覆盖）

**设计**：分支矩阵自代码导出——毕业路径（P1 <4k 零 fire/P2 跨 4k 流中 fire/P3 无空行永不/P4 围栏驻尾）、预热路径（W1 命中/W2 竞速回落/W4 多块）、竞态面（R2 完结竞 fire/R5 断流/R6 突发/R7 停顿）、拟人（H1-H3 乱滑乱点）。21 轮真机（13 语料×normal + multi-section×6 画像竞态 + 2 乱操作风暴）。脚本 /tmp/515-matrix.py（会话链自动化：新会话→root→picker→mock-md→指令；历史指令阴影规避=每轮新会话）。

**总判定：零崩溃、零 ANR、零 RESETKEY、fire 全 cached（跨画像含乱操作风暴）**。FAIL 标签 5 处全部为期望表误差（wide-table 实 ~4.2k 应 1 fire、multi-section 实 ~13k 应 3 fire——语料规模低估，产品行为全部正确：origin 4062/8423/12741 门槛步进精确）。

**关键通过面**：①eof-fire/giant-single（无空行 4k 单段）零 fire ✓P1/P3；②fast 画像竞速预热仍全命中（12/0）✓W2；③stall-mid 停顿后 fire 正常 ✓R7；④abort-mid 断流截断塌缩属预期族 ✓R5；⑤**乱操作风暴**（随机滑/点 40-55s）期间 3 fire 全部正确触发+cached，TurnFin 干净，无滚动撕裂签名——拟人鲁棒性过关 ✓H1-H3。

**异常候选（专项取证中，§10 补）**：①multi-section×line：arm=0 零 fire+**-20646 全轮塌缩**（#523 最大形态）；②wide-table -128×3 小负向（表族）；③long-paragraphs -2116×2（疑 #523 间歇）；④code-long 围栏全程驻尾不 arm（#516 语义自洽，165s 窗口未完结）。

## §10 异常候选取证收口（2026-10-06）

三案定向重跑（全量 logcat 留证 /tmp/515-forensics-{line,wide,longpara}.log）：
1. **line 画像 arm=0/-20646：非产品缺陷**——取证轮正常 arm/fire（origin=4922 chunks=2、9571 chunks=4；line 节奏下 flush 批粒度使 arm 边界后移至 4922，语义正确）；矩阵轮系会话装置变体。-20646 为 #523 完结塌缩最大形态（13k 文档）。
2. **wide-table -128×3：#523 族 fire 后变体**——fire(4191) 后 tail 224↔96 振荡两次（同值重复，churn 同签名），表族触发、fire 后即刻、与完结无关。原生日志 21:39:25.950/26.066。
3. **long-paragraphs -2116×2：#523 完结塌缩**——tail 正常长至 1303ch/2212px 后 TurnFin 时刻整段拆到 96（完结换名走整树换装）。

**§9/§10 总结论：#515 修复面（门槛 4000+预热+缓存）在 21 轮矩阵+乱操作风暴+竞态画像下零缺陷**（零崩溃/零 RESETKEY/零 ANR/fire 门槛步进精确/全 cached）；全部残余异常（完结塌缩·fire 后 tail 振荡·间歇性）归属 #523 churn 族——其触发面比立卡时认知更广（非仅 mock/完结：含 fire 后变体、多语料间歇）。#523 卡已更新扩展定性。

## §11 全量补测（2026-10-06 晚，用户裁决：全部补齐含 soak）

**真模型 V1 腿**（LongCat zen + deepseek-chat ×8 轮，模型选择带 dump 验证）：零崩溃/零 RESETKEY；**#523 实锤非 mock 独占**——LongCat 轮 TurnFin 时刻 2736→233（-2503）、deepseek 轮 -2732/-326，间歇 2/4；且 18:55 轮（B 之前构建）已存在=非本批回归。V1 真模型 fire 未练成：模型不遵长度指令（1975/223 字档）——如实登记（fire 路径由 mock 矩阵 15+ 次与 V2 腿覆盖）。

**V2 腿**（4201 无认证直连 deepseek）：**真模型 fire 达成**——doc 9.1k，`arm origin=9088 prewarmChunks=4` → `fire chunks=4` → 全 cached=true（含 #523 churn 期重组合）。**#523 在 V2 复现**（TurnFin 后 -3001/-2675/-3001 循环塌缩）——与 10-05 定罪第三例同源互证。

**DSH 腿**（dsh-pw-demo@8181，#521 runbook 配方：无 debug_username）：流式+完结健康（TurnFin t_seq-session/u_dsh-sys 信封键族）、零 fire（2.4k<4000 正确）、**零负向 d**——DSH 完结不走 #523 换名 reconcile（正差 +2055=完结批揭示非塌缩）。**意外事件**：首轮发送后 app 内容区空白（进程活/主线程 idle 非冻结/窗口聚焦）——force-stop 即愈，全量证据存 /tmp/515-dsh-blank.log（22820 行）待析；与 DSH send 的因果未定（不排除 MENU 保活键扰动）。

**beta 腿**：BetaDebug 包安装+冷启成功，但 debug intent extras 不路由（落连接屏）——按身份覆盖处理（STREAM_SHARD_PILOT/DELTA_BUS 与 dev 同值同码，dev 已全验证），intent 路由差异登记待查小项。

**基建发现**：①mock abort-mid 触发 opencode V1 **服务端无限自动重试**（abort→error→retry，1h+ 每 16s 一次 call 213-273）——已杀循环并把 abort-mid 移出 soak 随机池（指令直达保留）；②矩阵脚本曾因陈旧 a11y dump 假选模（real2/3 轮被 mock 污染已识别，选择加验证后修复）；③真模型长文指令遵从差（600 字/节→1975 字）。

**隔夜 soak 已上线**（22:52 起 10h，pid 798519）：mockmd 全语料轮转（eof-fire 含）×画像（abort-mid 已移出）×人类行为模型（随机滑动/阅读/中断/探索），driver-515.py（mDNS serial+4101 接线副本）。晨检：human-sim-10h/reports/driver.log 台账 + review.sh 巡检。
