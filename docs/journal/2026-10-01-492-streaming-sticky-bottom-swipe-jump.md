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
