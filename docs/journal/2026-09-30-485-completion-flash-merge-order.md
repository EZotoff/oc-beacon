# 485-completion-flash-merge-order（2026-09-30）

> 状态：进行中
> 关联：（spec 路径，若有）·（issue 编号，若有）
> 来源：用户反馈 / grilling / E2E / 顺带发现

<!-- 过程中的取证/验证证据直接写本文件；backlog.md 只留 ≤3 行卡片。 -->

## 现象与定罪链（真机 18:31:42 全量 logcat）

用户主诉：轮次完结收尾前，内容突然消失 ~350ms 再重现。真机 18:31:42 窗口完整链条：

1. 18:31:40.746 完结换装成功（asyncTerminal len=2634 Success，#472 hold 桥接正常）
2. 18:31:41.946 完结触发 listMessages 全量刷新（V2 列表响应不含 parts）
3. 18:31:42.037 HFLICK PLAN n76->77 rem=[u_0c34,t_626b] add=[u_0f1debb34,t_0c11,t_628d]——条目键翻转
4. 18:31:42.077 src=asyncTerminal Loading（新子树全新 state 实例，≈0 高）＝闪灭帧
5. 18:31:42.094/42.338/42.752 MDResize 8382→200→5452→8802（子树换血三连重测）
6. 18:31:42.434 preParsed Success＝内容回来

服务端真相（API 直查）：V2 一轮产 4 条消息 agent-switched/user/assistant/idle（created 单调差 22-52ms 级）。

## 根因（两层）

- 数据层：V2SseMapper 把消息 created 写成信封时间——user 回显信封比 REST 持久化 created 早 13-50ms（admit 与 persist 的服务端时序差）。mergeSortedMessages 契约「合并行保持 existing 原位」——user 行内容被 REST 权威替换（created 前跳）却留在 SSE 时期槽位 → 列表失序（user 冒到 agent-switched 上方）。次轮刷新二路归并反向行走 → 顺序自愈 → t_ 键振荡（18:30:36 t_628d→t_626b、18:31:42 翻回）之谜由此精确复现（代码级推演与日志逐条对上）。
- 键层：computeTurnAnchors 取 Older 侧相邻非 assistant 为锚——失序时相邻变成 agent-switched 信封（buildChatEntries 跳过不渲染的不可见行）→ t_ 键 = 信封 id。

子树换血后 #472 的 pilotTerminalHold/preParsed 防线全失效（pilotEverRendered 与 state 实例随子树一起被弃）——async 是闪灭的可见层，不是根因。

## 修复（commit 见 git log）

1. MessageMergeEngine.mergeRestSnapshot：REST 快照归并后按服务端 created 稳定重排；SSE_PRIORITY/REST_AUTHORITY 两策略接线（refreshMessages/分页回补/L3 兜底全走此路）；APPEND_ONLY 种子路径不动（Room 读序自洽）。
2. computeTurnAnchors：锚点穿透 SYNTHETIC_ENVELOPE_ROLES（agent-switched/model-switched/shell）信封锚到真实消息；idle 是轮末书签+可见 u_ 条目兼轮界，不穿透（防极端失序跨轮抢键）。

## 验证

- TDD 红→绿：MessageMergeEngineOrderTest（失序复现/双刷新不振荡/user 行 REST 权威 created）+ TurnGroupAnchorStabilityTest（有序锚 user/失序穿透信封/不跨 idle 界）。夹具时钟形状对齐 wire 实测（user 回显偏斜 13-50ms、assistant step.started ≈服务端钟）。
- 全量单测绿＋assembleDevDebug 装包。
- 真机 E2E 两轮（2053/3761 字，均 >2048 走 asyncTerminal 换装）：完结刷新 PLAN add-1-rem-0（仅 idle 气泡）零键移除、asyncTerminal Loading=0、pilot→hold→Success 直达→刷新后 preParsed Success、子树从零重测=0、RESETKEY/nonPrefix=0。修复前同场景对照：rem2+/add3+、Loading×2（~360ms 空白）、d=+5452/+8602。

## 残余与边界

- assistant 行 created 仍信 SSE（mergeMessageMeta 完结信 SSE 语义刻意保留）——信封≈服务端钟且服务端 user→assistant 固有 ~20ms 间隔，实际不可越界；若未来信封抖动越界，锚点穿透已兜住主要形态。
- #484 滚动 3/4 高度坍缩为独立 bug（抓包 bash-1563 持续运行中），与本卡无关。
