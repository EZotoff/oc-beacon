# #146①⑤ 客户端消费调研（2026-10-10）

> 只读调研（后台调研 agent 执行，主会话整合）。对象：backlog #146 剩余子项
> ① compaction 压缩生命周期事件、⑤ outputPaths/truncated 字段的客户端消费排期。
> 路径约定：`K = app/src/main/kotlin/dev/leonardo/ocbeacon`；`D = docs`。行号为 2026-10-10 HEAD 实测。

## 结论速览

- 客户端**双轨解析已存在**（新命名 `session.next.compaction.*` 与 beta 命名 `session.compaction.*` 都已接线），① 的剩余工作是**新命名 3 处实质缺口**（G1/G2/G3）而非从零接入。
- ⑤ `outputPaths` 全源码 **0 消费**；但截断提示 UI 已用**文本模式匹配**实现（BashToolCard）——⑤A 只是「结构化字段在场则优先，缺失回退文本」。
- 排期：**取证腿 R1 前**（纯观测顺带）→ **①A/B + ⑤A 实现腿并入 B4**；⑤B/C 视取证结论独立 B5 候选；①C 不排期。

---

## ① compaction 压缩生命周期事件

### 上游定义

- 新核心三档（`D/opencode-api-reference-v1.md` §21.7.9，:3772-3789）：
  - `session.next.compaction.started` `{timestamp, sessionID, messageID, reason}`（同步 ✅）
  - `session.next.compaction.delta` `{timestamp, sessionID, messageID, text}` —— **瞬时事件**（:5236，断线丢失）
  - `session.next.compaction.ended` —— **双 schema**：v1 `{timestamp, sessionID, text, include?}` / v2 `{timestamp, sessionID, messageID, reason, text, recent}`，客户端需尝试两种
- 粗粒度 `session.compacted {sessionID}`（§21.8 :3825-3828）与 ended **并存**、不同步
- 2026-10-09 取证（`D/research/2026-10-09-p4-upstream-preconditions-check.md` :333）：① 上游**已修**（core/session/compaction.ts:192 发 Started；schema session-event.ts:397）；「V1 兼容路径仍单事件」；:342/:356 提示需真机确认现网 V2 版本可消费

### 客户端现状与缺口

解析链（两条命名轨都已接）：
- 新命名：`K/data/api/sse/parsers/SessionNextEventParser.kt:17,19` → `K/domain/model/SessionNextEvent.kt:40-42,267-287`；挂在 V1（`K/data/api/SseClient.kt:169`）与 V2（`K/data/api/v2/SseClientV2.kt:98`）
- beta 命名 + legacy：`K/data/api/v2/V2EventParser.kt:111-177`（started 读 `inputID ?: messageID`；failed→CompactionEnded(error)；**ended 刻意映射 `SseEvent.SessionCompacted`** :144-150；delta 读 `text ?: delta` :158-168）
- DSH 自有链：`K/data/api/dsh/DshEventMapper.kt:842-907`（compaction/start|summary|end → banner + 转录实体）
- Json `ignoreUnknownKeys = true`（`K/di/NetworkModule.kt:43`）——多余字段静默丢弃

消费链：`K/data/repository/EventDispatcher.kt:157-164,441-443`（仅 `SessionCompacted` 跨 handler 调 endCompaction）→ `K/data/repository/handler/SessionNextEventHandler.kt:285-331`（横幅态/deltaText 累积/失败广播）→ `K/ui/screens/chat/ChatViewModel.kt:562-570,1089-1117`（snackbar+计数判变刷新）；V1 本地置态注入 :630-640（能力位 `CoreFlags.compactionAsync`：V1=false `K/data/adapter/OpenCodeServerAdapter.kt:60`）。UI：CompactionCard / CompactionDividerSlot / CompactionTranscriptCard。测试在库：SessionNextEventHandlerTest/FullTest、SseClientSessionNextTest、V2EventParserTest 等。

**实质缺口**：
- **G1（正确性）**：新命名 `session.next.compaction.ended` 只终结横幅（SessionNextEventHandler.kt:312-318），**不触发**「压缩完毕 snackbar + 消息刷新」——该链仅由 `SseEvent.SessionCompacted` 驱动（EventDispatcher.kt:441-443；ChatViewModel.kt:1089-1117）；beta 命名 ended 有对等映射（V2EventParser.kt:151-154），新命名没有。若新核心服务器只发三档事件，完成反馈断裂。
- **G2（数据丢失，确定性缺陷）**：新 schema delta 字段名是 `text`（API ref :3781），模型只映射 `delta`（SessionNextEvent.kt:274-279）→ 恒空串 → handleCompactionDelta 空串早退（SessionNextEventHandler.kt:302）→ **进行中分割线实时摘要永远空白**（新命名服务器）。beta 命名无此问题（V2EventParser.kt:164 `text ?: delta`）。
- **G3**：ended 的 `text`/`recent` 未建模（SessionNextEvent.kt:281-287 只有 error），摘要依赖 refreshMessages REST 兜底。
- **G4（未知）**：失败通道——beta 有 `session.compaction.failed`，新核心 schema 三档无 failed；失败时新核心发什么未证实。
- **G5（前置）**：现网 4096（2.x 2.0.26）实际发哪套命名**未实测**。
- 次要：sync 回放 `@v1/@v2` 后缀形态不被判别器匹配 → Unknown；当前不消费 sync 回放流（SseClient.kt:187 连实时流），实际影响零（重连走快照重拉，#146② 裁决）。

### 消费选项（①）

| 选项 | 内容 | 工作量 | v1 | v2 | DSH |
|---|---|---|---|---|---|
| **A 验证+对齐（推荐）** | 真机抓帧 4096 命名族；若发新命名：修 G2（CompactionDelta 双读 `text ?: delta`，对齐 V2EventParser 契约）+ G1（新命名 ended 成功态双发 SessionCompacted，复刻 V2EventParser.kt:151-154 语义） | **S**（取证半天 + 两处 S） | 无影响（V1 走 session.compacted 既有链；G2 修复对 V1 若发 next 事件同受益） | 修正目标端 | 无影响 |
| **B = A + 摘要/失败建模** | CompactionEnded 增 text/recent；失败通道按取证结论补（message status=failed 识别已有 V2Mappers.kt:387-391） | **S-M** | 回退零变化 | 增强 | 无影响 |
| **C DSH 式转录实体** | V2 事件升级 CompactionEntry 流内卡 + 历史重放幂等重建（#376/#378 架构）；V2 无 compactionId 需派生键 | **L** | — | 大改 data+UI | 需评估键派生分支 |

C 收益与现有 Part.Compaction 分割线形态重叠，不排期。

## ⑤ outputPaths / truncated 字段

### 上游定义

- `outputPaths`：`session.next.tool.success` 字段 `{..., outputPaths?: string[], result?, provider}`（API ref :3743-3747）「输出超阈值时保存到磁盘的路径列表」
- 2026-10-09 取证（同上研究 :312-325）：part 级 `ToolStateCompleted.outputPaths`（schema session-message.ts:100-101）；统一限幅 tool-output-store.ts（MAX_LINES=2000 / MAX_BYTES=50KiB）超限落盘+有界预览；shell 工具文本约定与结构化字段**并存**（opencode/src/tool/shell.ts:575-590，metadata 含 truncated/outputPath）；#45910（glob 丢截断标志）仍开；结论「可开始按字段做 UI 提示，兼容旧版需判缺失」
- `truncated` 在 V1 API 参考全文 **0 命中**——part 级 truncated 是 2.x core 新事物，V1 1.18 是否携带未知

### 客户端现状

- `outputPaths` **零消费**。三条丢弃路径：SessionNextEvent.ToolSuccess 无字段（:197-204）；V2Mappers tool 分支只读 state.{status,input,metadata,content}（:512-583）；域模型 Part.Tool（Part.kt:107-116）/ToolState.Completed（ToolState.kt:60-78）无字段
- `truncated` 已消费仅一处：`Part.Shell.truncated`（Part.kt:136-137，后台 shell 1MiB 捕获截断；beta-18414 实测恒 false）；UI 续读 ChatMessageList.kt:2238-2247
- **截断提示 UI 已用文本匹配实现**：`K/ui/screens/chat/tools/cards/BashToolCard.kt:54`（OUTPUT_TRUNCATION_PATH_REGEX）+ :186-209（"output truncated" 判定 → `chat_output_truncated` 提示条含路径）——即 ⑤ 的 UI 形态已存在，信号源是脆弱文本匹配（与 #332/#536 spill 同类风险）
- 本地截断：`K/data/local/ToolOutputTruncator.kt:24-27`（500 字符预览 + 后缀）
- DSH：outputPaths/truncated 0 命中；DSH 走 spill 文本约定（#536 SpillNoticeMatcher 并存于 BashToolCard.kt:76-79）

**文档/代码矛盾**：API 参考（题为 V1）:3745 记 tool.success 带 outputPaths，但研究文档定位字段进 schema 是 2.x core 行为——V1 1.18 现网是否已发两文档不一致，消费必须判缺失。

### 消费选项（⑤）

| 选项 | 内容 | 工作量 | v1 | v2 | DSH |
|---|---|---|---|---|---|
| **A 结构化优先+文本回退（推荐）** | ToolState.Completed 增 `outputPaths: List<String>? = null` + Part.Tool 透传；V2Mappers/ToolSuccess 建模；BashToolCard 提示条改结构化优先、缺失回退现文本匹配（复用现有文案与互斥逻辑） | **S**（文案不变则 i18n 零增） | 判缺失走回退零变化 | 直接受益 | 无字段走回退零变化 |
| **B = A + 查看全文动作** | outputPaths 是服务器侧文件路径非 URL：V1 `GET /file/content` 项目外 500（v1-v2-differences.md:90 实测）；V2 `/api/fs/read/*` 待验——**真机可达性取证前置** | **M** | 受限（项目外读不到→隐藏动作） | 主要受益 | 动作隐藏 |
| **C = B + truncated 三信号统一管线** | 再建模 shell metadata.truncated/structured.truncated；outputPaths/truncated/文本前缀三级优先级单点维护（覆盖 #45910 类丢标志工具） | **M-L** | 回退兼容 | 完整消费 | 与 spill 横幅（#536）互斥规则复核 |

## 排期总表

B 序现状：B1✅→B2✅→B3✅→**R1（stable 发版，待用户择时）**→B4（#522 清理）。

| 项 | 建议 | 理由 |
|---|---|---|
| ①⑤ **取证腿** | **R1 前**挂入真机回归顺带（纯观测：4096 v2.0.26 compaction 命名族抓帧 + tool.success/REST outputPaths 在场性） | 零代码不违「R1 前不进新功能」；结论决定 ①A 定性与 ⑤B 可行性；一趟会话覆盖两题 |
| ① 实现腿 A/B | **并入 B4** | G2 是确定性错配无论现网命名都该修；动 SessionNextEvent/EventDispatcher 承重链需完整回归窗口；与 B4 公共模型清理同窗口 |
| ⑤ 实现腿 A | **并入 B4** | S 量级纯增量+回退，data 公共模型触碰期共用回归 |
| ⑤ B/C | **B5 候选**（取证后定） | 路径可达性取证依赖；M~M-L 且 V1 侧受限 |
| ① C | 不排期 | L 量级、收益与现有形态重叠 |

**修复优先级**：G2（delta 字段错配）> G1（ended 完成 UX 链）> ⑤A > ①B > ⑤B/C。
