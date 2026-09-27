package dev.leonardo.ocbeacon.ui.screens.chat.tools

import dev.leonardo.ocbeacon.domain.model.ToolState

/**
 * #453：工具卡累积计时的状态时间提取（卡族统一能力）。
 *
 * 数据源 = ToolState.time（DSH tool/call|result、V2 tool.called|success|failed
 * 信封时刻填充；V1 服务器自带）。锚缺失（null/<=0，历史落库/老服务器）时
 * 返回 null——显示层不显示伪造时长（与思考卡 resolveReasoningDisplayDuration
 * 同原则）。
 */
internal fun ToolState.timingStartMs(): Long? = when (this) {
    is ToolState.Pending -> time?.start
    is ToolState.Running -> time?.start
    is ToolState.Completed -> time?.start
    is ToolState.Error -> time?.start
}.takeIf { it != null && it > 0 }

/**
 * #453：终态冻结时长（end - start，<=0 视为未知——同信封 0 跨度哨兵）。
 * 运行中状态（Pending/Running）恒 null（时长未定）。
 */
internal fun ToolState.frozenDurationMs(): Long? = when (this) {
    is ToolState.Completed -> time?.let { t -> (t.end - t.start).takeIf { it > 0 } }
    is ToolState.Error -> time?.let { t -> (t.end - t.start).takeIf { it > 0 } }
    is ToolState.Pending, is ToolState.Running -> null
}
