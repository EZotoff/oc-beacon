# 471-3-finalize-normalization-reflow（2026-09-30）

> 状态：进行中
> 关联：（spec 路径，若有）·（issue 编号，若有）
> 来源：用户反馈 / grilling / E2E / 顺带发现

<!-- 过程中的取证/验证证据直接写本文件；backlog.md 只留 ≤3 行卡片。 -->

## §1 调研与设计定案（2026-09-30；spec 为权威：docs/specs/2026-09-30-471-3-streaming-normalization-unification-design.md）

- 代码级全景调研：归一化 5 变换本体 + 全部 5 消费点（async 终态/parseAsync/小文本同步/libStreaming fallback/RenderReadiness.preParse）+ pilot 包装器与 SafePrefixGate 全 516 行 + markdown 域全部 17 个测试文件
- 定位冲突①裁决原文（StreamingMarkdownPilot.kt:66-68，#265 时代取舍）及其解锁钥匙：单调性只需对已放行前缀成立，归一化回改点全部落在 gate 扣留区（$$/☐☑✅/|/反斜杠/[] 皆活动标记）
- 调研两项新发现：① libStreaming fallback（pilot 关闭时）本就在流式侧做 normalizeForRender——逐快照归一化有先例；② gate injectTableBlankLines 即同判定前移的先例，本批泛化后退役
- 设计关键推演：位置制空行化必须「cumEnd(行 j)≥3000 且行完成时即定案」（不能等下一行存在，否则已放行换行被回改）；语义变更 = ≤3000 段落不拆 + >3000 头部整块+尾部渐进拆（稳定接缝），SplitOversizedParagraphsTest 语义测试需重写
- 影响面：主代码 3 文件（MarkdownContent/StreamingMarkdownPilot/SafePrefixGate）+测试 4-5 文件；ChatScreen/高度引擎/HeldTailReveal 零触碰；三 commit 计划见 spec §5；可关卡片清单见 spec §8（#471 整卡验收清单、#437 铁律收编 +1 域）
- 用户裁决链：方向批准（终帧=流式帧）→ 管线示意图确认 → async 保留确认（静态渲染本职：分片/内存/历史消息三硬理由）→ 调研+to spec 指令
