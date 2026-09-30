# 492-streaming-sticky-bottom-swipe-jump（2026-10-01）

> 状态：进行中
> 关联：（spec 路径，若有）·（issue 编号，若有）
> 来源：用户反馈 / grilling / E2E / 顺带发现

<!-- 过程中的取证/验证证据直接写本文件；backlog.md 只留 ≤3 行卡片。 -->

## 系统性定罪（跨卡 #492/#484/#491/#442，真机 5 轮布景 + 像素位移判定）

**方法**：真机 dev 包（e53e9f84 构建，滚动代码未动）+ v1:4199 big-pickle 会话；logcat 签名（ScrollDiag/SGR-435/RESERVE/VPT/LEAP/SafeFling/gesture，全程未清缓冲，#484 hitl3 纪律遵守）+ uiautomator dump + 截图像素 diff（含垂直互相关位移判定：纯平移 vs 原地重排可分辨）。

**破案链（按发现顺序）**：

1. **手势方向映射校准**（中间位置双向实验）：内容跟手（网页式）——手指上滑(y 减)=朝最新端；手指下滑(y 增)=读历史（fiso 增大）；贴底原点手指上滑=越界钳制零消费（流式/静置皆然，SafeFling `zero-consume` 佐证）。主诉「向上滑动读历史」的有效动作=手指下滑。
2. **列表结构破案**：fii=0..6 = 零高收起横幅（revert_banner/dsh_jobs/compaction 等恒驻声明），流式 item=首个有尺寸 item（当晚恒 fii=7，anchor= 键可证）。⇒ `release ... set(fii=7,fiso=0)` 幻影日志破案：resolvePairedTarget 以 total=0 也能穿过零高 item 落到首个有尺寸 item；total=0 时 requestScrollToItemNoCancel 不派发（ScrollCompensation.kt:558 guard），纯日志伪影。LEAP dIdx=7 同理=穿零高 item 的良性跳（无视觉变化）。
3. **核心配对（锚在增长源内）无罪实证**：deep-sea 轮流式贴底手指下滑 269px 松手，其后 19s 无任何触摸，fiso 逐批 paired release 自动爬升至 6925（+6656px），截图像素 diff **0.3%（mean 0.13）= 完美视觉稳定**。开工预测「锚内手势结束一次性大跳」被证伪；拖拽全程跟手（VPT d=12-16/帧平滑）。
4. align-flip 手势起点触发 caughtUp=true overflow=0 = 零位移良性（嫌疑②排除）。
5. **无补偿滑移家族实证 ×2（协议外高度源）**：
   - rivers 轮：手势结束视口冻结于 (7,2876)，A(+0.9s) 与 B(+3.5s) 间内容整体下移 40px、**残差 0.0（完美平移）**；窗口内零 RESIZE/release/attach 事件，仅一次 /question REST 轮询。
   - mountain-railways 布景（无增长窗口）：s1→s2 下移 26px、残差 7.0，同样零仪器事件。
   - 结论：存在**非工具化高度源**（候选：完结统计行/时间戳刷新/条目间距重排），冻结锚被无声滑动。⇒ #491「完结上抬数像素」直接同族；#484 坍缩为同协议的反向（大负向）违约。
6. **H1（锚越过增长源→「读历史免」不补偿）= #492 头号嫌疑，代码级定罪、设备复现未成**：StreamingAnchorRule :95-97 对 anchorIndex>growthIndex 返 0f（免），但帽释放撑大增长 item 时更旧 items 在列表空间必然位移（几何推导）→ 免补偿=每批可见跳变 + 手势结束帧一次性持帽 Δ 大跳；跳变方向（向最新端/统计栏）与主诉吻合。两次布景失败：短轮 flash 完结（tides 1.4s 内 644px 完结）、v1 会话进入 SUSPICIOUS 多发态（L3 误判 Idle/SseIdle force-complete）。⚠️ 修正记录：mountain-railways 轮 s1→s2 的 26px 滑移曾误判为 H1 证据，复核时间线发现窗口内无任何增长，实为第 5 条滑移家族。
7. 晚组合渐入：快速拖拽后进入视口 item 的 chunk 渐次组合（28.9% 像素渐变、非平移、无滚动事件）——独立观感源，参与「闪/跳」观感。
8. 死方向上滑静默解除 autoScroll（autoOn=false，零反馈零位移）。

**跨卡裁决**：四卡同住「流式高度变化↔视口补偿协议」子系统。协议不变量只保障稳态（贴底原点物理跟随/锚在源内 flush 节奏配对——今晚均实证正确）；**全部状态转换接缝（手势接管与结束、锚越界、完结退休、REST 重渲染）与协议外高度源均无统一不变量保障**。#492=正向免补偿跳变（H1 待复现）；#491=小滑移家族（两例实证）；#484=大负向坍缩（低频，hitl3 观察窗继续）；#442=主干收口载体（R2 深水区正好覆盖）。

**下一步**：①受控慢流式环境复现 H1（v1 会话状态恢复或换新会话后）；②非工具化高度源仪器补全（统计行/时间戳/间距入 RESIZE 账）；③零高 head items 锚语义治理（fii 噪声+幻影日志）。

## 广域检测网落地（用户裁决：仪器补全+零高治理+全接缝埋点；补偿不是根修）

**交付**（commit 见后；全 DEBUG-only，遵循既有限频/去重范式；既有 tag 格式零改动——#484 判读签名集不受扰）：

1. **新 tag 七族**：
   - `SilentShift`（静默滑移检测器，ChatMessageList turn Box）：item 根 y 变化而 fiso+高度双冻结 ⇒ 位移来自本 item 之外；600ms GrowthClock 增长静默窗门控（物理跟随/配对跟踪不报）。
   - `ItemH`（横幅实高变化）：BannerReveal 包裹层（CardExpandReveal 动画外层——内层内容 Box 量不到 revealed 高，首版踩坑后提升）+ 条件横幅内层 Box；8 恒驻横幅经 diagKey 传身份。
   - `ItemP`（item 存在性账本 enter/leave）：itemsIndexed 全条目 + 12 横幅/卡/加载点——低于锚的插拔不动 fiso，唯一签名即存在性。
   - `LBox`（列表容器自身位置/尺寸）：父布局推移检测。
   - `TurnFin`（流式→完结翻转帧标记）：#491 完结换装定罪起点。
   - `LRef`（引擎反射滚动唯一出口派发审计，含 set-fallback）：真派发必留痕，与 release 行成对。
   - `GrowthClock`（内部时钟非日志）：RESIZE 打点，SilentShift 门控用。
2. **既有探针治理（②零高噪声）**：LEAP 分级（W 级=真跳变需 dOff 或穿越像素 px>350；LEAP-Z=D 级穿零高横幅良性跳，含 px= 字段）；VPT 加 asize=（锚 item 尺寸，asize=0 ⇒ 锚是零高横幅）；SGR-435 release 日志 nodis 标记（total=0 不派发时不再打幻影 set(fii=7)——曾误导两轮定罪）。
3. **文档**：probing.md §2.4.1「广域检测网」新表 + LEAP/SGR-435 判读更新 + MDResize 大小写 grep 坑实录。

**装机即战果**（dev 真机烟雾，两轮）：
- `LBox h=972→1104 (+132px)`：列表容器自身变高（输入区收缩族）+ 伴随 78px 减速下滑动画=**滑移家族第一亚型定罪：容器/父布局尺寸变化**（此前全盲区）。
- -96/-56px 上跳一例：当时 LBox/ItemH(内层版)/ItemP 全静默——暴露 ItemH 首版层级错误（量内容高而非 revealed 高），已提升到 BannerReveal 层；该亚型待复发归因。
- `LRef set` 与 `release set()` 成对出现（派发审计闭环）；SilentShift 门控后物理跟随零误报。
- V1：compileDevDebugKotlin 绿 ×3 轮 + testDevDebugUnitTest --rerun 全量绿 ×2。

**根因深化（用户指令：补偿≠根修，找根因）**：几何根源已表述于本 journal 前节——流式增长在「锚度量原点之前」插入（新 token 落在 item start 侧，旧内容整体后移）⇒ 锚内容结构性漂移 ⇒ 整个帽/账本/配对引擎都是在**对抗自致的几何**。免补偿构造方向（#442 R2 载体）：①锚越界期间持帽到底（不释放=零位移构造，取代「释放+免补偿」的现行错位）；②完结换装单一渲染路径（流式/完结同解析器同布局参数=高度保持构造，取代完结后补偿）；③容器稳定（输入区/island 不推挤列表，取代 LBox 变化后补）。三方向共同点：**消除位移源，而非事后补偿**。
