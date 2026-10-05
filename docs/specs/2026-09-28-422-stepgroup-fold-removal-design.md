# StepGroup 折叠机制彻底清理（渲染树统一 + 死代码扫除）

- **状态**：已批准动工（2026-09-28 用户裁决「彻底清理，重构/完全重写也在所不惜」）
- **替代关系**：替代 #422（step 自动折叠）原始设计；顺带收账 #426（裂变死代码）
- **关联定罪**：2026-09-28 完结塌缩闪烁（多 step 轮 10/10 复现，d=-237/-401→+225/+365，65-132ms 回弹）

## 1. 背景与现状真相

#422（2026-09-20 用户裁决）原设计「turn 完结后非最后 step 折叠为计数行（DSH 同款时机）」。
2026-09-24 #430 用户裁决「过程卡片退役」后，折叠交互在活代码中已不存在：

- `expandedStepGroups` 在 ChatMessageList.kt 恒为 `emptyMap()` → 裂变路径（StepGroupHead/Body 拆条目）**永不触发**（死代码，#426 已登记）
- `StepGroupFoldRow`（计数行）仅被死路径 Head 条目引用
- 活路径 `StepGroupCard` 无折叠行/展开收起交互——它就是完结后的平铺渲染体

**「折叠」的真正残留**：
1. `isStreaming ? 平铺树(PartContent 直出) : StepGroupCard 树` 的**双分支整树互换**——完结塌缩闪烁根因（互换首帧 heavyComposed=false 渲染 24dp 桩；流式分支不写账本致桩兜底失守，ledgerTotal=0 实证）
2. 一整族休眠死代码（Head/Body/裂变/skipStepGroupItem/FoldRow/Local/i18n key）

## 2. 手术一：统一渲染树（消灭根因）

```kotlin
// 清理前：双分支互换
is RenderItem.StepGroup -> { if (isStreaming) { 平铺 } else { StepGroupCard } }
// 清理后：单一树，流式/完结同构
is RenderItem.StepGroup -> { StepGroup 渲染体 }
//   ├─ 小组：ChunkAssistantItems 直渲染
//   └─ 大组：切片+账本+窗口化（#427 体系保留）
```

- 删 `heavyComposed` 首帧门控（小组直渲染；大组由窗口化内部分批）
- 账本保留（进程级 LRU，#437 根修三）；统一树后账本跨形态恒存活，桩兜底失守一并消失
- 已知取舍：流式期间 `stepNeedsSlicing` 阈值翻转（罕见：单 step 组 ≈6 屏内容）时从直渲染切窗口化，理论存在切换跳变面——真机验证矩阵覆盖，若实测跳变再议局部豁免（渲染策略豁免≠树互换）

## 3. 手术二：死代码族清除

| 文件 | 删除 |
|------|------|
| MarkdownChunking.kt | ChatEntry.StepGroupHead/Body 类、裂变路径、skipStepGroupItem、expandedStepGroups 参数 |
| ChatMessageList.kt | Head/Body 渲染分支、条目 key、expandedStepGroups、Head 钉视口锚定段 |
| MessageCard.kt | skipStepGroupItem 参数 |
| MessageCardAssistant.kt | StepGroupFoldRow 全函数、BRANCH(chunk) 分支、HflickProbe 数据面 |
| ChatCompositionLocals.kt | LocalStepGroupComputing |
| i18n | chat_msg_tail_summary ×15 语言 |
| 测试 | StepGroupLazySplitTest.kt |
| 探针 | HFLICK 族 |

⚠️ LocalFoldRowYReport 有 ReasoningBlock（思考卡）消费者——删除前核对下游（钉视口锚定=死路径），确认后连 Local+消费一起清，若思考卡侧另有活用途则保留并注明。

## 4. 保留清单（不动）

思考卡 ∞/工具卡自身交互、#463 step 分割线（统一树内照常）、切片器/账本/窗口化宿主（大内容成本管理）、装配分组（pendingStepGroups/stepStarts）、CardExpandReveal/高度引擎/episode（通用）、eventExpandedStates（EventCard 域）。

## 5. 派生卡处置

#422→完结迁移（被替代）；#426→关闭（本次收账）；#428/#430→关闭（展开收起动作已不存在）；#471-①→随本次关闭；#429→收窄（窗口化渐进保留，展开动作部分失效）；#432/#434/#462→**保留**（思考卡域不受影响）；#431→保留（切片+列宽 LRU 保留服务）。

## 6. 验证矩阵

1. 今日 RED 回路转 GREEN：10 轮多 step POST 完结零塌缩（RESIZE 无 d≤-100 负跳）
2. 全量单测绿 + i18n 检查 + 编译绿
3. 历史大 turn 滚动流畅（窗口化无 3.6s 冻结回归）
4. 流式冒烟（多 step 流式渲染正常+分割线照常）
5. 思考卡 ∞ 交互无回归；展开吞卡/下拖症状复测消失

## 7. 后验复扫（用户流程）

**本清理合入后，用户将接入 codebase-memory-mcp 对代码库再做一轮全量扫描**，复核折叠域残留引用（符号/状态/i18n/注释线索）是否清扫彻底；扫描发现的任何残留回流到本卡处置。
