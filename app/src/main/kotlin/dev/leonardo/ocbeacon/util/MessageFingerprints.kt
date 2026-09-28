package dev.leonardo.ocbeacon.util

import dev.leonardo.ocbeacon.domain.model.Message
import dev.leonardo.ocbeacon.domain.model.Part
import dev.leonardo.ocbeacon.domain.model.ToolState
import dev.leonardo.ocbeacon.ui.screens.chat.ChatMessage

/**
 * 消息列表指纹/签名工具 —— 用于缓存结构不变的重计算。
 * 纯函数：只依赖入参，不持有任何状态。
 */
object MessageFingerprints {

    /**
     * 消息列表结构签名（id 序列）—— 用于缓存结构不变的重计算。
     * 只含 id 序列与顺序；内容（parts）变化不改变签名。
     */
    fun messagesSignature(messages: List<ChatMessage>): Int {
        var h = messages.size * 31
        for (m in messages) h = h * 31 + m.message.id.hashCode()
        return h
    }

    /**
     * #450（2026-09-27）：结构+生命周期签名——在 id 序列之上追加每消息
     * completed 位。turnGroups/turnAnchors 的签名缓存用本变体：结构签名
     * （[messagesSignature]）对 completed 变化不敏感，turn 完结时缓存复用
     * stale ChatMessage 引用 → isStreamingMsg/isStreamingTurn（读组内消息
     * completed 字段）卡 true → 统计栏「正在流式输出」永驻（真机探针定罪：
     * badge 决策点 completed=值 且 isStreaming=true 并存）。
     * 失效频率：completed 变化（每 turn 数条）才重建——流式 delta 期间
     * completed 恒 null 签名不变，无重组风暴回归。
     */
    fun messagesLifecycleSignature(messages: List<ChatMessage>): Int {
        var h = messages.size * 31
        for (m in messages) {
            h = h * 31 + m.message.id.hashCode()
            h = h * 31 + if (m.message.time.completed != null) 1 else 0
        }
        return h
    }

    /**
     * 轻量内容指纹：只覆盖会随 SSE 流式 / 工具输出注入 / 完成替换变异的字段，
     * 避免对整个消息做深 hashCode（大文本逐字符开销）。
     * 覆盖：Text/Reasoning 文本尾部、Tool output 尾部、消息完成时间与错误。
     * 2026-08-15：追加覆盖 modelId/providerId/agent——原注释假设"生命周期内
     * 不变"在 V2 下为假（step.ended 事件不含模型信息会触发字段变异、REST 兜底
     * 也会补值）；不纳入会导致 RenderableTurn 缓存复用陈旧值（统计栏丢模型不恢复）。
     * #310④（2026-09-05）：追加覆盖 tokens——REST 兜底可只补 tokens（其余字段
     * 已定型），不入指纹则台账 tokensTotal 陈旧缺席（同款教训）。
     */
    fun messageFingerprint(msg: ChatMessage): Int {
        val m = msg.message
        var h = partsFingerprint(msg.parts)
        h = h * 31 + (m.time.completed ?: 0L).hashCode()
        if (m is Message.Assistant) {
            h = h * 31 + (m.modelId ?: "").hashCode()
            h = h * 31 + (m.providerId ?: "").hashCode()
            h = h * 31 + (m.agent ?: "").hashCode()
            h = h * 31 + (m.tokens?.let { (it.total ?: (it.input + it.output)) } ?: -1).hashCode()
            if (m.error != null) {
                h = h * 31 + m.error.name.hashCode() * 31 + (m.error.data?.toString()?.hashCode() ?: 0)
            }
        }
        return h
    }

    fun partsFingerprint(parts: List<Part>): Int {
        var h = parts.size * 31
        for (p in parts) {
            h = h * 31 + when (p) {
                is Part.Text -> p.text.length * 31 + tailHash(p.text)
                is Part.Reasoning -> p.text.length * 31 + tailHash(p.text)
                is Part.Tool -> toolFingerprint(p)
                else -> p.id.hashCode() * 31 + p.javaClass.name.hashCode()
            }
        }
        return h
    }

    fun toolFingerprint(p: Part.Tool): Int {
        var h = p.callId.hashCode() * 31 + p.tool.hashCode() + p.state.javaClass.name.hashCode()
        when (val s = p.state) {
            is ToolState.Running -> h = h * 31 + tailHash(s.output)
            is ToolState.Completed -> h = h * 31 + tailHash(s.output)
            is ToolState.Error -> h = h * 31 + tailHash(s.error)
            else -> {}
        }
        return h
    }

    fun tailHash(s: String): Int {
        val len = s.length
        if (len <= 64) return s.hashCode()
        return s.substring(len - 64).hashCode() * 31 + len
    }
}
