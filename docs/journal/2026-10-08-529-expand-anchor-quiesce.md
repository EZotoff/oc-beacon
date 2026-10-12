# 529-expand-anchor-quiesce（2026-10-08）

> 状态：进行中
> 关联：（spec 路径，若有）·（issue 编号，若有）
> 来源：用户反馈 / grilling / E2E / 顺带发现

<!-- 过程中的取证/验证证据直接写本文件；backlog.md 只留 ≤3 行卡片。 -->

## §1 #529 根修：锚点捕获静止门（1efbce1a，2026-10-08）

**背景**：观察窗（至 2026-10-14）期间用户裁决提前开工修复（2026-10-08「开始#529」）。修法沿 journal 2026-10-07-dsh-standard-bugs §6 备好的两方向落定。

**机制勘误（本轮首项产出——推翻「抬底进锚点基」假设）**：
- 探针签名 `boxH=194 @ f=0.001, rep=0` 的正确解读：`onSizeChanged` 挂在 `cardExpandGeometry` 修饰符**内侧**，观测到的是内层内容盒的**自然高**（194），被外层 `report=0` 裁剪——不是布局槽位，item 实占仍 0。真机 E1 复现同签名（boxH=24074 @ f=0.001, rep=0, host 未变 13242）实证此读法。
- 抬底（cold-floor）门控 `measureCount<=2 ∧ fraction>=1f ∧ !animating` 在 E1（收起态回收重组合→展开集）**结构性关闭**：集内 animating=true 恒成立，fraction 集内 0.001→1 只发生在 driveTo（animating 中）。方向 A（floor 对齐）无需修，理由记录在案。

**定罪面（方向 B 推广）**：反射目标 `(fii,fiso+H)` 的绝对位语义建立在「捕获→待定区消费之间布局基不变」上。E1 恰逢滚动余尾 + 回收重组合后 item 异步内容跨测量遍落地——基漂移使同一 (idx,off) 请求绝对落点漂移=双计（样本1 topY+H）/半计（样本2 topY+H/2+校正帧）。settle 只保证**本卡**内容静止，不管列基。

**修复（消除源，非补偿）**：`awaitAnchorQuiescence(listState)`——展开（settle 后、读 H 与锚点前）与收起（幕布后、读 rep 与锚点解析前）两分支插入静止门：
- 滚动静止门：`isScrollInProgress` 期间基恒漂，重置计稳（dispatchRawDelta 亦置位——兄弟卡稳态派发同样被门拦住）；
- 基指纹计稳：`fii:fiso|idx=size,...` 连续 2 帧不变（anchorQuiesceAdvances/anchorBasisFingerprint 纯函数可单测）；
- 有界 200ms：超时按现状捕获（E3+ 恒稳零感知；典型代价 10-25ms，被 computing 过渡/幕布吸收）。

**验证**：
- 单测：AnchorQuiesceTest 8/8（指纹区分度 4 向 + 计稳语义 + 判稳序列）；chat components 包 398 全绿；全量套件另行留痕。
- 真机（192.168.1.2:34935 无线，dev 包 03:35 build，v1:4199 会话 ses_f19eeb1d7ffeii7H8I4bFCaWaw「App verification steps」78 reasoning parts）24k 巨卡：
  - **E1（回收重组合后首个展开）**：滚离视口×3 屏→滚回→立即点击。门 stable=2 ms=25；PLACED 四帧 topY 恒 1533 纹丝不动，fiso 12391→36465（+24074=H 精确），host 13242→37316（+24074）。**零跳变**。
  - **E2 收起**：门 stable=2 ms=11；close-anchor-request fii=8 fiso=12391（mirror 24074）——回退到 E1 捕获基分毫不差；close-post 落位 12391。
  - **E3 对照（同实例再展开）**：门 stable=2 ms=23；topY 恒 1533，+24074 精确。
  - **E4 fling 余尾后收起**：门 stable=2 ms=10；completed=true f=0.000 干净落位；其后 1.2s 空闲预热正常沉降（settle done frames=2 H=24074）。
  - 视觉终态截图核验：渲染正常无空白错位。
- 诚实边界：机器节奏 15 轮在修前也是干净的——本轮真机 E2E 证明的是**无回归 + 门每次生效 + 反射数学精确**；「真手指滚动余尾+速点」的脏节奏终验仍在用户侧（观察窗照旧至 10-14）。

**后续解码增强**：`[DEBUG-529] anchor-quiesce stable=N ms=M` 每 episode 一行——用户再遇跳变时 `adb logcat -d | grep -E "PLACED|expand-anchor|DEBUG-529"` 三签名并读，可直接分辨「门超时捕获」（stable<2）与「门后基仍漂」（stable=2 仍跳——那才是新机制，PLACED 序列继续解码）。

## §2 补充 E2E：系统性扩面（2026-10-08 03:55-03:57，用户质询「全面端到端」后补齐）

**补测动机**：首轮只覆盖单卡四集；按 2026-10-06 验收委派裁决（「AI 全面系统性真机 E2E」关卡标准）补齐构型面。

**新增覆盖（同会话同真机）**：
- **host≠fii 折叠链构型**（首测卡为 host=fii=8 同 item；补测卡 fii=22 host=23——normalizeExpandAnchor 的宿主容量 +H 折叠路径真实走通）：思考卡 H=194 三集（E1/E2/E3），门 24/23/23ms，**topY=810 全程恒定**，fiso 2372↔2566（±194 精确），item23 尺寸 482↔288（±194 精确）。
- **工具卡（无 cacheKey，同 CardExpandReveal 底层）**：bash 卡 H=259 展开集 topY=1105 恒定、fiso +259/host +259 精确；收起镜像回退 2566 精确。门 24/24ms 两集均生效。
- **快速连点**（300ms 间隔三连点）：三集顺序完整完成（281/255/281ms），零 CANCELLED、零漂移。

**仪器边界（诚实记录）**：
- 真·飞行中重定向取消（<250ms 间隔）：`input tap` shell 注入时延 ~272ms（memory 实测），两次点击物理上压不进一个集内——该竞态面无法机器触发，归用户真手指域。
- DSH 域等价性说明：脏样本出自 DSH tab，本轮在 v1 会话验证；CardExpandReveal 展开几何为共享 Compose 层，与服务器风味无耦合（DSH 特有的投影/part 派生不进入集内几何路径）。
- 流式降级路径：门结构性不可达（降级分支在 clock/episode 之前 return），代码可检，无需 E2E。

**累计矩阵**：7 场景 11 集（24k 巨卡 4 集 + 折叠链思考卡 3 集 + 工具卡 2 集 + 连点序列内含 + fling 余尾），全部 topY 恒定/±H 精确/门 10-25ms 生效/零异常退出。

## §3 5h 拟人 soak 启动（2026-10-08 04:39，用户 /goal 裁决：全绿→关闭 #529；有问题→根因修复循环）

**基建**（86b1868e，复用 #511 10h 基建全部加固件）：`driver-529.py` 六节律（回收E1/连点循环/阅读翻看/速点重定向/多卡堆叠/离开发呆，全部拟人抖动+滚动找卡）×宿主 v1:4199 两个富思考卡会话按权随机；`analyze-529.py` 多维判读（逐集 topY/收起镜像对账/fiso↔H 数学/静止门分布/negRESIZE 收起配对归因/#484 健康签名）；无线 adb 自愈内建。

**smoke 验证（0.15h，17 周期 37 集）**：判读器两处缺陷当场修正——①groups() 索引 0 基误当 1 基（我引入的回归，合成样本集抓出）；②同 item 相邻两卡同 E1 号被 merge 成假脏段（fiso/host 连续性+topY 跳 232=两卡间距定罪=判读器误报，加「落后复升」切割）；negRESIZE 9 条全部与收起集配对（−458=H，收起设计行为，10h 流式 soak 无收起故基线 0）。终判：**21 集 0 脏、门 37/37 stable=2 p50=24ms、镜像 15/15 精确、PASS**。驱动器两缺陷修正：swipe 少参（TypeError 当场抓）；卡行 y 过滤 2200 误杀 2338 的真卡行（定向点击放宽 2380）。

**5h 正式跑**：pid 678475，started 04:39，deadline 09:45（progress.json 为准）；巡检 cron 每 40min（死亡复活/增量判读/脏即取证定罪/终盘多维判读+Room 直查+关卡处置）。smoke 产率 ~140 集/h → 预计 ~700 集。

## §3.1 巡检 04:36

- 04:36 巡检：alive pid=678475，8 周期/181 卡发现/32 PLACED；增量判读 PASS（8 集 0 脏、门 14/14 stable=2、镜像 3/3、negRESIZE 6 全配对、零 THREW/FATAL）。

## §4 soak 域内第二缺陷：抬底过期塌缩（04:42 单帧定罪 + 根修）

**定罪**（04:47 巡检 negRESIZE 未配对 7 条，全部同 item t_msg_0e36bd4aa001、d 恒 −4490）：
- 04:42:54.871 RESIZE 15982→11492（−4490，手势中）→ 04:42:55.016 steady-report d=4490 → .018 RESIZE 11492→15982（+4490）——147ms 全高塌缩回长；
- 04:42:59 实证 `[DEBUG-FLOOR] cold-floor effH=9558 measured=0 count=1/2`（同 item 另一卡）——**回收-展开态重组合后重内容异步组合逾经典冷窗**：count 过 2 后 floor 失效、内容 measured 仍 0 → 盒上报 0 → item −H 手势中塌缩 → 落地 +H 回长 = 可见闪跳。既有缺陷（01e4ba2b 冷窗设计），非静止门所致；同族第三位（展开竞态/收起竞态/抬底过期）。
**根修（消除源）**：`coldFloorEligible(fraction,animating,count,measuredH)`——窗口=经典冷窗(count≤2) ∨ **内容未组合(measuredH==0)**。零高绝不可能是 finalHCache>0 展开卡的合法终态→零高续抬无陈旧风险；内容合法变矮（>0）仍由 count>2 真测接管走 steady（旧行为保留）；animating 期照旧不抬（抬底不进锚点基维持）。
**验证**：ColdFloorEligibleTest 5/5 + AnchorQuiesce 8/8；全量套件另行留痕；新 APK 已装真机。按目标循环：旧 soak（04:32-04:53，21min/63 集 0 脏）作废，重启全新 5h。

## §5 run2 5h soak 终盘（04:57-09:58 完整跑满，2026-10-08 12:0x 判读收尾）——全绿关卡

**规模**：601 周期 / 678 展开+624 收起点击 / 7754 卡发现 / 2134 PLACED 帧 / 568 反射 / 310,985 日志行（孤儿期截断后）/ 54 证据文件（35 截图+19 录屏 181MB）。

**逐维度判读（终盘 PASS, analyzer exit=0）**：
- 集机制：228 展开集+331 收起集全净，**0 脏**（1 集尾切片窗脉冲巧合单列归因）；收起镜像 req↔post 463/463 精确；fiso↔H 对账 0 失配。
- 静止门：1064/1064 stable=2（p50 24ms / max 29ms / 0 超时）——门在 5h 全节奏下无一次超时捕获。
- 生命周期：exit-CANCELLED 8（滚动取消，位置优先权语义，预期）/ **THREW 0** / dispatch-threw 0 / **FATAL 0 / ANR 0** / RESETKEY·GUARD·nonPrefix·RESERVE 全 0。
- negRESIZE 479 全归因：收起配对塌缩 + 13 切片窗瞬态脉冲（SliceHost 域，登记 #533：86ms-83s 跌后回长、卡态/内容零丢失、Room 佐证）。
- Room 直查（db+wal+shm 三件套）：integrity_check ok；目标会话 parts 481 / **reasoning 78=服务端基线精确一致**（内容在 payload 最大 20.5KB）；全库 5817 parts 无损坏。
- 视觉：尾段截图核验正常（思考卡/工具卡/输入栏完整渲染）。
- 全量单测：**3920/3920 全绿**（含 ColdFloorEligibleTest 5 + AnchorQuiesceTest 8）。

**判读器终修三件（伪影全清零）**：①logcat 轮转新进程倾倒缓冲→相邻 cap 重叠（跨文件行去重）；②实例A E1-展开→滚动数分钟→实例B E1-收起同 E 号跨分钟 merge（帧间 gap>3s 切割）；③MDResize 正则格式失配（`MDResize(22216): card=`）——negMDResize 历史计数全为真空值。孤儿 logcat（python 退出子进程存活）按驱动 ended 截断。

**结论**：#529 修复（静止门 1efbce1a）+ soak 域内第二缺陷根修（抬底过期 96464327）后，5h 拟人 soak 全维度全绿。#529 关闭；切片窗脉冲域独立登记 #533（P2，既有缺陷）。

## 已完结卡片迁入（2026-10-08）

### **#529 思考卡展开偶发高度跳变（E1 新实例竞态窗）** `uiux` `scroll` `race`
  - 用户 21:11-21:12 实测两脏样本：均为 E1（回收重组合后首个展开集）topY+H/+H/2 位移，一例集后还有 +198 校正帧；E3+ 全干净
  - PLACED 探针已增补 boxH/host 两字段；15 轮机器复现未触发（需真手指滚动余尾+速点节奏）；嫌疑=finalHCache 抬底/预热/重组合在锚点捕获窗内的交错
  - → journal: docs/journal/2026-10-07-dsh-standard-bugs.md
  - 观察窗（用户裁决 2026-10-07）：至 2026-10-14 无跳变反馈即完结；期间再遇立即 logcat 取证
  - 根修已落地（1efbce1a 2026-10-08）：锚点捕获静止门——展开/收起反射锚点捕获前等滚动静止+整列基指纹计稳（有界200ms）；抬底理论已勘误排除（boxH 探针=内层自然高非槽位）；真机 E1/E2/E3/E4 四集全绿 topY 恒定
  - 观察窗照旧至 2026-10-14：机器节奏本就复现不了脏样本，终验=用户再遇或期满；再遇时 grep 加 DEBUG-529 签名可分辨门超时 vs 新机制
  - → journal: docs/journal/2026-10-08-529-expand-anchor-quiesce.md
  - 迁入依据：用户 /goal 裁决关卡：根修（1efbce1a 静止门+96464327 抬底过期）后 7 场景 11 集系统性真机 E2E + 5h 拟人 soak（run2, 601 周期 559 集）全维度全绿（0 脏/镜像 463/463/门 1064/1064/Room 78/78/零 THREW·FATAL/3920 单测）；soak 域外发现独立登记 #533（backlog.sh migrate 2026-10-08）
