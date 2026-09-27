# 446-tearing-root-fix（2026-09-27）

> 状态：进行中
> 关联：（spec 路径，若有）·（issue 编号，若有）
> 来源：用户反馈 / grilling / E2E / 顺带发现

<!-- 过程中的取证/验证证据直接写本文件；backlog.md 只留 ≤3 行卡片。 -->

## 根因定罪与修复（R1-R4）

**症状**（#446）：流式中 active 块上移快于 turn 上方内容，视觉撕裂。

**取证链**（工具沉淀 `~/card-lab/`）：
- R1/R3 两轮作废教训：R1=入口后重启了 v1 服务器杀掉 app SSE（整窗零探针，完结仅 L3 刷新）；R3=POST 目标会话（card-sid.txt=CARD-R2/ses_f1f81ee7）与屏幕会话（tap CARD-EP11/ses_f1f999）**错位**——流式进了不在屏幕上的会话（SessionStateService --TextDelta--> 满屏而渲染零动静）。方法论固化：发消息前必须核对「屏幕会话==目标会话」，入口后禁触服务器。
- R4（有效轮）：sid 对齐 + 全量 logcat + 33.5fps 录屏 175s 完整覆盖 50.5s turn；探针量：MDPgate 320 / MDPapp 128 / RESmeas 71 / SGRrel 68 / RESIZE 73。
- 新仪器三件：`tear-strips.py`（16 条带逐帧相位相关竖直位移差分；内容带 b2-b13 分类，固定 UI 带 b0/b1/b14/b15 排除——初版把它们计入 spread 导致一致位移帧全误报，勘误在案）；`tear-timeline.py`（探针日志结构化，注意 logcat `TAG ( pid): ` 段）；`tear-correlate.py`（一致位移帧 ↔ capd 匹配求录屏-日志时移 ≈2.0s，撕裂帧映射事件窗 ±200ms）。

**撕裂画像**（80 异常帧 / 1792，贴底全程）：
1. **b12/b13 底缝单带差动** ±6.5~66.4px（26 帧，正负双向），全部对齐 MDPgate 毕业/RESIZE/RESflush ±50ms 窗——统计栏/held 缝独立于其余内容位移；
2. **毕业窗 ±80px 往复**（f813-f823 序列：b12 -23.4 → 中段 -24 → b12 -10.9 → 中段 +82 → b12 -48.8）；
3. **大块毕业（fence/表格闭合）原地重排混沌** 1-2 帧（单批 RESIZE +284/+744）；
4. **完结切换窗混沌**（t≈46-49s，EOF 全量重挂 3930ch + SKIP 78 + RESflush 10826→231）——#438/#445 已知家族，不属本卡范围。
- 对照组：多数批次为全内容带一致位移且幅值=capd（配对正确的基线行为）；CONTENT-BLINK=0（条目结构稳定）。

**根因**：`StreamingMarkdownPilot` 毕业 held 收缩侧一帧延迟（`withFrameNanos{}`，2026-09-25 为前帽时代「净高先减一帧→视口单次往返闪烁」所加）：正文扩张落帧 N、held 收缩落帧 N+1 → item 净高单帧回缩；帽 `reserved` 单调不回改 → top 对齐下统计栏/held 缝**单帧上跳 Δmoved**（b12 负向差动源）、底对齐态下内容整体下滑 Δmoved。帽协议（#437 引擎①）落地后该延迟的保护对象已消失——增量当帧被帽裁掉、flush 单出口只放行净增长（trueHeight−reserved），列表永不见负增量。

**修复**：撤销收缩侧延迟，held 收缩与正文扩张同帧（净高变化交帽协议承接）。

## 修复验证（R5 对照）

- 修复装机（install -r 保数据）：commit e76aed43；构建环境重建顺带完成（brew openjdk@21 → ~/.jdks 探测位 + ~/android-sdk cmdline-tools/platforms;android-37.0/build-tools 37.0.0-rc2 + local.properties 改指——机器迁移后 JDK/SDK 全失是本轮编译五连败根因）。
- R5（同会话同型 prompt，turn 62.3s，探针量与 R4 同量级：MDPgate 391/MDPapp 141/RESIZE 82/SGRrel 78；录屏 2261 帧 @34.4fps）vs R4（1792 帧）：
  - **b12 底缝差动族：13 帧 → 2 帧**（0.73%→0.09%，收敛 ≈88%），且残余 2 帧集中于单一 episode（t≈32，大块扣留窗的锁高量子步进族，亚 50px）；
  - **±80px 振荡级联：消失**（R4 f813-f823 形态不再出现）；
  - b13 滞后帧为度量伪影（b13 含固定输入栏图案，R4/R5 同量），不计；
  - CONTENT-BLINK 两轮均 0。
- 残余已知族（不属本卡、不扩大）：①大块毕业/扣留窗原地重排混沌 1-2 帧（SafePrefixGate 整块放行，#443 粒度扩展域）；②完结切换窗混沌（EOF 全量重挂+SKIP，#438/#445 家族）。
- 环境注意：gradle.properties 的代理四行与 org.gradle.java.home 为**本机生效、禁止提交**（CI 无代理/无 brew 路径会被破坏）；local.properties 已改指 ~/android-sdk（不入库）。
- 工具沉淀（~/card-lab）：tear-r1..r5.py（复现协议：清日志先于入口+sid 对齐屏幕会话+首探针门禁+anchor 记录）、tear-timeline.py、tear-strips.py、tear-correlate.py、tear-r{4,5}-logcat/timeline/strips.csv 全套数据。
- 待用户验收（V6）：日常使用观感确认「流式输出中 active 块与上方内容是否还有撕裂感」。

## 已完结卡片迁入（2026-09-27）

### **#446 SSE流式active块上移快于turn上方内容(视觉撕裂)** `streaming` `render`
  - 疑似与上文消失(CONTENT-BLINK)同源;用户裁决支线,卡片介入实验后系统调研
  - 候选:帽clip相位差/放行与reserve释放节奏/diff与flush事务帧错位
  - 根修交付(2026-09-27,e76aed43):毕业 held 收缩撤销一帧延迟(withFrameNanos)——真机条带差分定罪 b12 底缝 26 帧差动全对齐毕业窗;R5 对照 b12 族 13→2 帧(收敛88%),±80px 振荡级联消失。真机验证绿,单测跑全量;待用户观感验收
  - 验证收口:单测全量 3573 例两轮绿(首轮1例 SseConnectionManagerTest MockK verify 超时=冷跑负载偶发,定向复跑绿+全量复跑绿交叉确认);真机 R5 对照 b12 族 13→2 帧
  - 迁入依据：用户日常验收未再见撕裂(R5+本批限速后观感通过)（backlog.sh migrate 2026-09-27）
