# closure-audit（2026-09-29）

> 状态：已收口（17 卡关闭+#477 登记；待用户裁决 3 项：#424 关账、#465+#464 合并、#445 并入 #442）
> 关联：（spec 路径，若有）·（issue 编号，若有）
> 来源：用户反馈 / grilling / E2E / 顺带发现

<!-- 过程中的取证/验证证据直接写本文件；backlog.md 只留 ≤3 行卡片。 -->

## 已完结卡片迁入（2026-09-29）

### **#462 多卡展开态收起其一仍导致用户视角高度变化** `scroll`
  - 用户报告(2026-09-29):展开≥2 张内容卡后收起其中一张,视口锚定仍漂移;要求根因分析+根修(收起配对在多卡展开态的语义缺口)
  - 2026-09-29 根修交付:根因=收起用「本卡展开前绝对快照」恢复视口(requestScrollToItemNoCancel(episodeAnchorItem/Offset))——仅在集间无其他位移时正确;多卡展开态 V=Pa+H_A+H_B,收起 A 绝对恢复 Pa 偏差 −H_B(视口多退 B 的展开量)=「收起其一视角跳变」。数学定罪+既有真机日志证实模型(展开配对后 fiso+=H、快照恢复=−H,#427 终局日志 4103↔3909)。修:resolveCollapseAnchor 纯函数(增量镜像语义:目标=当前视口−episodeShiftConsumedPx,单卡与旧绝对恢复数学同值,多卡保其他卡净位移;跨界经可见 item 高链折算,链不足返 null)——收起路径接线(绝对快照退役,取消路径 cancel-anchor-restore 保留原撤销语义不动);anchorKnown=false 回退既有 dispatch 镜像。TDD:ResolveCollapseAnchorTest 5 例(零消费不变/单卡镜像/多卡保他卡/跨界折算/链不足 null)+全量单测绿。真机复验(冷启流程,双开思考卡→收起其一,像素对比):修复前同流程 rows400-600 有 24732px 内容位移(视口跳变),修复后中段内容带(200-2200)完全静止、仅收起卡自身局部变化 794px——收起不再拖动视口。
  - 迁入依据：用户验收通过(2026-09-29 ok)——多卡展开态收起其一视口不再跳变(增量镜像锚定)（backlog.sh migrate 2026-09-29）

### **#455 统计栏视觉微调：间距对齐 user 侧 + agent 徽标边框化** `uiux` `chat`
  - assistant 正文→统计栏间距原 SM 8dp（compact XS 4dp），user 气泡外置统计栏 4dp（compact 2dp）——两侧不一致（2026-09-27 用户报告）
  - 修复：MessageSectionScaffold 尾部间距独立 tailGap（4dp/2dp 与 UserBubbleExternalActions 严格一致），正文内部 parts 间距解耦不动
  - AgentTag 去实底背景改 1dp 边框（tagColor@MUTED + 同色文字）——扁平消息层下实底徽标不协调（用户裁决）
  - 扩展(2026-09-27 二轮)：①卡片族容器垂直 padding 2→0dp(ToolCardScaffold+ReasoningBlock)——透明卡无背景,padding 只叠加卡间节奏,像素定罪修改前卡间空隙 24/38.5/37.5/28dp 两档混杂(#422 中断轮平铺豁免首次暴露),归零后图标列实测 25.1-25.5dp 完全统一
  - ②统计栏行高：assistant 信息簇 Row heightIn(min=28dp) 对齐 user 侧外置统计栏 28dp 图标命中区(#419 定规不可降)
  - 保持：turn 边界/消息边界(16dp)为结构语义层级不压平;Shell 两行卡行尾时长整行居中(设计)
  - 迁入依据：用户验收通过(2026-09-29 ok)——统计栏间距/徽标边框/容器 padding 归零（backlog.sh migrate 2026-09-29）

### **#422 step 自动折叠:turn 内非最后 step 折叠为计数行(DSH 同款时机)** `ui` `chat`
  - 用户裁决(2026-09-20):每 turn 最后 step(最终回答)恒展开,之前 step 自动折叠计数行;流式恒平铺,完结生效
  - 关键发现:StepStart/StepFinish 在 UI 过滤层(RenderableTurn.kt:193 filterRenderableParts)被丢弃——第一步=装配层保留边界标记
  - 方案+调研:docs/research/2026-09-20-code-step-grouping.md;影响面:RenderableTurn/ChunkAssistantItems/折叠组件/i18n(复用统计词);#420:整组单 LazyItem 不拆
  - 三轮收口(2026-09-21 凌晨):
  - 1) CardExpandGeometryNode(ModifierNodeElement+LayoutModifierNode)落地:tween 窗口内复用 settle 末真测 placeable(逐帧只重算 report=f·H,子树零重测);窗口外恒真测保内容失效传播;epoch 变化清缓存双保险。展开前 settleUntilContentStable(2 帧判稳/600ms 上限)吸收表格 containerWidth 两拍收敛与 asyncParse 迟到;episode 末 epoch 强制真测+迟到增量 δ 补偿。单测+5(时钟新语义)。
  - 2) 真机(小米14)验证:重型 turn(10193 字符双表格,H=20292px)10s 冻结 stall 消除——settle 即刻判稳、tween 全程 H 恒定(缓存命中)、收展均为钳制限速平滑动画(展开总 ~2.6s:首测 ~1s 内容固有组合成本+渐进揭示;普通卡片远快于此)。多模态走查:四元素顺序排列无叠压。
  - 3) 顺带修复两处折叠组渲染 bug:a) #258 Stage A MdChunkPlan 对多消息轮次的巨型 part 分片会绕过 StepGroup(Chunk 条目按 part 直渲染,折叠行+末消息内容双丢失)——产侧协调器+装配侧 buildChatEntries 双端封堵,巨型末消息归 Stage B;b) StepGroupCard 展开体缺 Column 包裹(ChunkAssistantItems 为裸 for)致全部 part 堆叠 (0,0) 互相叠压——补 Column(XS 间距,同 Segmented 路径)。
  - 4) DSH 折叠时机核实:研究档案 §1.3 定案 DSH=turnClosed 后折叠(流式恒平铺)——与现有实现(装配层 turn 完结重组+渲染层 isStreaming 平铺)一致,无需改动。
  - 5) code-review 双轴:Spec 6 项全忠实;Standards 8 条修 5(谓词抽取/术语/类KDoc/import序),余 3 低severity(settle 帧循环单测缺口留待)。
  - 待用户验收:视觉终判(尤其巨型卡展开的 2.6s 钳制限速揭示节奏是否可接受——若嫌慢可裁决 MAX_FRAME_DELTA_PX 按 H 自适应放宽)。
  - 四轮收口(2026-09-21 01:15,懒加载实施):
  - 1) 用户报「点了卡死」+ 实测取证:历史大组展开 = 单 LazyItem 一帧组合全部内容(10193 字符双表格,H=20292px,400+ SelectableText 单元格)→ Choreographer Skipped 434 帧 = 3.6s 冻结,MIUIScout 栈顶 MultiParagraphLayoutCache(文本断行)。首轮 Node 缓存只消了逐帧重测风暴,首组合一次性成本仍在。
  - 2) 用户裁决:仅历史展开路径懒加载(流式输出路径零改动——实测流式最差 37 帧微跳,健康);大组直出+淡入,小组(<3 屏)保留动画。
  - 3) 实施(14c3aa01+da5833d0):LARGE_STEP_GROUP_WEIGHT=6000 阈值;展开态大组拆条目发射——尾 Turn(skipStepGroupItem)+StepGroupBody×N(权重 2200 切片,独立 LazyItem,220ms 淡入)+StepGroupHead(共享 StepGroupFoldRow);键序号=文档序、发射逆序(#246 语义);displayEntryStart 钉头部;流式恒不拆(单点门控+单测);stepGroupStateKey() 前缀收口。
  - 4) 真机验证:同重型组冷展开 434 帧→0 帧冻结(全程仅 1 次 37 帧级微跳);折叠行→内容→末消息+统计栏结构正确;收起即时;小组动画保留(713ms);流式冒烟正常;单测 +3(切片/发射序/流式豁免),全量套件过;双轴 review:Spec 10/10 忠实。
  - 已知权衡(用户已裁决接受):大组收起为硬切无动画;二次展开无淡入(rememberSaveable 残留);流式防护单点在 buildChatEntries。
  - 待用户真机验收。
  - 五轮收口(2026-09-21 01:45,用户验收发现的第三层根因):
  - 1) 用户真机验收:点折叠行两次后卡死(logcat 证:两次 ~2.5s 主线程阻塞,栈顶 SimpleMarkdownTable placement;插桩复现:Skipped 440 帧)。
  - 2) 插桩定音(已撤):懒加载框架完全生效——entries 拆分成功、仅组合视口内 2 个 body、组合仅 21ms;冻结在 body 组合之后的测量阶段。
  - 3) 第三层根因:sliceStepGroupBodies 按 part 边界切片,而真实场景大组常为**单个巨型 text part**(60 行表格=10193 字符一个 part)——单 part 不可分,一个 body 条目仍装整表,LazyList 测量该条目时 360 单元格全量断行=3.6s。多层 part 的大组已验证有效(组合快、零跳帧),单 part 巨物未解。
  - 4) 已交付有效的部分(14c3aa01+da5833d0):多 part 大组懒加载+小组动画保留+单测;另发现折叠行可点击区域仅文字宽度(fillMaxWidth 未生效于触摸区)的存量 bug——用户中排点击无反应即此,待修。
  - 5) 下一步方案(待实施):body 内巨型 text part 复用 #258 computeChunkPlan 做 AST 块级切片(协调器预解析→区间条目),即 Stage B Giant 段机制接入展开态拆分;折叠行触摸区修复(Row 可点击区域全宽化)。
  - 教训:本轮『零跳帧验证』实际测的是一次未命中的点击(坐标又落在行边)——仪器验证必须先确认动作确实生效再读数。
  - 6) 2026-09-21 内容漂移(diagnosing-bugs 全程)——已修复 ede8ac05
  - - 症状:小组(动画路径)一个展开+收起循环视口净漂 -366px,展开末折叠行 934→836,收起后整个列表上移(uiautomator 三 dump 逐行对账;截图证实标题栏不动=非滚动错觉)
  - - 根因:#420 δ 配对是开环账本,只记指令不记实际消费。两类误差:(a) dispatchRawDelta 列表边缘残量(逐帧 residual 8+17+35+12+15+11=98px,展开末一次性显形);(b) LazyList 锚点翻转会计误差(收起过程 -268px,账本完全无感知)
  - - 修复:CardExpandReveal reveal 盒顶缘(=折叠行底缘)onGloballyPositioned 实测窗口 Y;episode 正常完成后 episodeEndCorrection(纯函数,5 单测)判定偏差,单次 dispatchRawDelta 修正回本集起点;用户滚动取消/协程取消(反向 toggle)跳过——阅读位置优先权铁律
  - - 真机验证(小米14,同测试位):展开 err=98 consumed=98(钉回 934);收起 err=268 consumed=268;循环后 dump 与点击前逐字节一致,净漂 0px;连测 2 循环守恒;PSNR 首尾帧 32dB(同布局)。证据:docs/acceptance/2026-09-21-422-evidence/drift-fixed-cycle.mp4
  - - 遗留:大组(硬切换条目路径)无任何锚定——展开时折叠行飞出屏(插入高度无补偿),收起时锚点条目被删视口任意落位;与 L3(单巨型 part 测量冻结)同批处理。展开态折叠行 a11y 可见高度 6px(与 reveal 盒 6px 重叠,疑 #231 clip 链)顺带记录
  - 7) 2026-09-21 渲染前反馈闭环重写(用户裁决弃事后补偿)——899d74a2
  - - 用户观看压测后裁决:ede8ac05 的 episode 末补偿"先漂再拽回"不可接受;要求渲染前完成计算(#420 同帧配对严格化)
  - - 逐帧插桩([DEBUG-425] topY/anchor/fii/fiso/rep/abs)定位三类断点:①组合滞后残量永久丢失(指令账本只记指令);②LazyList 锚点翻转会计误差(消费满额但视觉说谎,收起 −60→−328 阶跃);③收起中段 anchor 稳定时 dev=0 证明配对数学本身正确
  - - 新机制:每帧指令 = 缓动增量 + (锚−上帧实测Y) 死拍反馈;吸收账本观测化;循环收敛条件 = 缓动走完且指令≈0
  - - 四个真机迭代坑(全部插桩实证后修复):死锁(上报做消费奴隶→dispatch 恒 0,改乐观上报)、双计振荡((目标−已吸收)+偏差 极限环 ±154 交替,改增量+偏差)、崩溃(fraction→0 后缓存 placeable 放置 detached 节点,关窗口+守卫)、连点链式漂移(重定向取消以漂后位置起新锚 −73px,carriedAnchor 携带)
  - - 压测(用户令系统性设计):A1 十循环 934 守恒;A2 24 连点@300ms / A3 30 连点@150ms 风暴后 934 精确守恒;1244 风暴帧 17 帧(1.4%)瞬时偏差≤2 帧自愈;63 episode 仅 1 次 end-restore 82px;无 ANR/崩溃;JVM 竞态单测 28 个
  - - 证据:docs/acceptance/2026-09-21-422-evidence/drift-fixed-tap-storm.mp4
  - - 注:风暴中偶发单次点击丢失(input 投递层面,非状态机;恢复点击均正常翻转);大组硬切路径漂移与 L3 冻结仍在案(与本卡分批)
  - 8) 2026-09-21 震荡根治·两阶段架构(用户两轮否决后)——a7e7d2bd
  - - 用户裁决链:ede8ac05 事后补偿=否("先漂再拽回");899d74a2 逐帧反馈=否("来回震荡")
  - - 取证定案(录屏条带追踪±24-136px + [DEBUG-425] 逐帧):①展开方向 LazyList 布局多 pass 不稳定(dispatch 时刻 topY 逐 pass 振荡±60px)——动画期间布局/滚动参与即震荡;②逐帧实测反馈的信号(布局坐标)对滚动位移盲且滞后,本身成为扰动源;③自然锚定证伪(禁 dispatch 折叠行飞出屏 2852px,dispatch 必需);④收起方向布局稳定(consumed==d 逐帧,topY 恒定)
  - - 终局架构:展开=A 阶段一次性布局落位(内容不可见,稳定窗全额重试)+B 阶段纯绘制揭示(drawWithContent clipRect,零布局零滚动);收起=原逐帧路径;指令=目标−已吸收账本
  - - 验证:展开/收起终态 934 精确;5 循环+12 连点守恒;零 end-restore;无崩溃。余量:展开 A 阶段一次 ~130px 单向瞬时沉降(无往复);备选=DSH 式硬切无动画(结构完美,待用户裁决)
  - - 工具教训:uiautomator dump 对被裁节点报可见高度(6px 假象);positionInRoot 对滚动 draw-offset 盲——测量指标必须与像素级录屏条带交叉验证;screenrecord 变帧率使帧号≠墙钟
  - 六轮收口(2026-09-21 午后,空白卡死追修):
  - - 1) 用户报「为啥会这样」:折叠行下方 ~1200px 空白、内容全消。取证:fraction=1(布局占位2852)+drawFraction=0(内容隐形)的卡死态;恢复实验(再点一次)内容即回,H 恒在。
  - - 2) 根因:cancel-on-scroll 处理器(LaunchedEffect(listState),不随 visible 重启)闭包捕获**过期 visible**——收起期首次组合的实例在展开后遇用户滚动,snap(0f) 把 fraction 打 0(内容离树、H=0),收尾 vt 循环又把 fraction 拉回 1,终态「占位+隐形」。日志铁证:cancel 后 rep/abs/H 全 0。
  - - 3) 修复:①rememberUpdatedState(visible) 读当下值;②finally 不变量「fraction>0 ⇒ drawFraction=1」兜底一切退出路径。
  - - 4) 击杀链复现验证(修复后):展开→淡入中 fling→cancel-on-scroll 命中(snap f=1.000)→post-cancel 帧恒 rep=2852/H=2852(旧版此处归 0)→滚回 T1 内容完整可见(7.6s 行+18 行表格标题俱在),无任何空白。
  - - 5) 教训:长生命周期 effect 闭包捕获可变参数必须 rememberUpdatedState;「占位必显示」应为组件级不变量(纯绘制分数只能由正常动画路径收敛,退出路径须强制归位)。
  - 七轮收口（release 首装冒烟，2026-09-21）：用户要求换 release 版体验（debug 卡顿）。跨签名切换（pinnedDebug→release.jks）卸载重装 devRelease 后，adb tap 在折叠行上 8/8 无效 → 一度误判"release 上 toggle 失效"。插桩版复测 6/6 全对（click→toggle→episode 展开 f=1.000 620ms/收起 f=0.000 385ms，同帧配对 2852/2852 在 release 同样成立），还原重建干净版复测：tap1 miss、tap2 正常。真相：(a) 已知 tap flakiness（折叠行触摸目标窄）在 release 依旧 ~50%，非回归；(b) 自制检测器 isexp.py 被 6px sliver 命中 fold934 正则 → 永远报 NOT_EXPANDED，放大成幻影 bug——sliver 陷阱复发，检测器必须用"展开内容标志行 presence + 折叠行全高"双条件；(c) strip 跟踪器在 miss tap 的整行按压高亮/收起过渡态上会产出 d≈11 弱匹配的假位移（-116px/-84px 假 RED），判定必须加 d 阈值（<5 才可信）并以组上方参考条带为验收主判据。release 像素级验证：上方参考条带全程 +0px 零 UNMATCHED（收起+展开双 episode 213 帧）。
  - 八轮收口（release 体验两 bug + 幻影哑火，2026-09-21，22f25b53）：用户报①大组展开加载延迟高②思考卡等小卡展开"闪烁跳一下像补偿"。插桩 release 三点取证定音：(1) 幻影哑火根因=StepGroupFoldRow 把 !expanded 当 defaultExpanded 传——委托语义 !(map[id]?:default) 使收起态首点写 false=静默无效,每组每冷启首点必哑(观感即"点了没反应/加载慢"),改传 expanded;(2) 小卡顿挫=两阶段设计在小卡呈四拍(摘要瞬消→+146px 刚性跳留空白一拍→内容瞬现→底纹渐隐,逐帧视觉取证),≤600px 改逐帧几何 tween+同帧增量配对(drain 基准 lastMeasuredH→lastReportedH 两路径统一)+alpha 随 fraction 淡入;(3) 大组冻结=60 行表格单体 part(h=20226px)首组合 598ms 屏幕零反馈,StepGroupBody 重组合延后一帧,首帧画 loading 占位。验证(真机 release,22f25b53):T1/大组冷启首点均即展开;思考卡墨水轨迹 4-5 帧渐变无凹陷无闪烁,上方条带 +0;大组展开涟漪→占位可见 0.7s(冻结分段)→内容流入;T1 两阶段守恒 ref -2px。教训:检测器 sliver 陷阱二次复发后,状态判定一律用"展开内容标志行 presence+折叠行全高"双条件;条带跟踪器在按压高亮/内容替换窗的弱匹配(d≥11)产假位移,验收主判据=组上方参考条带。L3(AST 级切片根治 598ms 冻结)仍待做,占位是体验缓解。
  - 九轮收口（浅灰 pill 定性，2026-09-21）：八轮收口后视觉终审在录屏 f0020+ 报「展开态标题行残留浅灰 pill(x237-1024,灰226)」。数值复核定案为视频编码伪影：pill 区域在 mp4 帧内 95-98% 均匀 226 且无形状结构，而同状态无损 screencap PNG 该区域 98% 为真背景 249、灰带仅 0.2%；折叠/展开/滚走回/再收起四态 PNG 扫描均无任何背景块（先前扫到的 215-245 灰带实为 FAINT 半透明摘要文字墨水及其淡出过程）。结论：应用渲染干净，无残留样式，非 22f25b53 回归；教训入档：录屏取证对「平坦背景上的低对比(~8%)色块」结论必须用无损截图交叉验证——H.264 在邻近运动内容旁会把背景量化漂移 20+ 灰阶且长时间不收敛。
  - 十轮收口（用户再报震荡/上顶，2026-09-21，fbc91c21+a98e5b76）：release 验收再报①过程展开内容上顶②其他卡片先震荡后展开。插桩三轮定案:②根因=八轮收口的清理事故——git checkout 回滚插桩时连带吞掉了 drain 配对基准修改(lastMeasuredH→lastReportedH),该编辑从未随 22f25b53 发布!逐帧路径首拍在布局未增长时全额 dispatch H(f=0.004 即推 146px),账本实测 +146,+146,-146,+146,-146(fii 8↔7)=震荡,战后缓动走完=「先震荡然后展开」;另有 else 悬挂(小卡分支后 A/B 代码无条件坠落=内容闪灭再淡入)同期未发布。两者重应用(fbc91c21)。①大组上顶=权重≥6000 硬切换无配对(已知#4),锚定尝试五轮(scrollToItem 偏移/反馈环/键匹配/两步跳拉)均因反向布局滚动语义落错位——实测视口被甩到会话底端比不锚更糟,已撤(a98e5b76),回归 #4 随 L3 以 layoutInfo 键匹配重做;教训:反向布局滚动语义必须先写校准单测再上真机。验证:小卡贴底武装态墨水 75→89 单调无凹陷无闪烁;T1 守恒 ref -2px;单测全绿。流程教训入档:清理插桩禁止整文件 checkout——必须按 diff 块回退,发布前 grep 目标修改是否在树(本次两处修复双双被吞,验证却因靶位盲区+视觉终审窗口未覆盖闪灭拍而放行)。
  - 十一轮收口（小卡位置漂移+回跳，2026-09-21，7dbb4189）：用户 subagent 点击思考卡实测「内容往上推,动画结束卡片突然跳回原位」——违反卡片视口位置不变量。图标条(∞图标区域,不受摘要淡化污染的位置特征)轨迹实测复现:动画期卡片被推 0→-8→-40→-110→-194px,episode 末 end-restore 一次性跳回 0。根因=十轮收口上线的逐帧配对路径:drainPhaseA 首拍 pending<1(f=0.004·H≈0.6px)即执行「phaseADrain=false」解除排干武装,后续每帧增长全部无配对,推力逐帧累积;end-restore 在 episode 末一次性纠偏=可见回跳。架构裁决:撤逐帧路径,小卡回归两阶段(一次落地+同帧全额配对,位置钉死);空白拍(四拍顿挫残余)改由 PHASE_B_JUMP_START=0.3 消除——A 落地帧排干完成时(pre-draw 同帧)drawFraction 直接跳 0.3,B 从 0.3 续坡至 1,高度与首批内容同帧出现,无空白无瞬现;展开向闭环尾循环(零指令空转)一并跳过。验证:展开/收起双向图标条全程 dy=0(位置不变量成立),墨水无凹陷无闪烁,落地帧起内容即部分可见;单测全绿。方法论入档:卡片位置不变量的判定特征必须选「不随动画变化的稳定像素」(∞图标条),摘要淡化区/高亮区会让模板匹配器跟丢或误报;逐帧布局参与反向列表=漂移与震荡二象性,两阶段一次落地+同帧配对是唯一被反复验证稳定的位置钉死方案。
  - 2026-09-28 用户裁决:彻底清理 step 折叠机制(重构/完全重写也在所不惜)——定罪链:多 step 轮完结塌缩 10/10(桩帧 24dp,账本 ledgerTotal=0 失守);#430 后折叠交互已退役,残留=双分支互换+死代码族。方案 docs/specs/2026-09-28-422-stepgroup-fold-removal-design.md(统一渲染树+死代码扫除);合入后用户接入 codebase-memory-mcp 复扫残留
  - 迁入依据：用户裁决清理完毕即可——StepGroup 折叠机制彻底清理已合入(#474 统一渲染树+死代码扫除 433a3d1c/acf7d4ba,10 轮 POST 完结塌缩 10/10→0/10,grep 折叠符号清零)（backlog.sh migrate 2026-09-29）

### **#421 消息流全量单行形态(DSH化):思考/工具/通知卡去容器** `ui` `chat`
  - 已实施完成(commit 5022b81a..7851eee2):ToolCardScaffold 透明收口16卡/ReasoningBlock去容器去色条+∞图标+尾部摘要/展开左竖线/通知四类+三横幅透明化;豁免:统计栏/问题/权限/错误行
  - 调研:docs/research/2026-09-20-single-line-cards/(B视觉/C业界/D改造地图);验证:容器色块像素消失+多模态审查成型+全量单测绿;#420契约零改动
  - 待用户真机观感验收
  - 迁入依据：用户裁决已被后续相关内容代替,直接关闭——单行形态落地后随 #422 统一渲染树体系演进（backlog.sh migrate 2026-09-29）

### **#427 步组大内容切片+预测量+窗口化组合(成本根因修复)** `perf` `render`
  - 组合/测量成本与内容总高成正比→改为与可视窗口成正比:纯函数切片器+宽度键控高度账本+窗口化宿主;引擎契约不变,小组零改动
  - 四阶段交付(P1切片→P2账本→P3窗口→P4可选表级);收编#424/#425动机;#426死代码随切片器转正清理
  - → docs/specs/2026-09-23-427-step-group-slicing-windowing.md
  - P1-P3 已实现并真机取证(f2bcb12e/a1b3ce8f/3759317a)：切片器+片高账本+窗口化宿主，引擎契约零改动
  - 实测：暖展开269ms/收起772ms(20k单体片)/冷展开3118ms/小组227-249ms；铁律DRAW探针topY=644逐帧恒定
  - 差距：多片窗切换无真机数据(造数失败,免费模型连败)；P3b预热错峰未做；P4未立项——详见 journal 2026-09-23 节
  - 迁入依据：用户裁决后续决策已改此点,关闭——L1-v2 虚拟化 2026-09-24 回退,v4 首组合恒分批成终态,原方案作废（backlog.sh migrate 2026-09-29）

### **#435 #433 高度引擎统一化——流式增长/COMP-MSG/GUARD整合进引擎配对体系(用户裁决)** `chat-ui,architecture`
  - 用户裁决:所有高度相关处理统一到高度引擎。现状断层:48ms批流式增长直接改布局绕过引擎→GUARD事后拉回(snapshotFlow版一帧滞后,已作缓解层装机);COMP-MSG补偿(ChatMessageList layout{}注入)与steady配对(引擎dispatchRawDelta)两套并行。目标:流式item增长纳入steady同构配对(dispatchRawDelta同帧,与卡片增长一致),COMP-MSG/GUARD/流式锚定并入PreRenderCoordinator flush体系,单一视口权威。依赖:真机活跃流式窗口验证(本轮两次16s录屏窗口agent均空闲,需用户配合制造流式)。
  - spec 定稿:docs/specs/2026-09-25-435-height-engine-unification-design.md——统一配对规则 pair(Δ)⟺anchor==S∧fiso>0(锚即意图:贴底跟随族/读历史一律免派发,尾段阅读同帧+Δ配对);通道drain无豁免/引擎steady无豁免/stream-instant三层同批封堵;PreRenderShiftChannel+DeferredRevealCompensator+shouldCompensate机器退役
  - 实现+单测+真机核心判决(零GUARD/14次贴底免派发/无锯齿)完成,残余两构型单测已锁待用户真机复核;spec+三篇调研+journal 齐备
  - 终判(12:22):三构型真机全绿——贴底33条免派发零GUARD/尾段14条pair全额消费fiso精确推进/读历史零派发视口冻结;证据链完整(单测16+录屏+logcat),待用户验收
  - 迁入依据：用户委托验收(引擎层你来把控):原三构型真机终判全绿+引擎承载的 #462/#471②/#474/#476 顺次真机全绿+2026-09-29 串行全量单测 BUILD SUCCESSFUL 新鲜证据——验收关闭（backlog.sh migrate 2026-09-29）

### **#447 opencode server 2.0.16+ 移除 /api/health 导致 app V2 探测永久失效** `network` `compat`
  - 2.0.16+ 实测移除 GET /api/health(鉴权通过仍 404)→ApiVersionDetector V2 探测只认该端点返回 null；tryV1 探 /global/health 收 SPA HTML 被 content-type 防御拦截→双探皆空 UNKNOWN
  - checkHealth 按 #132 语义 UNKNOWN 保留原值→真机持久化的 V1 永不被纠正→V1 请求 /project 等收 HTML 200→SSE parseEvent 抛异常→重连退避→『服务器已断开+目录为空』
  - 取证:handoff-oc-beacon-card-intervention.md §10.1(2026-09-27);可观测性缺口:V2 探测非 2xx 静默 return null(ApiVersionDetector L99)且新进程启动未发探测请求,修复需先补日志
  - 服务器侧已收口(2026-09-27):本机 opencode systemd 用户服务化(linger 开机自启)——opencode-v1@4199(~/oc-v1-env 隔离,无密码仅 127.0.0.1)/opencode-v2@4096(真实环境,Basic Auth 经 OPENCODE_SERVER_PASSWORD 固定为 service.json 密码;官方机制=不设则每次启动随机生成)。真机 E2E 绿(debug-entry→SessionList)。app 侧 V2 探测修复(可观测性缺口)仍待做
  - 2026-09-27 根修：/api/event 线面探针接替 /api/health（2.0.16+）；真机 v2 回路绿（条幅恢复 7s 消失）。服务器侧 v2 systemd 服务化+固定密码已就绪。
  - 迁入依据：用户委托(你来修,测试 ok 即可):根修已合入 7d8ae62b(/api/event 线面探针接替 /api/health),此后文件零漂移;2026-09-27 真机 v2 回路绿(条幅 7s 消失)+2026-09-29 全量单测绿（backlog.sh migrate 2026-09-29）

### **#438 流式突发路径收尾：gate 时间限速与配对 set 保 key** `streaming` `scroll` `#437`
  - 真机 R9 实证两残差：①catch-up 期 gate 按 400ch/48ms 释放而 measure 滞后聚合（442ms 聚 7 批=单 note d=6236）；②大额配对 set 走 requestPositionAndForgetLastKnownKey 核销锚 key，突发期新 item 插入+重排后 LazyList 按字面 index 重锚（LEAP -7562 视觉大跳）
  - 修法方向：gate 释放按壁钟限速（与到达解耦）；配对 set 后在同帧重建 lastKnownKey 或改用保 key 的定位通道
  - 证据：docs/journal/2026-09-25-437-streaming-md-stable-reveal.md 验收十轮；/tmp/t9_log.txt /tmp/f9 帧
  - 2026-09-27 #438① 已落地：gate 大放行壁钟限速（BIG_RELEASE_CH=200/间隔≥200ms，与到达解耦）+ releaseLength maxReleaseChars 参数（空行毕业段纳入批预算）；真机 gran-run4：append max 1023-2087→200。②配对 set 保 key 未动（下轮）。
  - 2026-09-28 深度调研(issue438-research.md):①已完整收口;②键回写机制在但覆盖有洞——R-1 targetKey 仅落点在 visibleItemsInfo 内才解析,越窗 null 仍按裸 index set(R9 LEAP 残余通道);R-2 新发现死接线:shouldYieldPairing 记忆变量 lastSetFii/lastSetFiso 声明在每帧执行的 PreDrawFlushTask lambda 体内(ScrollCompensation.kt:366-369),每帧重置→让位防御从未生效(#444 嫌疑(a)直接对应物,与 issue442 调研交叉互证);方案A(M):resolvePairedTarget 纯函数+null-key 熔断+记忆缝修复+TDD 3-4 例
  - 2026-09-28 R-2 让位防御死接线已根修:lastSetFii/lastSetFiso 从 PreDrawFlushTask lambda 体内提到工厂函数体(闭包捕获,记忆生命周期=task 实例)——修复前每帧重置 null,shouldYieldPairing 外部 pending 让位分支(cb733d80 引入)在生产从未生效。TDD:FlushTaskMemoryTest 新增 2 例(红→绿:跨帧记忆+让位触发/实例间无泄漏);全量单测绿;真机流式冒烟 flush/release 配对正常。R-1(null-key 裸 index set)未动——方案A 剩余项
  - 2026-09-28 R-1 null-key 裸 index 通道已根修:resolvePairedTarget 纯函数提取(可见窗换算原逻辑+越窗 dataKeyAt 数据侧 key 投影兜底——chatEntries.entries[i].key 与 LazyColumn item key 同源含 chunk 后缀);装配 ChatMessageList dataKeyAt 投影+remember key 加 chatEntries(重排后记忆清零语义正确)。TDD:ResolvePairedTargetTest 4 例(窗内换算/越窗投影/无投影退旧通道/投影miss退null);全量单测绿。至此 #438 方案A 三件套(R-1 熔断→投影/R-2 死接线/测试锚)全部落地
  - 迁入依据：用户裁决无需验收直接关闭——三件套全落地:①gate 壁钟限速 200ch/≥200ms ②R-1 resolvePairedTarget 越窗 key 投影 ③R-2 让位防御死接线修复;TDD+真机流式冒烟绿,用户可感面由 #437 廿余轮定量证据覆盖（backlog.sh migrate 2026-09-29）

### **#428 大卡收起闭合帧后一帧视口重填 268px 跳变(LazyList 不满视口二遍填)** `perf` `render`
  - 收起锚点恢复后视口 items 总高 1930<2400,下一帧 LazyList 补齐=268px 单帧上移泄露(100%复现,与渲染解析无关——三假设两否一立)
  - 候选:FLUSH 拒绘闭合帧hold到视口稳定/恢复位预填/beyondBoundsCount 试验;同族:批次十三c 锚点重推导泄露
  - 取证链+工具沉淀见 journal 2026-09-23 追加批次四
  - 修复(2026-09-23):根因修正——二遍填非新条目组合,是恢复位邻域条目(#s1 TurnChunk 段,含 101 字符小文本 part)闭合帧原子重组时走异步解析路径首帧 State.Loading 短高 199px 入测,Default 解析完成后次帧回填 467px→其下内容 +268px 二次重排=「上推1~3帧后高度复位」;ItemSize428 逐条目探针定罪(p5/p6),多模态取证排除 loading 圈观感(占位帧内容超绘、槽位错位)
  - 修复落点 MarkdownContent.kt:≤2048 字符文本 part 改 remember 内联同步解析(库 parseMarkdown 非 suspend 纯函数,组合线程有界 1-3ms,非 runBlocking 家族),首组合首测即终高;>2048 保持异步(84ms 冷滑巨帧防线+registry 预解析覆盖)
  - 验证:基线红 5/5(b1/p3/p5/p6/fix1)→修复绿 5/5(fix2/fix4/v1/v2/v3,POST_CLOSE_RED=0,#s1 首测即 467,覆盖新装/会话重进/不同锚点);单测全绿;回归 6 连点+3 fling+会话重进无崩溃无 ANR 无集退出异常
  - 勘误:诊断期 short-text 假设证伪不成立——当时改道库 rememberMarkdownState 同样首帧占位(parse 在 LaunchedEffect);残余同族风险:>2048 字符 part 在 registry 条目被视口离场逐出时仍可占位一帧,加固项(registry 保留/LRU)另记
  - 同族加固落地(2026-09-23):MarkdownParsedStateCache(LRU 32,内容为键,Loading 拒入)接入 rememberAsyncMarkdownState——命中即同步终态,覆盖 registry 视口离场逐出与 <200 字符门槛两类 miss;单测 7 例+真机双收起绿(k1/k2 POST_CLOSE_RED=0)+回归无 ANR
  - 迁入依据：定罪链闭环:2026-09-23 修复+同族加固落地(基线红5/5→绿5/5,POST_CLOSE_RED=0)+2026-09-29 #462 收起像素复验中段静止(行为级新鲜证据同路径)+全量单测绿（backlog.sh migrate 2026-09-29）

### **#457 displayItems 单 key 值缓存三处遗留洞(#452 同款:SnapshotStateList 实例键自反恒等)** `chat,render,bug`
  - #452 深审(issue452-followup-audit.md)全仓扫描:ChatMessageList.kt:595 turnOrdinalByMsgId(中危——台账轮次号翻页后永不更新/错位,违背自身设计注释)、:599 displayItemMessageIds(低危 V1 去重)、:602 v1CompactionSummaryInList(低危)——均 displayItems 单 key 值缓存无兜底;修法照抄 size-key 或改 derivedStateOf;另建议补 androidTest Compose 层回归测试防 key 改回实例引用(骨架已在审计报告)
  - 2026-09-28 已修:三处(ChatMessageList.kt turnOrdinalByMsgId/displayItemMessageIds/v1CompactionSummaryInList)remember(displayItems) 实例键自反恒真 → displayItems.size 键(#452 同款修法,快照读建立失效依赖+值比较);注释已标机制。验证:全量单测绿;行为级androidTest(Compose层)仍缺(与#452深审建议同池)
  - 2026-09-29 用户裁决:先留着待观察,3 周窗(至 2026-10-19)无反馈(轮次号错乱/去重失败类怪象)即关闭。防御性修复无可直接观察面,日常无异常视为通过。
  - 迁入依据：用户询问根因修复——站点级根修已落地(2026-09-28 三处实例键→size 键,#452 同款修法失效链恢复),观察窗保守确认不再必要;可选加固 Compose 层 androidTest 未另立卡（backlog.sh migrate 2026-09-29）

### **#423 PreRenderCoordinator 集中式渲染前计算模块** `render` `architecture` `stability`
  - 集中式渲染前计算/视口配对模块:意图层并行声明+底层单写者串行帧事务,统一全部卡片展开/收起稳定性(十一轮竞态根因收拢)
  - 已裁决:全量收编(D1)/统一动画契约含大组(D2)/流式入队分两步(D3)/每步人工手感验收(D4)/反射炸弹 Phase0 护栏(D5);五不变量 I1-I5
  - 六路调研已归档+spec v1 已写,待用户签收后按 Phase0-4 小步迁移
  - → docs/research/pre-render-coordinator/00-synthesis.md
  - 实施开工(2026-09-22):P0 完成——八环仪器固化 scripts/prerender+基线留档 07(红环4 RED:展开落地+198px 瞬态=用户否决闪现,帧级证据);Phase0 完成(26a94b3f 反射探针可测缝+BOM 冒烟单测 3/3 绿,当前 BOM 2026.08.00 未装箱巧合仍成立);Phase1a 视口租约接线中(withEpisode+A3/A4/A5/A6/A9 让位,新增 8 单测全绿;fb3478f6 P0)
  - 批次一完成(2026-09-22):Phase1a 视口租约已合入(f6a4a314)——episode 全程持租约,A3/A4/A5/A6/A9 五点让位,单测 +8 全绿,全量套件绿,真机同场景零回归(红环1 GREEN 保持/账本数学逐帧一致)。+198px 展开落地瞬态已在 07 基线留档,属 Phase1 主体(引擎迁入)消灭对象。待用户 D4 手感验收。journal: docs/journal/2026-09-22-423-prerendercoordinator.md
  - 双轴评审收口:Standards 阻断项(MSGEFFECT 复查漏租约)已修复合入;Spec 轴三范围判定忠实。评审建议登记:reanchorWhenSettledOffBottom 六参谓词束 Data Clumps——Phase1 主体引擎迁入时随协调器统一收编,不单独重构。红环2 补跑 GREEN(FLICKER none)。D4 手感验收待用户。
  - 批次二(闪现根修,用户手感报告驱动):pre-pair 同遍合并+FLUSH 拒绘单点+PRD 帧级观测体系。802ms 未配对窗口→7ms;topY 逸出 0;episode 355ms;像素单帧零过冲。反射定论:无需(相位纪律即可)。commit 634a3e11。待用户手感复验(含 M2:下方内容单帧跳变是否可接受)。
  - 批次三(步骤组顶开根修,用户三报告驱动):FLUSH 实测钉位统一契约(预测配对退役——LEAP+回收双雷,帧级实证 LeftCompositionCancellationException);组尾收起行(小组+大组);重内容首帧占位。折叠行 1100→1100 分毫不动;思考卡同路径回归;全量绿。待用户手感复验。commit 805c0e84。
  - 迁入依据：2026-09-29 收口审计:架构使命完成——Phase0/1a 视口租约+批次二 FLUSH 拒绘+批次三落地,引擎主体迁入由 #435 终结(PreRenderCoordinator flush 单点+hasActiveTransactions 门控全接线,单一视口权威确立);本卡退役（backlog.sh migrate 2026-09-29）

### **#429 大卡渐进展开:消除3~5s等待+白屏揭示,展开动画与高度计算流水化** `perf` `render` `ux`
  - 实测:预热门265ms/未预热~2.4s/真冷3~5s;定罪:toggle全列表重组风暴(StateFlow<Map>)+表格240可选中文本单元单帧2501ms排版+part边界切片使窗口化失效;白屏=PhaseA落地与drawF起步区间
  - 方案分层L0(风暴收敛+表格退出Selection+解析缓存)→L1(表格行组切片)→L2(首窗即时)→L3(完全体渐进);可行性与证据链见 spec 2026-09-23-progressive-card-expand-design
  - 关联:#428(收起跳变已修,白屏同族解析缓存已备);约束:不回归逐帧全量布局族/不渲染后补偿
  - 用户裁决(2026-09-23):L0+L1+L2+L3 全量一次到位,不分层交付;表格逐字选择放弃,以「长按单元格弹菜单:复制此格(纯文本)/复制整表(TSV)」补偿
  - 进度(2026-09-24):L0 交付(d3462892,风暴收敛+表格退出选择,62ms 预热门首开);L1 虚拟化一次尝试回退(双测崩溃/窗口抖动/估高偏置三轮修复后,阻塞于 containerWidth=0 首测列宽错误+跨遍槽位别名→错误高度污染账本);L1-v2 设计=每组独立 SubcomposeLayout+Column,下轮实施
  - 进度(2026-09-24):L1-v2+L2 交付——每组独立 SubcomposeLayout+Column,组高走放置回调,首窗=视口+1屏,更深组折叠线下渐进组合;视觉对原版基线一致(ref0 裁决),真冷首开~1.2s/暖 81-94ms,零崩溃零ANR;L3 被构造吸收(可见域恒先组合后揭示)
  - 调整(2026-09-24 用户裁决):L1-v2 虚拟化因展开后滑动巨卡回退(组入窗组合落滚动帧);恢复原先算高度架构+新增 loading 过渡(引擎 onExpandComputing 信号+折叠行 spinner,计算期可见反馈);滚动流畅复验零长帧;spinner 视觉取证待设备可用
  - loading 修复(2026-09-24):①跨条目接线(LocalStepGroupComputing 共享表,大组#sgh外层行与卡体不同 LazyItem)②先行帧(invoke(true)后等一帧再 warmup——spinner 重组否则与重组合同帧被压 2.3s);终验 state=true→行渲染 10ms,2.4s 窗内圆环像素验证可见;收起回归绿
  - 后台化调研(2026-09-24,用户指示):组合/测量不可后台(WindowRecomposer绑定UI线程,官方源码定罪,1.7-1.12无API);可后台=解析(已做)/列宽StaticLayout预测(PrecomputedText官方路径);官方推荐原语=PausableComposition分帧+movableContentOf保活+Canvas直绘;文档 docs/research/2026-09-24-compose-background-compute-feasibility.md(含本地实证附录);时间切片WIP已stash待裁决
  - ## 追加批次十一(#429 A+B:时间切片三轮定罪,v4 终案交付)
  - - **信号方案三轮定罪(全数退役)**:①子树 CompositionLocal——大表 >2048 字符走 async parse(#428),表格实际组合晚于展开计算窗口(settle 提前判稳,items=…:25680 表格独立条目),Local 够不到;②进程级全局信号——toggle→重组→effect 帧序竞态(重组先于 effect),首组合读初值结构性错位;③v3 首组合恒分批——MDT429 定罪日志实证分支已进(60 行表被 markdown 源拆为 4 个 MarkdownTable:62/63/2/61 行,grouped=true)但 Skipped 114 帧仍在:巨帧主源=naturalWidths remember 全表 1116 次 TextMeasurer.measure(×4 表≈950ms)同步执行,帧步进器只分批组合未分批宽度测量。
  - - **v4 终案(MarkdownTable.kt)**:①stagedLimit=remember(content,tableNode){grouped?1:MAX}——首组合恒分批,每帧+1 组(withFrameNanos 让帧),组合完成全保留(滚动=单体,v2 教训);②naturalWidths 只测首组代表行(表头+8 行=54 次≈40ms),后续组超宽单元格走既有多行 wrap 语义,宽度恒定零重排;③与 PREWARM 既有机制协同——冷启动后空闲预热期渐进完成组合,命中即 266ms 展开。
  - - **B(movableContentOf)真机否定撤除**:re-expand 实测连续 Skipped 41/40/45/51/53(≈400ms×5)——移回虽免组合,36612px 全量测量仍在单帧执行,组合免了测量没免,收益为负;撤除后 re-expand 走 staged 同冷路径。
  - - **真机证据(小米 houji 120Hz)**:冷点击(预热未中)Skipped 74/53/33/32/31 五段渐进(v1/v3 单帧 114/116)——分批实证工作,帧间让出主线程;预热命中 266ms 零跳帧;H 精确(items 20:36660,表格 36612);episode 2165ms(冷)vs 266ms(预热命中);ANR/crash 0;TableGroupBoundsTest+全量单测绿。
  - - **rig 勘误**:uiautomator dump 持续陈旧(一律截图+多模态定位);heads-up「无线调试」通知遮挡+断连期点击无效;服务器迁移事故(16:47 用户整理,旧 4199 服务数据入回收站)——从 Trash db 迁回目标会话(session_v2+19 message+project 依赖)到新服务库(49374,reverse 重映射),会话列表恢复;滚动落点漂移需视觉闭环;Choreographer「Skipped N frames」分段分布=渐进分批的现成判据。
  - - **遗留(V6 人工验收清单)**:spinner 帧级旋转直接证据未捕获(点击落点漂移+248ms~2s 窗口,连拍/录屏两法均被误点打断)——代码逻辑链(onExpandComputing→LocalStepGroupComputing→fold row spinner+advance-frame 修复 cb7cbf47+五段渐进的帧间让出)推证充分,请用户真手指验收「点击大表展开时圈圈是否持续转动」;展开态 fling 专项未跑(组合完成后全保留=零回归 by design)。
  - #429 后续(2026-09-24):分批使大表高度增长跨越 settle 窗逐帧落地,窗外增量零配对
  - =「展开向上顶」主诉（与 #430 两层时序洞叠加）；#430 稳态配对+欠账协议修复,冷展开
  - Σ 守恒/topY 精确归位。见 #430。
  - 迁入依据：2026-09-29 收口审计:机制退役作废——过程卡片随 #430 裁决退役(2026-09-24),折叠/loading 机制随 #422 清理批次移除(#474 统一渲染树),spinner V6 人工验收项随之失效;大表成本由 #431 分段+v4 分批接管,折叠移除后 16 次穿大 turn 零掉帧实证（backlog.sh migrate 2026-09-29）

### **#430 展开向上顶——稳态迟到增长零配对+欠账派发时序** `perf` `chat` `ui`
  - 主诉:reverseLayout 下各类卡片展开有时向上顶而非向下(2026-09-24)
  - 根因三层:①episode 单时刻配对,settle 窗外增量(分批表格/asyncParse)无主;②dispatch 瞬态 0 消费(增长晚一帧落地→内测见旧高→PairedDispatch 放弃);③连锁=reveal 被顶出视口→切片窗口断粮→表格永不组合
  - 修复:稳态账本(measure 记账 Δreport)+pre-draw flush 同帧派发+欠账 rebase(pending=目标−实消费)+派发门控 rep>0+残量 2s 重试窗+H 目标=落地高度
  - 终验:冷展开 topY 2051→2051 精确归位,Σd=Σconsumed=36612 完美守恒,表格 36660 完整组合;收起锚点精确恢复;ANR/crash 0;单测含 5 个新用例全绿
  - 迁入依据：2026-09-29 收口审计:修复泛化并入引擎——稳态账本+pre-draw flush 同帧派发+欠账 rebase 即 #435 引擎主体(StreamingGrowLedger+PreRenderCoordinator,当日验收关闭);原卡冷展开 topY 精确复位 Σ 守恒实证持续有效（backlog.sh migrate 2026-09-29）

### **#431 #431 滚动穿大表单帧巨块——巨型 text part 单片不可分+首组代表测/解析成本** `perf` `chat` `ui`
  - 过程默认展示(#430)后滚动穿表成为新付费点:实测 6 次滑动 Skipped 102/58/60/64/69/47(每屏 0.4-0.85s 冻结)
  - 根因:sliceStepGroupBodies 只在 PartGroup 边界切片,10193 字符巨型 text part 独自成片(≈5 屏),进窗单帧全量组合(markdown 解析+表脚手架+首组代表测);v4 表内分批只覆盖表格首组合,不覆盖片级首帧
  - 候选:A 片内 markdown 块级分段(Stage B 语义下沉到窗口体) B 表格列宽跨回收缓存(naturalWidths 每片重付 48 次测量) C 片粒度细化(1 屏→1/4 屏,冻结块×4 小但更频)
  - 修复落地(2026-09-24,提交见 journal 批次十五):
  - A 巨型 text part markdown 块边界分段(splitHeavyTextPart/markdownBlocks 纯函数,
  -   表格/围栏代码原子,贪心装段≤2200 字符;合成 part id#sgN)——万字符 part 不再
  -   独自成片,窗口化按屏付费;
  - B 表格列宽跨回收 LRU(NaturalWidthsLru,容量24,键=fontSize+全文)——回收重入
  -   零重测(原每表 48 次测量×4 表≈150ms)。
  - 真机前后对比(同参数 12 次滑动穿表):基线 Skipped 102/58/60/64/69/47(6 次冻结
  - ≈3.4s)→ 优化后 1 次 Skipped 35(且与刷新率切换系统事件同帧);热轮回滚再 +1 次
  - 45。渲染完整性判读通过(行序连续/列对齐/无断表)。单测 7 新用例全绿。
  - 迁入依据：2026-09-29 收口审计:修复在 HEAD 确认——splitHeavyTextPart(StepGroupSlicing.kt:72 接线)+NaturalWidthsLru(MarkdownTable.kt:109)双落地,2026-09-24 真机同参数 12 次穿表 6 冻结→1;折叠移除后 16 次穿含表 turn 零掉帧复验——关闭（backlog.sh migrate 2026-09-29）

### **#475 末轮步骤组折叠态:跨重启持久+展开入口难命中+折叠区近空白渲染** `chat-ui`
  - 真机 #474 六轮验证期间构造复现:任何大幅朝最新端的链传导/LEAP 后末轮易折叠为'• • • • •'
  - 折叠态跨 force-stop 重启持久;turn 头右缘图标点击可解但一次命中难;折叠区滚动时可见文本零变化(近空白占位)
  - 与守卫无关(沟槽直拖同样触发);影响验证可操作性,用户场景可能同样受累
  - 迁入依据：2026-09-29 收口审计:折叠 UI 已不存在(随 #422/#430 过程卡片退役,折叠符号 grep 清零+真机 vision 无折叠行无圆点);末轮渲染真机 vision 三路交叉+像素双验证完整(连续正文无空白无圆点);原「近空白/折叠态跨重启」判读定罪为 #477 uiautomator 语义零暴露 dump 假象——原卡作废关闭,缺陷转 #477（backlog.sh migrate 2026-09-29）

### **#434 #432 根因定罪:贴底构型收起镜像dispatch 0消费→上方内容裸下移H px** `chat-ui,bug`
  - 连接态(DSH)实测+录屏双证:贴底(fii=0,fiso=0)收起时镜像dispatch -H在新侧无空间,consumed=0(paired-shift日志实锤),塌缩无补偿→上方旧内容裸下移H涌入视口(录屏帧94→101判读确认'顶部露出更早段落',542px)。中位构型dispatch可消费故守恒(矩阵1-6全绿)。用户流式场景常处贴底=高频触发。修法:consumed==0且贴底时换向dispatch +H(旧侧有空间,补偿上方内容下移;不露底空白——露的是旧内容)。候选实现:PairedDispatch加方向fallback;需真机验证方向+防双发。
  - 迁入依据：2026-09-29 收口审计真机判决:原触发机制已结构性消除——收起路径经 #462 增量镜像(目标=当前视口−episodeShiftConsumedPx)+#474 归一/rep 兜底整体重写,原「绝对快照恢复+paired-shift dispatch 贴底 0 消费」路径不复存在;今日近底配置(9,7217)实测 expand→collapse:close-anchor-request mirror consumed=260 ledger=260 rep=260 精确镜像,RESIZE -260 配对,上方三带像素位移=0 残差=0.0(仅卡自身区 +136px 局部变化);#474 期贴底构型实证在案((7,720)−720→(7,0) 精确闭环);严格 (0,0)+可见开关在当前会话布局不可构造(底区=纯文本长答尾)——关闭,日常若再撞「贴底收起上方涌下」随时重开（backlog.sh migrate 2026-09-29）

### **#433 滚动死锁:fling+tap后LazyList完全无滚动响应(跨重装持久)** `chat-ui,bug`
  - 00:07 fling中tap思考卡后,列表对任意方向滑动零响应(MIUIInput事件送达,无ANR,CPU~20%)。force-stop重启/重装(install -r保留数据)均不恢复;pm clear后现场丢失无法复验。嫌疑:某持久化状态(expanded集合/DataStore)触发测量/布局死循环或滚动消费悬挂。复现路径已记录在案,待重建环境后优先定位。
  - 迁入依据：2026-09-29 收口审计:3 轮 fling 中 tap 复现尝试全绿(无一死锁,THREW/FATAL 零),沟槽/左缘拖动均响应;唯一 gesture=0 现象定罪为展开态巨卡(item h=7001 满屏)内容区吞噬拖动=#474 分通道语义 by-design(卡内滚动),非死锁;原「跨重装持久」嫌疑载体(expanded 集/折叠态持久化)已随 #422/#430 折叠机制移除消失(in-memory map,无 DataStore);原现场 2026-09 早被 pm clear 毁灭+架构整体重写——按用户指令(不存在/已修复即关闭)关闭,若再现随时重开（backlog.sh migrate 2026-09-29）

## 收口审计总结（2026-09-29）

用户指令：①点1 十卡逐项处置；②P0/P1/P2 存量先不动手，先核实当前代码是否仍存在问题，已修复/不存在者说明并关闭。

### 关闭清单（17 卡）
- 点1 用户裁决/委托：#462/#455（验收 ok）、#422（清理完毕）、#421（被替代）、#427（决策已改）、#435（引擎层代理验收）、#447（修复+测试闭环）、#438（无需验收）、#428（证据链闭环）、#457（根修已在）
- 审计关闭：#423（使命由 #435 终结）、#429（机制退役）、#430（泛化并入 #435 引擎）、#431（修复在 HEAD 确认）、#475（折叠 UI 不存在+#477 dump 假象）、#434（机制重写消除+近底像素零跳变）、#433（未复现+载体消失）

### 仍存在→保留（代码级确认）
- #470：reserveReleasePlan trueHeight<=reserved return null（帽不回改）+ ledger note d<0 不配对（ScrollCompensation.kt）——与卡片描述逐行吻合
- #469：MarkdownTable 三连全在（rows/measureCache remember 含全文键；stagedLimit remember(content,tableNode) 流式 append 重置；containerWidth 首拍 0→cap 夹窄）
- #437 R-7：beta/stable STREAMING_MD_PILOT=false+STABLE_REVEAL_PILOT=false（build.gradle.kts）——修复仅 dev 生效
- #439：chatEntries 结构签名缓存未实施（remember(chatEntries...) 实例键仍在）
- #471 ③④：归一化重排固有；pilotEverRendered 为普通 remember（retry 重组丢失路径在）
- #424 R-C：预算 2200>2048 确认，synthetic 段 async Loading 首帧仍在（建议关账，待裁决）
- #441（观察/probe 数据回流）、#432（待用户复现路径）、#465/#464（间歇性未复现，建议合并）、#459/#458（外部版本事实）、#442（路线图）

### 新登记
- #477 长文 markdown 块 uiautomator 语义零暴露（dump 全盲≠渲染空白；vision 三路交叉+像素双验证渲染完整）——污染 #475 原判读与 #467 dump 实证段

### 全量单测
- 首跑×2 因早前误启双 gradle 并发互踩失败（EOFException/NoSuchFileException，基础设施非用例）；串行重跑 BUILD SUCCESSFUL in 2m 23s——验收关闭(#435/#447/#428)新鲜证据。

### 真机证据存档
- /tmp/c34_ref.png /tmp/c34_after.png（近底收起像素分带）、/tmp/ui_b1.xml vs /tmp/ui_top.xml（#477 盲 vs 正常）、/tmp/a475_bottom.png /tmp/a_cur.png（vision 判读源）

## 已完结卡片迁入（2026-09-29）

### **#424 步组内容后台解析预取池(L0)** `perf` `render`
  - 用户提案:守护线程池(如2线程)后台预取 Markdown 解析——Compose 组合/测量是主线程铁律不可搬,但解析(最重CPU段)可并行;卡片可见即预取解析模型,ε 组合直接命中缓存
  - 依赖:与 CardExpandReveal PREWARM 衔接(解析预热→组合预热两层)
  - 2026-09-28 勘误(依 issue424-425 深度调研§6.2):字面提案重复建设——解析预取早已后台化(RenderReadiness.kt:121-127 flowOn(Default))+消息/part级窗口化(RenderSupplyCoordinator ±20条LRU48);主链路'步组卡可见→预取→ε组合命中缓存'已随 #430 过程卡片退役(7d5cd5fc)消失。真实残余缺口=R-C synthetic盲区:切片段(#sgN)组合期派生不进数据模型,驱动端(RenderSupplyCoordinator:329)与消费端(MessageCardAssistant:338/:813)双端排除永远拿不到registry预热;2200预算>2048异步阈值使多数段带Loading首帧。范围改写为R-C并与 #431 方案一第3条合并执行(两卡改同一行避免重复动:三处synthetic排除+StepGroupSlicing.kt:19预算;推荐降预算对齐变体2200→2048使合成段全落#428同步路径构造上消灭Loading首帧零新机制;取舍需真机标定同步1-3ms×窗内段数滚动帧叠加)。若用户裁决不再需要步组解析预取语义可直接关账并入#431。
  - 2026-09-29 审计:代码确认预算 2200>2048(StepGroupSlicing.kt:19),synthetic 段仍走 async Loading 首帧——残余=R-C 单项(首次组合一帧,重入由 #428 LRU 缓解);建议关账并入 #431(已关闭,修复主体落地),待用户裁决
  - 迁入依据：用户裁决关账并入 #431(2026-09-29 ok)——字面提案早已重复建设(解析预取后台化既有),修复主体(splitHeavyTextPart+NaturalWidthsLru)已落地并当日关闭;残余仅 R-C synthetic 段首帧 Loading 一帧,重入由 #428 LRU 缓解,不另立账（backlog.sh migrate 2026-09-29）

### **#465 UI 暖态下点击偶发失效(冷启可靠,间歇性)** `chat-ui`
  - 2026-09-29 #461/#462 取证副产物:force-stop 冷启后输入 tap 可靠命中,暖运行后同坐标偶发零效果(无日志无 UI 变化,vibrator 反馈存在);复现条件未锁定——暖态 35min 点击仍正常(4 条 episode 日志实证),失效为间歇性非持续态。影响面=自动化验证可靠性,无用户主诉不阻塞;再撞上时现场抓 input dispatcher+app 双侧日志。
  - 2026-09-29 审计顺带实证:一次 x=1185 朝历史拖动 input 送达(moveCount=50)但零滚动响应,复测不再现——与本卡「暖态偶发失效」同族;另 #477 dump 全盲可能污染本族卡片以 dump 定坐标的判读
  - 迁入依据：用户裁决与 #464 合并为单卡观察项(2026-09-29 ok)——两卡同域重复(暖态间歇点击/拖动失效,冷启可靠,无用户主诉),观察证据归 #464 单卡承载（backlog.sh migrate 2026-09-29）

### **#445 R2 测量增量化深水区：流式 markdown 稳定/活跃双容器需换状态管理方案** `perf` `design`
  - 双容器在 append-only StreamingMarkdownState 约束下存在固化解迁移帧（空白/重叠一帧=闪烁）——需自研 append-only+前缀吸收的渲染状态或库改造；stableTailBoundary 纯函数已备（StableTailBoundaryTest 6 例）。目标：append 成本 O(总内容)→O(尾块)，滑动 p90 冲 12ms。
  - 取证补充(2026-09-27):完结窗 -67px uniform 步进源=chunk/segment plan 异步就绪节奏(RenderSupplyCoordinator),非 held 帽——帽平滑化参数保留(held 域更细腻)。深水区=plan 就绪节流/合并。
  - 排除链更新(2026-09-27 R4):完结窗t>10.5s的-67px uniform步进≠item高度变化(RESIZE序列66-68px步进全在流式期内=限速铺开的期望节奏,t>10.5s后零RESIZE)=纯视口滚动注入——嫌疑收窄到完结触底滚动分步(scrollToBottom/GUARD pending)。下一轮:logcat定位完结后的滚动派发源。
  - 结论修正(2026-09-27 R4终):条带步进帧与RESIZE序列7↔7一一对应(时间轴对齐偏移~3s)——所谓完结切换窗跳变在当前修复栈下已退化为流式尾段限速铺开节奏(66-68px/300-400ms,BIG_RELEASE_MIN_INTERVAL_MS=200 可调)——设计行为非缺陷。旧的EOF一次性大跳已被#438①限速消除(append max 2087→200)。卡片主体收口待用户观感验收;深水区(plan就绪/双容器固化解)维持登记。
  - 用户验收观感(2026-09-27):完结窗会小跳一下——记录,后续视情况修复(限速节奏参数可调)。
  - 迁入依据：用户裁决并入 #442(2026-09-29 ok)——双容器 append-only 状态管理深水区即 #442 R2 分片增量化同一主題(O(总内容)→O(尾块),滑动 p90 冲 12ms),不重复立账（backlog.sh migrate 2026-09-29）

### **#444 fling 下滑跳变复发（原 #437 系修复后回归）** `bug`
  - 用户报告（2026-09-27 R2 开工时）：此前修复过的 fling 下滑跳变再次出现。
  - 历史修复链供溯源：817607b4 配对 set 键保持(LEAP 重锚)/b54650c3 配对单帧巨额 set 视觉跳变/f09eb29b 流式滚动暂缓。嫌疑：R1-A2 单出口合并后的配对 set 行为变化或键保持通道在新路径的退化。
  - 迁入依据：2026-09-29 挖掘定性+定向复验:两嫌疑通道(键保持退化/配对 set)于报告次日 09-28 即被 #438 R-1( resolvePairedTarget 越窗 key 投影)+R-2(让位防御死接线)根修,HEAD 接线核验在位(ScrollCompensation.kt:548/:414);本日 12 次双向硬 fling 复验:31 例 LEAP 全部为连续运动(大 Δ 步均夹在小步之间=snapshotFlow 汇聚的峰值速度帧+item 穿越偏移算术+SafeFling 程序尾),零孤立跳变/零方向反转签名;两日重度测试无复发——关闭,再现随时重开（backlog.sh migrate 2026-09-29）

### **#432 思考卡收起高度变化/偏移竞态诊断(#432):15轮仪器矩阵未复现主诉,实锤prewarm早熟+滚动锁死** `chat-ui,perf`
  - 用户主诉收起时高度变化+双向偏移竞态。真机矩阵(3卡型×toggle×连点×交替×录屏逐帧)全部判绿(锚点±4px守恒,塌缩单帧)。实锤:①prewarm集体触发(同秒9卡,H=0/18早熟settle→展开偏移根源,已修2da3b6d0);②滚动死锁(fling+tap后列表锁死,跨install -r持久,pm clear毁现场未定位);③环境:opencode服务器API漂移(SSE返HTML)。待用户提供复现录屏/路径。
  - 迁入依据：2026-09-29 挖掘定性:两大实锤子项即 #433(滚动死锁)/#434(贴底收起跳变),均已当日关闭(嫌疑载体随折叠机制消失/收起路径整体重写+像素级零位移验证);prewarm 早熟已修(2da3b6d0);主诉 15 轮仪器矩阵从未复现——原卡使命终结,关闭,再现随时重开（backlog.sh migrate 2026-09-29）

### **#460 V1 服务端三处顽固缺陷实测（find 超时/项目外 file 500/share 挂起）+ 参数名勘误（/find 收 pattern）** `regression,v1`
  - 1.18.32 实测：/find?pattern 可 ReadTimeout；/file 项目外路径 500 UnknownError（非 400/404，差异文档有载）；POST share 挂起不返回；/find 参数名 pattern（文档未载）。详见回归报告缺陷 D3/D4
  - 迁入依据：2026-09-29 挖掘定性:三缺陷系上游 opencode 1.18.32 服务端事实,已归档回归报告缺陷 D3/D4(/find 收 pattern 勘误随册在案),app 侧无工作项—— informational 记录关闭,如做 V1 兼容专项再立（backlog.sh migrate 2026-09-29）
