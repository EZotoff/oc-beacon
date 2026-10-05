# #509 残余深修 handoff——数据层身份稳定化（方案 B，用户裁决 2026-10-03）

> 交接前提：本文自含全部前因后果，供压缩/新会话直接开工。前置修复已推（`35b9365d`），
> 本文档只覆盖**残余问题**（第三层：条目身份翻覆）。#509 卡片与 journal §27/§28 是证据正本。

## 1. 现象（用户主诉）

带 markdown 表格的回答轮，流式输出完毕的瞬间（TurnFin），整条消息（约 2600px≈一屏）
空白 **350-700ms** 后整体弹回。纯文本轮无恙（4ms 换装）。用户原话观感：
「一会儿便秘输出，一会儿大段内容突然出现」。

## 2. 前因后果（三层，按发现序）

### 第一层（已修）：权威转写增量重放
DSH 桥把完结转写**当 delta 序列重放**：消息 text 走 `4→5→15→…→1182ch`（~380ms）。
重放中途消息 completed 位翻转 → 渲染分支从 pilot 切到 fallback → fallback 同步渲染
当前的 4 字 → 条目高 2197→182px 塌缩。
**修复**：`CompletionHandoff.replayHoldCandidate`（重灌中间态=终文短前缀，余量>256）
+ MarkdownContent replayHold（pilot 分支保持+freeze 冻结旧内容+5s 帽+LRU 防污）。

### 第二层（已修）：小文档主线程同步解析
非重放（原子到达）时，<2048ch 走 `syncSmall` = remember 内 `parseBlocking`。
文本 1-3ms 无感；**12-14 行表格 =350ms 主线程阻塞**，期间条目空白。
**修复**：换装指纹命中（takeIfMatches）轮次取消 >2048 长度门，改 asyncTerminal
（Default 线程解析 + holdPilotTerminal 保持 pilot 到就绪），主线程零解析。

### 第三层（本 handoff 目标）：条目身份翻覆 → 子树销毁
两修复后表格轮仍空白 350-700ms。定罪证据（v2a 轮，13:41:39-40，fix.log）：

```
39.57 TurnFin
39.61 RESIZE h 2571→0 (d=−2571)          ← 塌缩
39.61 ItemP enter key=u_seq-… / t_seq-…   ← 条目（重新）进入组合
39.96 path=render src=asyncTerminal len=1249  ← 新条目首次渲染（第二层修复已生效于此）
40.28 RESIZE h 120→2595 (d=+2475)        ← 弹回
40.31 ItemP leave key=u_seq-… / t_seq-…   ← 再次销毁（REST 刷新二次换装）
```

**链条**：消息 id 在「合成 id（流式期 dsh-t{turn}s{step} 等）→ 权威 seq id →
L3 REST 刷新再分配」链上翻覆 ≥2 次；条目 key（`chatEntryKey` → `t_ + 槽位锚`，
槽位锚=轮 user 消息 id，#440）随 id 漂移 → LazyColumn 视为删旧加新 →
**整个 item 子树 dispose 重建** → 全部 `remember` 态（pilotEverRendered、pilot
累积内容、async 终态、换装指纹槽的槽位卫）归零 → 第一二层修复被结构性 bypass。

### 为什么只有表格轮可见
文本轮：新子树解析快（1-3ms）+ 多数翻覆一次完成 → 空窗 <1 帧。
表格轮：新子树首组合含表格解析（350ms）+ 布局测量（310ms）+ 全列表重组合湍流
（entries n=142）→ 空窗 670ms，肉眼可见。

### 三协议共性（回答「底层都这样吗」）
- 失效面协议无关：①条目 key 锚不稳定 id；②归并引擎 + L3 REST 校验/刷新循环
  （app 侧机制，`L3 REST message refresh` 日志三协议都在跑）。
- churn 强度：DSH 3 段（合成→权威→REST）＞ V2 1-2 段（part 生命周期 + REST）
  ＞ V1 1 段（`pending-` 乐观播种换装，computeTurnAnchors 注释在档）。
- 方案 B 修在归并/身份层 = 三协议同治。

## 3. 方案 B：数据层身份稳定化（已选，方案 A 内容指纹锚暂缓）

### 目标
消息与 part 在合成→权威→REST 全链路保持**同一逻辑 id**（或提供稳定映射），
使 LazyColumn 条目 key 在毕业换装全程不变，子树不销毁，第一二层修复自然生效。

### 主战场（文件地图）
| 文件 | 角色 |
|------|------|
| `data/mapper/MessageMergeEngine.kt` | part 快照合并（#505 endsWith 去重已撤）——权威替换/重放的应用点 |
| `data/event/MessageEventHandler`（写入路径 upsert/sortBy） | 消息 id 变更的入口：合成→权威替换在此发生 |
| `ChatRepositoryImpl` / L3 REST 校验刷新链 | `L3 REST validation/message refresh`——第三次翻覆源 |
| `ui/…/ChatMessageList.kt: chatEntryKey()` | 条目键派生（t_ + 槽位锚） |
| `ui/…/TurnGroupCalculator.kt: computeTurnAnchors()` | 槽位锚=user 消息 id（漂移点） |
| `domain/model/PartIdContract.kt` | swapStableKey（UI 级补丁，B 完成后可评估退役） |

### 已在场的既有资产（勿重复造）
- #504 `CompletionHandoff`（内容指纹 LRU×4 + takeIfMatches/replayHoldCandidate）
- #440 槽位锚（现锚 id，B 的直接受益者）
- #485 归并失序防御（envelope 穿透等）——**B 的高风险邻区**，动归并前先读 journal #485 节
- 探针网：ItemP（条目进出）、ScrollDiag RESIZE（高度）、A11yDiag path=（渲染分支）、
  MDPilot、504-forensic、TurnFin——验收全靠它们

### 设计约束（硬性）
1. 逻辑 id 稳定 ≠ 拒绝服务端真删改：用户真实删除/重命名/重生成消息时 id 变化必须
   仍能正确重建（不能把删除吞掉）。
2. 合成→权威映射需在**事件到达即建立**（第一帧前），否则首帧仍闪；REST 刷新与
   SSE 流竞态（#485 教训：归并失序把 user 挤到 envelope 下方）。
3. `pending-` user 乐观播种（三协议共通）与 DSH 合成 part 是两个不同的合成源，映射层都要盖住。
4. Room 持久层 id 是主键——稳定映射建议在内存层（eventDispatcher/repo 侧）做
   logical-id ↔ wire-id 解析，不动库 schema（动 schema 需迁移，另批裁决）。

### 风险清单
- 归并引擎是 #485/#505 事故高发区：任何「按内容匹配替代按 id」的路线都有误并风险
  （两轮同文提问）。映射必须由**换装事件序**（同一 turn 的 part.created/替换序列）建立，
  内容匹配只做校验不做主键。
- L3 REST 是权威纠错机制——稳定化不得让它失效（刷新发现真差异时仍要落地替换）。
- 回归面：V1 pending- 换装、V2 delivery=queue/steer、DSH 毕业重灌、#507 turnGroups
  结构缓存签名（id 序列变化触发重建——id 稳定后签名也要稳定，否则缓存失效风暴）。

### 验收标准（全部可仪器断言）
1. 表格轮毕业窗：ItemP **零** u_/t_ leave+enter；RESIZE 无 d<−800；
   条目高度单调（允许输出驱动正增长）。
2. v2a 复现脚本：`curl 注入「输出一个 14 行 5 列 markdown 表格+400 字总结」→ 贴底
   跟随 → 抓 TurnFin±3s 的 ItemP/RESIZE/A11yDiag`。
3. 全量单测 + 回归：文本轮毕业 4ms 基线不退化；#507 冻结条目、#505 换装门、
   #503 宽限窗用例全绿。
4. 三协议抽测：V1 lighthouse 原生发送一轮；V2（4096）一轮；DSH 表格轮×2。

### 环境备忘
- DSH 桥：`http://127.0.0.1:3080`，curl 注入带 `-b /tmp/490_cookies.txt`，
  body 模板见 journal §27（session/prompt client-request）。
- 矩阵会话：`session-7dbfd559-308a-4b01-b9a8-f215bc21eed8`（DSH-3080 下）。
- 真机：houji，USB serial `e69a99d8`；USB 断开后无线 serial
  `adb-e69a99d8-yzT17Y._adb-tls-connect._tcp`（reverse tcp:3080 需在无线通道重挂）。
- 入口纪律：`./scripts/debug-entry.sh`（4199）；DSH-3080 用 am start debug intent
  `--es debug_url http://127.0.0.1:3080 --es debug_name DSH-3080` → 会话列表 → 矩阵行 (403,623)。
- 测试期**禁改系统设置**（用户裁决）；a11y 陈旧树三态与自愈法见 journal §20。

## 4. 决策记录
- 2026-10-03：方案 B（数据层身份稳定化）由用户裁决采用；方案 A（内容指纹锚）暂缓。
- 前置：第一二层修复 `35b9365d`（replayHold + 换装桥全长度 asyncTerminal）已合入。
- 本 handoff 后用户将压缩会话再开工——新会话从「主战场文件地图 + 设计约束」直接起步。
