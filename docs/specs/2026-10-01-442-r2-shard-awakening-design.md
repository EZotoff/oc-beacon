# #442 R2 分片唤醒设计（批次 A2/A3 施工图）

> 状态：A2 装配已落地（2026-10-01，Fire=切尾重建快灌中间态；STREAM_SHARD_PILOT
> dev 开关）；A3 影子态换装（§2 双喂消除重建窗）与 A4 真机判定未落。前置：批次 C
> （flush 深拆+安全网测试，commit 2248a551）+ 批次 A1（毕业计划纯函数+不变量测试
> 8 例，commit c6f1c113）。调研底座：docs/research/2026-09-28-issue442-research.md §5 方案 A。
> A2 落点补充（实施期裁决）：broker 单例按 partId 注册/发布（ScrollQuiescence 先例）；
> 资格=text-leading 流式 turn（首个 renderItem 为 Single-Text——多步 turn 先行
> reasoning/工具卡的文档序与全 turn 粒度插入不兼容，拒绝分片）；完结/回收持续性
> 经 controllerFor 对已发布 part 兜底 + pilot 冷续单帧入树（sliceOrigin 继承时
> 免限速重铺）。

## 0. 目标与判定

- **滑动 p90 19-27ms → ≤12ms**：settle 追平批与流式 append 批的「组合/测量」成本从
  O(总内容) 降为 O(尾块)——机制来源：冻结 chunk item 内容不可变 ⇒ 无失效 ⇒ Compose
  布局缓存命中（零重测）；帽只测尾块。
- **零闪烁（I1′）**：毕业换装帧前后总高恒等（构造性，非补偿）。
- **键稳定**：流式锚键 `t_X` 永不消失（#440 槽位锚/jump 族/帽物主零迁移）。
- 回退：`buildConfigField STREAM_SHARD_PILOT`（dev 先行，beta/stable 关——对齐
  STREAMING_MD_PILOT 先例）；关=现行单容器行为。

## 1. 条目结构（装配层）

流式 turn 的 LazyItems（reverseLayout：index 增=视觉上方=更旧）：

```
… t_X(尾块, 原键) , t_X#g_{N-1} , … , t_X#g_0
```

- **发射顺序**：逆文档序（尾块先入列，#246 定音同款）；`displayEntryStart` 钉回
  g_0（头片含标签栏——完结分段同语义；毕业前单 item 自带标签栏，首毕业帧标签栏
  随头片留在视觉顶部，位置恒定）。
- **键族**：`#g<i>` 与完结 `#c`（旧 MdChunkPlan）/`#s`（TurnSegment）互斥不碰撞；
  冻结 append-only（A1 不变量）⇒ g 键一旦出现永不改写/重排。
- **完结持续性**：完结后 StreamChunk 保留（键不变），尾块同键换终态渲染（#472
  terminal hold 适配切片坐标）；**不迁入 TurnSegmentPlan**（避免键换血——三十八世轮
  候选①教训）。REST 刷新/分页替换按既有指纹失配语义重算。

## 2. 毕业时机与影子态（零闪烁换装核心）

数据层切片方案已否决：`markdown = text.substring(tailFrom)` 在 origin 前进帧是非前缀
变化 → pilot resetKey 整树重建 → 空首帧塌高（#472/#H4 历史教训）——**append-only 墙**
（四十四世轮结论在 part 级同样成立）。零闪烁换装必须内容恒等交换：

1. **武装**：`planStreamingGraduation` 新增冻结量 ≥ `GRADUATE_MIN_CHARS`(2000) → 记
   候选边界 O′，创建**影子态**（新 StreamingMarkdownState，origin=O′）。
2. **双喂**：armed 窗口内 gate 的每批 delta 同时 append 主态与影子态（同串）——影子
   内容自动 = `text[O′, released)`。成本：尾区增量解析 ×2（尾区 ≤ ~4KB，解析毫秒级；
   仅 armed 窗口；滚动期 appends 暂缓 ⇒ **p90 窗口零成本**）。
3. **触发**：影子追平（content.length == released − O′）∧ `ScrollQuiescence.isQuiescent`
   （滚动中不毕业）。
4. **换装帧**（单次重组原子）：
   - entries：`[t_X]` → `[t_X, g_{N-1}…g_0]`（t_X 键不变）；
   - 尾块渲染态换影子实例（**内容恒等** ⇒ 高度恒等）；
   - g 块首组合同步解析（`rememberSyncMarkdownState`，单块 ≤ `GRADUATE_MAX_CHUNK_CHARS`
     (4000) ≈ 2-5ms，quiescent 帧一次性成本，每 ~2000ch 一次）；
   - **帽 reset**（reserved=-1 首帧直通）：帽物主键不变（t_X），但基线必须重立——
     换装后 trueHeight=尾块高，若沿用旧 reserved（整 turn 高）则「帽不回改」使尾块
     永久虚高 h(冻结区) ⇒ 总高超发一次 = 锚上方读位跳变（#470 墙的毕业形态）。
     reset 后首帧直通真高；总高恒等依赖 §3 切割高度恒等。
   - 主态退役（弃引用），影子升主；下一门槛再武装新影子。

## 3. 切割高度恒等（换装帧总高不变的机制前提）

- 切点=空行块边界（A1 `packBlocks`，cut 在 ≥2 连续 \n 的 run 之后）= AST 顶层块边界
  ——与 #258 完结分段同款；完结分片零缝隙实证在先（chunk item 间零间距先例
  ChatMessageList 装配）。
- 假设：**每块 padding 对称**（markdownPadding(block=) 逐块施加）⇒ 块在何 item 内
  渲染总高不变；块尾空行 run 解析器忽略（无幻影块）。
- **A4 验证点**（假设未在流式换装帧实证过）：stream-flicker-test.sh + VDRAW/
  SilentShift + 像素 diff；若失配 → 备选：切点改 AST offset（复用 computeChunkPlan
  的 range 语义）或 item 级间距补偿。

## 4. 喂养管线（pilot 改造）

- 新 `StreamingSplitPilot`（列表级 remember，key=turnKey）：plan / origin / shadow /
  resetKey；毕业判定纯函数化（可测缝：arm/fire 条件单测）。
- `MarkdownContent` 流式分支增 `streamingOverride: StreamingMarkdownState?` 通道
  （外部实例渲染 + heldTail 由 split 状态提供）；现有 pilot 分支在 override 在场时
  让位。
- 切片经 split-state 查表（turnKey+partId → tailFrom），Chunk 条目自带冻结文本
  （字符串不可变，remember(text) 键稳）。

## 5. 帽/账本适配（A3）

- 帽物主=尾块 item（键 t_X 不变——**零迁移**）；`streamingGrowPairing` 留尾块；
  g 块无帽无账（静态内容零增长）。
- 毕业帧帽 reset 见 §2.4；reset 帧与「外部 pending 让位」交互：reset 帧无配对派发
  （总高不变 ⇒ 无 Δ）——flush 相零行为，表征测试补格。
- 锚在 g 块（读历史）+ 增长在尾块：`StreamingAnchorRule` 现行格子 anchor>growth=
  免派发（LazyList 默认锚定保画）——天然正确；#492 遗产「锚越界持帽到底」（免补偿
  构造①）可在此域顺带评估，非本批必改。

## 6. 明确否决/搁置

- **B 案（预留高度表+预测量管线）**：保留为 A 复测不达标的升级路径（#445 载体）。
- **数据层切片**（substring 直喂）：非前缀墙，见 §2。
- **#470 帽回改 B/A 语义裁决**（回缩时同步缩帽+视口跟随 vs 维持空白）：挂起待用户
  裁断，不阻塞 A 线（4h 重度使用零负向事件实证）。

## 7. 实施批次

- **A2**：装配层+喂养管线（Chunk entry 类型/发射/pilot 改造/streamingOverride 通道/
  STREAM_SHARD_PILOT 开关）——纯接线，换装帧先以「不等高但键稳」中间态联调（dev
  开关后真机观察），后接 A3。
- **A3**：影子态换装+帽 reset+完结持续性（零闪烁收口）+表征测试补格。
- **A4**：真机调优与判定（gfxinfo 滑动 p90 ≤12ms、VDRAW 泄漏=0、CONTENT-BLINK=0、
  SilentShift 零毕业帧滑移；#492 检测网在役）。
