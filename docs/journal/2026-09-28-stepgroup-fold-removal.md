# stepgroup-fold-removal（2026-09-28）

> 状态：进行中
> 关联：（spec 路径，若有）·（issue 编号，若有）
> 来源：用户反馈 / grilling / E2E / 顺带发现

<!-- 过程中的取证/验证证据直接写本文件；backlog.md 只留 ≤3 行卡片。 -->

## 执行与验证证据

### 提交链
- 433a3d1c 手术一:统一渲染树(isStreaming 双分支删除,StepGroupCard 恒走+isStreaming 仅驱动 asyncParse 解析分流;heavyComposed 门控+桩帧分支+HflickProbe STEP 探针退役;ChunkAssistantItems 增 asyncParse 参数透传,小组/窗口化两路径传 !isStreaming)
- acf7d4ba 手术二:死代码族清除(ChatEntry.StepGroupHead/Body 类+裂变路径+skipStepGroupItem 三层参数链+StepGroupFoldRow+stepGroupStateKey+LocalFoldRowYReport/LocalFoldRowClick 死链(REPIN 遗迹,无 provide 者恒 null)+ReasoningBlock 死链引用+LocalStepGroupComputing+stubHeightPx+SG_BODY_FADE_MS+chat_msg_tail_summary×15 语言+StepGroupLazySplitTest 148 行)

### 自动化验证
- 编译绿×2(手术一/二各一次);全量单测绿(testDevDebugUnitTest --rerun;首跑红=StepGroupHeightLedgerTest 4 处 stubHeightPx 残留引用,随测试对象退役删除后绿)
- i18n check PASSED:916 keys × 14 languages(删 1 key 后全一致)

### 真机验证(小米 houji,ses_f19eeb1d,4199 v1)
- **RED→GREEN 判决**:修复前 10 轮多 step POST 完结塌缩 10/10(d=-237/-401→+225/+365,65-132ms 回弹,RESIZE 两连跳上屏);修复后(versionCode=1790602346)同参数 10 轮 **0/10**(168 个 RESIZE 零 d≤-80 负跳)
- 对照组(修复前):单 step 纯文本轮 0/3 负跳——step≥2 为唯一 load-bearing 变量,与 StepGroup 互换根因闭环
- 滚动流畅性:16 次 swipe 穿越历史大 turn(含表格轮)**零掉帧**(Choreographer Skipped=0)——窗口化体系无冻结回归
- 重进会话加载路径:RESIZE 0 负跳 GREEN(历史高度稳定,账本暖)

### CBM/grep 复扫(用户后验要求)
- grep 全仓(app/src,*.kt+*.xml):已删符号(StepGroupFoldRow/skipStepGroupItem/stubHeightPx/LocalStepGroupComputing/LocalFoldRowY*/StepGroupHead/Body/HflickProbe/expandedStepGroups/chat_msg_tail_summary/SG_BODY_FADE_MS/heavyComposed)代码引用**清零**,残留全为合法历史注释(清理批次说明+CardExpandReveal 描述文字)
- [DEBUG-hflick] PLAN 探针(ChatMessageList 741-803)保留:探测对象 entries key 序列仍活,属 #473 探针清扫域非本次范围
- codebase-memory 图索引(fast)已建:project=oc-beacon

## Bug B/C(展开吞卡/下拖)复测证据(2026-09-28 21:49)

- 定位:tap 扫描(x=140, y=700-1700 每 60px)命中 y=1480 可展开卡
- 展开: RESIZE 826->1020 (d=+194) 单条目变化
- 收起: RESIZE 1020->826 (d=-194) 与展开精确镜像守恒;RESERVE align-flip overflow=0(帽零溢出);无其他条目 RESIZE(无吞卡);无 dispatch=贴底免派发正常
- toggle 风暴(5 连点@550ms): +194/-194/+194/-194/+194 严格交替,零漂移零多余扰动
- 截图留档: /tmp/462_expanded.png /tmp/462_collapsed.png(视觉终判留用户体感验收)
- 结论:展开/收起只影响目标卡自身,上方内容零扰动——吞卡/下拖仪器面消失

## 已完结卡片迁入（2026-09-28）

### **#426 结构裂变死代码清理(批次十三退役遗留)** `cleanup`
  - buildChatEntries StepGroupHead/Body 发射机+sliceStepGroupBodies+SGB 探针+LARGE_STEP_GROUP_WEIGHT 常量已休眠
  - 2026-09-28 已随 #422 清理批次执行完毕(acf7d4ba):裂变发射机/StepGroupHead-Body/skipStepGroupItem/相关常量与注释全删,grep 复扫清零——本卡可关闭
  - 迁入依据：已随 #422 清理批次执行完毕(acf7d4ba):裂变发射机/StepGroupHead-Body 条目族/skipStepGroupItem 参数链/相关常量全删,grep 复扫代码引用清零（backlog.sh migrate 2026-09-28）

#474 2026-09-28 真机定罪与验证证据

## 定罪链
- Bug A(收起零回退): t4 复现——展开 aZU0bdlc(H=194,fii=7 fiso=2986→3180 配对✓)→滚离5屏→LazyList回收→滚回→收起: close-anchor-request fii=7 fiso=2314 (mirror consumed=0) 原位零回退+RESIZE -194 无配对 = clock(remember)账本随回收丢失, resolveCollapseAnchor c<=0 短路原位
- Bug B(滚回裸顶): 同域 22:46:44 steady-report d=+720 rep=720 inProgress=true → steady 按位置优先权弃配 → RESIZE +720 无滚动配对; 录屏逐帧(降采样3x ±75px 搜索): t=9.4-9.6s 反向 -24/-18px + 连跳 +75/+75/+63px 帧(residual 28-30 vs 正常帧 4-10)
- 修复: ①mirrorConsumed = episodeShiftConsumedPx.takeIf{>0} ?: rep(稳态恒等,反射/dispatch 两收起路径统一) ②CardExpandGeometryNode 冷首测(measureCount≤2 ∧ fraction≥1 ∧ !animating)以 finalHCache 终高占位(DEBUG-FLOOR)

## 修复后验证(真机 dev 1790602346 + 926983ac + 01e4ba2b)
- Bug A: close-anchor-request fii=11 fiso=686 (mirror consumed=720 ledger=0 rep=720) → 1406-720=686 精确回退✓
- Bug B: [DEBUG-FLOOR] cold-floor effH=720 measured=0 count=1 ×2(两次回收)且零 steady-report 弃配✓
- 矩阵: mid-list ±H 精确 / 大卡 731↔1451 / 全行扫描开关 ×N / 五连点零异常 / 多卡同展收起其一 fiso 恒守恒 686 / 流式中点击(toggle 3eysGTHa=true)帽协议配对无裸跳✓
- 单测: CardExpand/CollapseAnchor/PairedDispatch 全绿

## 关联
- #467 SSE 断连实证补充: 22:35 essay 轮与 23:15 inject4 两次注入服务端 200 完成但 app 零渲染日志(SGR 无新行), 重启 app 恢复——外部注入不渲染 = #441 SSE 随机断连, 用户自发消息路径不受影响

### #474 负跳归零定量收口(2026-09-28 23:2x)

十轮展开/收起循环(th-final 轮思考卡 H=260)自动采集+python 判定:
- toggles=20(10开10关), close-pre=10, RESIZE 负跳=10×(-260)正跳=10×(+260)
- **孤儿负跳=0**——每个 d≤-100 负跳均被 close-anchor 精确配对覆盖(±1s 窗口)
- 判定脚本: /tmp/negjump.py 模式(logcat 全量→事件抽取→配对覆盖核验)
- 结论: 目标判据「仪器验证负跳归零」达成;待用户真机验收 ⑥⑦(展开收起不跳变,含滚走滚回收起场景)

### #474 像素级终验:展开瞬间纹丝不动(2026-09-28 23:23)

tap 前后 screencap 对(pxA/pxB)+分带位移相关分析(±90px 搜索):
- upper(y300-1100)/mid(y1100-1700): shift=0 residual=0.0——**逐像素完全相同**
- lower(y1700-2400): shift=0 residual=8.8——仅新展开内容出现
- 结论: 展开配对(expand-anchor fii=7 fiso=8177+H=260)下,除新增内容外全屏零位移
= 用户判据⑥「周围内容纹丝不动」的直接像素度量

### #474 soak 疲劳终验(2026-09-28 23:3x)

两段 soak:
1. 随机混合 30 轮(滚动/随机 tap/双击/三连点): 零 THREW/FATAL, 仅 1 次孤立 -260(滚出视口回收塌缩,非交互路径)
2. 定向开关 soak 4×(5 轮开关+滚离滚回): toggles=30, RESIZE 15×(-524)/15×(+524) 全配对, **孤儿负跳=0 PASS**, 进程 32594 全程稳定
- "AndroidRuntime 48 条"甄别= uiautomator dump 工具进程启停日志, app 零异常
- 连续滚离/滚回循环(回收+重组合反复)下 FLOOR 抬底与 rep 兜底持续生效, 无累积漂移

### #474 第5轮补全:贴底构型+TodoListCard(2026-09-28 23:38)

- 严格贴底 (0,0) 展开(todowrite 轮思考卡 H=720,pinned=true 豁免路径): 像素分带——upper residual=0.0 逐像素不动,mid 3.2(卡行摘要),lower -20px(展开内容向下让位);三构型(mid-list/贴底/回收)统一「点击卡及上方纹丝不动」语义
- TodoListCard(default expanded,首次点击=收起): close-anchor consumed=240 ledger=0 rep=240——**默认展开卡零账本场景 rep 兜底实证**(旧代码此处必零回退跳变=修复顺带修正的隐性 bug);close-post 720-240=480 精确;再展开归位正常
- 卡型覆盖最终清单: 思考卡(194/259/524/720 四尺寸)/bash 工具卡/TodoListCard/(QuestionCard 同 default-expanded 族同机制)/分割线(非交互)/表格与嵌套列表(非交互渲染已验)

### #474 二轮:贴底域「往下拖」根修(2026-09-29 00:0x)

用户复验反馈:基本正常,偶发「点击思考卡时视口内容往下拖动」。
- 定罪:现行 expand-anchor 恒走反射 (fii,fiso+H)——贴底/近底态(fii==0)把视口滚离底部 H,锚定区(用户正阅读的最新内容)整体下拖 H;偶发性=点卡时是否处于贴底域(轰炸 E4-E8 fii=0 ×5 复现该路径);#432 注释自留口「fii==0 offset>0 未取证保守不启用」正是此域
- 修:贴底全域(fii==0,含半贴底)豁免位移——零位移指令+锚定底部语义(增长向上扩展,卡及下方纹丝不动,上方让位);clock.bottomAnchoredExpand 标记,收起镜像零回退(防 rep 兜底反向过冲),steady plain rebase(防欠账补派违背豁免);fallback dispatch 同豁免;BottomPinnedExpandSkipTest 半贴底用例语义更新
- 验证:①贴底展开 [DEBUG-466] expand-anchor-bottom zero-shift + 像素分带 lower(锚定区) residual=0.0 逐像素不动/upper+70 上移让位 ②贴底收起 mirror consumed=0(rep=720 在场正确不兜底)原位零回退 ③mid-list 回归反射照常 6347+260=6607↔6347 精确 ④五轮贴底循环 fiso 全程 0 零拖动零累积 ⑤单测 43 例绿

### #474 三轮:占位带豁免推广(2026-09-29 00:0x)

- 发现:贴底豁免(fii==0)后,锚停在 idx0-6 占位带(fii=1..6,size=0 分隔/占位 item,真机 items=0:0|1:0|...实证)时卡恒在上方 item7+,增长不触及锚 item=锚定天然稳定,反射(fiso+H)反拖走下方=「往下拖」偶发残留末域
- 修:豁免判据推广 fii==0 ∨ 锚item零尺寸(零尺寸必不宿主可展开卡,数学安全;占位带轻滚 100px 即被惯性跨过,用户实际停入概率极低=防御性覆盖)
- 验证:fii=7(卡宿主 item)反射域回归精确(expand-anchor 183+720);单测绿;编译绿

### #474 四轮:用户裁决终向——上方不动+向下扩展(2026-09-29 00:2x)

- 用户否决二/三轮「锚定底部零位移」(上方让位=向上扩展):「不要向上扩展!!要向下扩展!!向下扩展且上方不动!!」
- 撤销全部豁免(bottomAnchoredExpand 族退役,贴底判定恢复严格(0,0) 仅管 steady 迟到增长),全域统一反射 (fii,fiso+H)=「视口下移 H 与布局增长抵消:上方净零、卡钉住、下方下移 H」
- 新增 normalizeExpandAnchor 超界归一:v1 偶发跳变防御(半贴底 fiso+H 超出锚 item 可滚范围→沿可见链向新端折算,零尺寸占位直接穿过,数学等价);本会话占位带高 0→fii==0 恒 fiso==0 半贴底不存在,归一作通用防御(item0 非零高构型)
- 验证:贴底展开反射恢复+归一 (0,0)+720→(7,720)+upper residual=0.0(上方逐像素不动,内容向下);贴底收起 (7,720)−720→(7,0) 精确闭环;mid-list 反射精确+rep 兜底(ledger=0)继续工作;单测 47 例绿(含归一 4 例)
- 语义三代对照:v1 反射=上方不动✓(缺归一防御);v2/v3 豁免=向上扩展✗被否决;v4 全域反射+归一=终态

### #474 五轮:卡内 fling 制动拉底双通道根修(2026-09-29 02:2x)

- 用户主诉:最后 turn 第一 step 思考卡展开,内容 fling 中手指触碰(哪怕 1px 下滑)→整个视口拉到对话流最底部
- 一度定罪:触摸打断子 fling,剩余速度 -1480px/s 经 onPostFling 转移父 LazyList(reverseLayout 符号=朝底)→SafeFling 一帧 LEAP 7→0;首修吞 onPostFling
- 二度定罪(复测 v=0 仍跳底):子 fling 逐帧走嵌套协议,到边后每帧剩余经 onPostScroll 泄漏父(VPT 逐帧 -8px 实证);正解=onPreFling 标记 fling 窗口,期间 onPostScroll 全额吞没+onPostFling 吞残速,drag 泄漏放行(滑到底继续滑=滚视口的期望行为)
- 复测:S2b 泄漏归零((7,720) 稳),S3/S4 残余 215px=drag 期合法泄漏,不再拉底
- 单测绿;ReasoningBlock 内容盒 nestedScroll 拦截(NestedScrollConnection 三方法)

### #474 五轮补:fling 泄漏拦截上提 item 级通用守卫(2026-09-29 02:2x)

- 盘点:消息流 item 内 21 处卡内滚动容器(思考内容/Bash/Read/Edit/Write/Search/WebFetch/Task/Shell/Skill/Question/Event/Injection/Compaction/Diff 等)同暴露 fling 泄漏场景
- 上提:CardFlingLeakGuard.kt——Modifier.cardFlingLeakGuard() 挂 ChatMessageList 消息 item Box(2526 行区),一个拦截点覆盖 item 内全部卡内滚动容器(含未来卡型);ReasoningBlock 局部拦截移除(单一来源)
- 复测等效:S2a/S2b (7,720) 稳定(fling 泄漏吞没),S3/S4 (7,492) 不拉底(drag 合法泄漏保留);单测绿

### #474 五轮终:泄漏符号适配→协议内反转注入(2026-09-29 11:4x)

- 用户否决吞没式(打补丁):期望卡内到边后惯性仍传导外层
- 三代实现对照:
  1. 原始(无守卫):泄漏被外层反向消费→上滑到底外层却朝底猛滚(残速-1480一帧拉底)
  2. 吞没(fling窗口吞):不传导,用户否决
  3. 负返回值反转(误用协议):负返回=负消费=放大正向传导,复测仍拉底
  4. **协议内反转注入(终)**:onPostScroll/onPostFling 全额吞原始泄漏(上传零)+scope.launch{ listState.scrollBy(-y) } 反转注入(走完整滚动协议/SafeFling/锚定),残速×0.12 距离换算同路注入
- 终版复测:S2 泄漏 fiso 720→808(增=朝旧端✓),S3 惯性 LEAP idx 7→8(视口朝更早内容传导✓),S4 制动 (8,27) 稳定✓;单测绿
- 挂载:item 级 cardFlingLeakGuard(listState),覆盖 21 处卡内滚动容器

## 六轮(2026-09-29):卡内滚动泄漏守卫分通道语义重写(3468fa03)

- **用户终裁语义**(本轮对话):卡内滚动到边后——拖拽(drag,手指触摸)沿协议自然传导外层同向滚动;惯性(fling)到边就地吸收,不传导不注入。五轮「反转注入」把拖拽通道一并反转(卡底下滑视口反向上移,用户实测否决),且人为造对向惯性传导,废弃。
- **实现**(CardFlingLeakGuard.kt 重写):leakDisposition(source)——仅 SideEffect(fling/动画)→AbsorbAtEdge;UserInput(drag)/Wheel/Relocate→ChainNaturally。onPostScroll: Drag 零干预(Offset.Zero 自然上传,方向归一由 LazyList 内部处理,协议天然正确)/SideEffect 返 available 全吞;onPreFling Zero;onPostFling 返 available(残速全吞,零注入)。无 listState/无协程/无 scrollBy,纯声明式。挂载点不变(item 级 Box,覆盖 21 处卡内滚动容器)。
- **单测**:CardFlingLeakGuardTest 6 用例(拖拽透传/惯性逐帧吸收/残速吸收/preFling 不拦截/分类),连 BottomPinnedExpandSkipTest 全绿;lint 绿。
- **真机仪器证据**(ses_f19eeb, 7.0s 思考卡, 逐帧判定):
  - S2a 拖拽链(内容顶+下滑): 卡头 y 1489→增量 +222px 同向跟手(朝历史) PASS
  - S2b 拖拽链(内容边+上滑): 卡头向最新端移出视口(方向✓)
  - S3 惯性吸收: logcat FlingLeakGuard "fling leak absorbed framesPx=459 residualV=0px/s" + 外层 SafeFling enter v=0px/s(零动量上传) + 无 LEAP + idx/off 稳定 PASS
  - S4 制动(用户原始主诉场景): fling 中触碰 → absorbed framesPx=541 residualV=0 + v=0 + 无 LEAP PASS
- **方向基线修正**(重要勘误):本列表真实语义=手指下滑(y增)=读历史(idx增)、手指上滑=朝最新端;旧经验规则「y减=idx增=朝旧端」系方向记反,本轮以冷重启+单步慢滑+ScrollDiag 日志实证修正。验证脚本一律以日志 idx/off 与卡头位移双重判定。
- **测试中发现的两处既有怪癖(与守卫无关,沟槽直拖可复现)**:①末轮步骤组折叠('• • • • •')后:折叠态跨重启持久、难展开(turn 头右缘图标可解)、折叠区渲染近乎空白且滚动其间文本无变化;②链传导/LEAP 大幅朝最新端移动后末轮易被折叠,折叠后 7.0s 卡从 dump 消失——已登记 backlog 待查。
