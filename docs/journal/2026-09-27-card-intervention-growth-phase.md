# 2026-09-27 流式中卡片介入验证（#437 系 handoff 接力）——增长期 episode × 流式 flush 并发裁决

## 背景

接力 `handoff-oc-beacon-card-intervention.md`（原 session 环境全失：旧家目录 /home/leo-tkp 未迁移、SSH 私钥//tmp 资产/V1 server 均丢失）。核心缺口：增长期并发点卡的真实行为（原 session 唯一成功取证 EP2 落在模型思考停顿窗，不构成增长期证据）。

## 环境重建（0→1）

- git：SSH 私钥丢失 → origin 切 HTTPS（匿名 fetch ✓）；身份从旧 commit 恢复（leonardo <tfr971018@163.com>）；本地 == 远程 d9cc5d08，未推送内容仅 backlog 三卡。push 凭据待用户提供（本轮未解决）
- backlog：#447/#448 两卡在迁移中丢失，按 handoff §10 描述补登记（commit e4da06f5 `docs: backlog #446/#447/#448`）
- V1 server：opencode-ai@1.18.32 装至 ~/oc-v1（npm prefix 隔离），数据/配置隔离于 ~/oc-v1-env；zhipuai key 从 2.x opencode.db credential 表提取内联（不入仓）
- adb：platform-tools 下载至 ~/android-platform-tools；真机无线 mDNS 名直连（含 ' (2)' 后缀条目）

## 方法学突破（handoff §9 三卡点的解法）

1. **卡点二（视口无法离底）→ 破解**：根因是**手势方向 × 坐标系**双重错误——reverseLayout 中手指**下滑**才是向旧内容推进（上滑撞内容开头边界被 SafeFling zero-consume 拦截，EP7/EP8 的坐标起/终点又撞底部输入栏）。正确配方：`input swipe 720 700 720 1900 500`（起点必须在列表区 y<2200，避开 ~2387 起的输入栏）
2. **卡点一（dump-tap 延迟点空）→ 破解**：真机上 `input tap`（DOWN/UP 同帧 0ms）被列表 scrollable **系统性丢弃**（真手指正常、MIUIInput 显示事件已送达窗口）——注入配方：`input swipe x y x y 100`（100ms 同点按压），toggle 100% 触发
3. **增长期检测**：GrowthWatcher 线程 tail logcat 匹配 `ScrollDiag.*RESIZE t=\d+ key=(t_msg\S*) h (\d+)->(\d+)`（注意 t= 参数在 key 之前，原 handoff 正则不匹配）

## 时序规律（决定实验成败）

- **流式开始时 app 主动滚底**（EP13 实证：LEAP idx7→0 dOff=-38225）——「先离底后发消息」必被拉回；**必须在增长确立后跳转**（EP14 配方）
- **快速定位跳转不关 autoOn**：autoOn=true 的离底视口在增长期被 MSGEFFECT 持续锚回贴底；跳转后**微下滑 140px 关闭 autoOn**（drift 实证 autoOn=false）→ 视口真锁定
- V1 server 长跑后 provider+event 双通道齐挂（健康端点正常、直连 zhipuai 正常）——干净数据目录重启即愈；session 串行：前 turn 未完时新消息排队饿死

## 核心证据（三场景矩阵）

### 场景 1：流式 turn 内思考块 toggle（08:23:17，CARD-EP10）

```
RB-EXP toggle -> true
RESIZE t_msg h 6581->6972 (d=+391)      ← 思考正文展开高度
SGR-435 release capd=391 led=0 set(fii=7,fiso=5542) h->7020   ← 走流式 ledger 通道
```
**不走 episode**（零 CardExpand 行）：流式 item 内的卡片高度变化被 StreamingGrowLedger 单通道吸收，与增长批同链串行。

### 场景 2：离底读历史 toggle 完结 turn 思考块（EP14，CARD-R2）

```
P1 展开：close 无前置（open 方向）→ RESIZE +720 → LEAP off 51489→52209 → close+2f → episode done 261ms completed=true
P2 收起：close-pre rep=720 meas=720（配对目标=实测）→ close-anchor-request fii=9 fiso=51489 → close-post → LEAP -720 → RESIZE -720 → episode done 333ms completed=true
```
**episode 全链精确配对**；且 episode 窗口（0-350ms）内流式批 = 0、窗口外 ±1.5s = 0——**turn C 距视口 50000px 不进 LazyColumn 组合窗口，流式 flush 对其静默**。

### 场景 3：过渡区（EP2，handoff 既有证据）

episode 窗口内 RESERVE flush 无并发记录——共享 PreRenderCoordinator 单点串行化。

### 录屏帧差互证（cv2 中列带 1D 互相关，阈值同 scripts/frame-jump-analyze.py）

| 录屏 | 位移型突跳(>480px) | 归因 |
|---|---|---|
| card-ep13.mp4 | **0** | toggle 期间零视口跳变 |
| card-ep11/ep12/full2 | 2-4 | 全部对应主动操作（跳转/流式开始滚底 LEAP），非 episode |

## 结论（handoff §14.3 → 结局 A：无双发、配对正常）

**「episode ΔH_card 与 ledger ΔH_stream 同帧双发」在实测中不成立**，机制三层：
1. **空间分离**：流式 flush 只对组合窗口内的流式 item 活动；用户离底所读旧卡所在 item 与增长源被 LazyColumn 组合窗口天然分区
2. **单通道吸收**：流式 item 自身的卡片 toggle 走 SGR ledger（场景 1），不产生独立 episode
3. **单点串行**：过渡区共享 PreRenderCoordinator flush 单点（EP2）

**不需要改生产代码**（ScrollCompensation.kt 的理论缺口 §6.2 被实测证伪其可触发性）。

## 新发现（待裁决/记录）

1. **快速定位跳转不关 autoOn**：增长期视口被 MSGEFFECT 锚回贴底（对「跳转=读旧消息」的意图而言疑似 bug；#435「锚即意图」语义下跳转应关闭跟随）——已立卡 #449
2. **流式开始自动滚底**（LEAP 巨量跳底）：产品行为，离底读旧消息的用户会被拉回——同 #449 一并裁决
3. V1 1.18.32 server 长跑双挂（provider+event）：环境记录，不立卡

## 资产沉淀

- `~/card-lab/run-card-probe.py`（GrowthWatcher + full4/full5 全流程）
- `~/card-lab/tap-text-phone.py`（100ms 按压注入 + --last/--index）
- `~/card-lab/mp4-frame-jump.py / jump-classify.py`（cv2 直读 mp4 突跳分类）
- `~/card-lab/peek.py / window.py / analyze-density.py`（取证辅助）
- 录屏与日志：~/card-lab/card-ep1[1-3].mp4、card-full-ep1[1-4]-*.log

## 勘误：#449 归因修正与完结（2026-09-27 用户质询后深查）

用户质询「流式中视窗不含末条消息就不会被自动下拉？好像有参数？」——深查代码+补充实验（EP15/EP15b）后确认**用户记忆正确，#449 原归因错误**。

### 机制三层（非配置参数，是历史修复形成的组合）

1. **MSGEFFECT/GUARD 流式静默**：ChatScrollController 的 `!streamingActive()` 门控（#437 验收六轮定规「流式中静默——物理跟随+配对 set 各司其职」）
2. **SGR 离底免派发**：StreamingGrowLedger drop(reading-away)（#435「锚即意图」）
3. **reverseLayout 物理特性**：贴底视口才被内容增长自然推动（框架自带）

### 实测补充（EP15/EP15b 真机）

- 非流式 + autoOn=true + 快速定位跳最早 Q：9s 内零 GUARD reanchor、零 MSGEFFECT anchor、视口纹丝不动
- 非流式 + 预下滑关 autoOn + 跳转：同样零拉回

### EP13 LEAP -38225 真因（替代原「MSGEFFECT 锚回」解释）

turn 边界 user 消息插入 → 旧 turn item 坍缩（RESIZE 40342→255，d=-40087）→ 视口 offset 38225 越界 → **LazyColumn 框架 clamp 弹底**（一次性结构事件，随后 item 弹回 39491）。EP14 因跳转发生在结构稳定后而不受影响。

### 用户裁决

「流式开始滚底」不是问题（所有聊天 app 都这么做），维持现状。#449 双裁决项均关闭，卡片完结迁移。
```
09:31:22.895 RESIZE t_msg turnB h 40342→255  (d=-40087)   ← item 坍缩
09:31:22.951 drift atBot=true idx=0 off=0                   ← 视口被 clamp 弹底
09:31:23.167 RESIZE t_msg turnB h 975→39491 (d=+38516)      ← item 弹回
```

## 已完结卡片迁入（2026-09-27）

### **#449 快速定位跳转未关闭 autoOn,增长期视口被 MSGEFFECT 锚回贴底** `streaming`
  - EP13/EP14 实证:快速定位跳到旧消息后 autoOn 仍 true,流式期 MSGEFFECT 持续把离底视口拉回底部(LEAP idx7→0 dOff=-38225);另流式开始时 app 主动滚底(同类家族)
  - #435『锚即意图』语义下『跳转=读旧消息』意图应关闭跟随;当前需用户额外下滑手势才能锁定视口(实验中以微下滑 140px 规避)
  - 取证:docs/journal/2026-09-27-card-intervention-growth-phase.md(EP13/EP14);裁决项:a) 跳转关 autoOn b) 流式开始滚底是否保留
  - [勘误·归因修正 2026-09-27 用户质询后深查] 原告『跳转不关autoOn→流式期被MSGEFFECT锚回』不成立:①流式期MSGEFFECT/GUARD全静默(ChatScrollController L177/L231 !streamingActive()门控,#437验收六轮定规)②非流式跳转autoOn=true实测9s零拉回(EP15b真机:GUARD/MSGEFFECT anchor零出现,视口纹丝不动)③EP13的LEAP -38225真因=turn边界user消息插入引发旧turn item坍缩(40342→255px)→LazyColumn视口offset越界被框架clamp弹底(一次性结构事件,先塌d=-40087后弹d=+38516)
  - [用户记忆验证]『视窗不含末条消息就不会被自动下拉』正确——机制=reverseLayout物理特性(贴底才跟随)+SGR drop(reading-away)离底免派发+MSGEFFECT/GUARD流式静默三层;非配置参数,是#435/#437/#301历史修复形成的机制组合
  - [用户裁决 2026-09-27] 流式开始滚底(实为turn边界item坍缩clamp的框架行为+贴底正常表现):不是问题,所有聊天app都这么做,维持现状
  - 处置:原告双方均证伪/裁决维持现状→完结迁移journal
  - 迁入依据：归因修正(用户质询深查):流式期MSGEFFECT/GUARD静默+SGR离底免派发,原告证伪;用户裁决流式开始滚底维持现状（backlog.sh migrate 2026-09-27）
