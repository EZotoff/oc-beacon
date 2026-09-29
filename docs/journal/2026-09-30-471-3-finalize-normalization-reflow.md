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

## §2 实现（2026-09-30，commit A 276d8fbf / commit B 9130c83e）

- **commit A（纯函数+TDD）**：normalizeMarkdownCore（CRLF 快路径+表格空行+数学降级，无身份分支）/ normalizeForStreaming（pilot 专用）/ normalizeForRender 重构（同源组合，render==streaming 逐字节不变量入测）/ splitOversizedParagraphsByPosition 位置制空行化（cumEnd≥3000 行完成即定案；旧 splitOversizedParagraphs 删除）
- **性质测试（NormalizationStreamingMonotonicityTest）抓出四个真实破口**——spec §3.3 矩阵的论证漏洞，全部修复并钉死：
  1. gate 返回处尾 $ run 收口：$ 逐字符到达时首字符以「单美元=纯文字」直出、次字符以行续段身份再放，跨批凑成 $$ 后闭合字符到达触发归一化改写（math-block fixture k=21/22 逐步实证）；收口=截点在快照尾且以 $ run 结尾时退过整个 run
  2. gate 表格三处（族续放/表头分支/正文行循环）排除含 $$/\[ 行：块级数学跨行配对面，完整行 ≠ 定案
  3. gate 表头行待定三行结构回退：空行毕业先放「文字行\n表头\n\n」，分隔行后到时 ensureBlankLineBeforeGfmTables 在文字行与表头间插空行（random 轮 k=318 实证）；回退到表头行首，走表头分支或 EOF flush
  4. 归一化侧两个**存量渲染缺陷**兼修：① ensureBlankLineBeforeGfmTables 无围栏意识——栏内表格形态（代码字面）被插空行；② 三变换围栏跟踪与 gate 分歧（栏内「```xxx」误闭合 + 反引号栏 info 不查反引号）——统一为 MarkdownFenceLine 共享判定（gate/CommonMark 严格语义）
- **commit B（接线+退役）**：rememberPilotStreamingMarkdownState 顶部 normalizeForStreaming（prev/released/heldTail 坐标全归一化；heldTail 显示 - [ ] 预览/tex 围栏行=WYSIWYG）+ injectTableBlankLines/prevLineBeforeIsTextRow 删除（releaseDelta 退化 substring，state.content 与快照坐标重新耦合）+ 冲突①裁决 KDoc 重写 + gate 注入正例测试迁移
- 全量回归 **3716 绿**（基线 3700 + 16 新增）

## §3 真机 E2E（小米 houji 无线 192.168.110.239:36339；dev debug；v2 4096 反向端口；LongCat 2.5 Preview Free）

- **E1**（任务+$$ 公式+贴边表格轮）：完结态 tex 围栏块/表格（双列渲染）/任务行渲染正确——归一化生效；裸「☐ 文字」行首无列表标记属 task 变换既有语义（流中/完结一致显示 ☐，非本批回归）
- **E1b**（- [ ] 列表诱导 + >2048 essay 轮，2664 字符）**核心判据全过**：完结序列 = gate release held=**0**（归一化全文已放行，pilot 终帧=全文归一化文本）→ path=pilot hold=true ×2（**85ms** 桥接窗）→ path=render src=asyncTerminal **Success 直达**——无 Loading→Success 弹跳双帧（对照修复前同场景 -13171/+12824 族与 #472 残余 ±24px）；flap suppress=0；2672（归一化后）vs 2664（原文）= 8 字符空行插入自洽
- **E2**：与 E1b 同轮覆盖（>2048 完结换装无塌缩/弹开）
- **E4**：gate release 全程节奏正常（首批铺开+小批直通），无扣留风暴/无非前缀重建
- **E5**：流式批次间隔 ~90-100ms（48ms 预算节奏内），无 ANR/无渲染期 AnrScout 报警
- **E3（>3000 单段）取舍记录**：真机诱导 3000+ 单段成本高——以位置制单测（截断前缀稳定/幂等/精确边界）+ E1b 长段（716px 连续大段）视觉档案替代；**用户验收清单保留此项**（spec §9 第 3 条）
- 截图档案：/tmp/e1b_final.png（本机临时，未入 git）；logcat 全程 /tmp/e1b.txt、/tmp/e1full.txt
