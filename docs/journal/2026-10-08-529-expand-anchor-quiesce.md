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
