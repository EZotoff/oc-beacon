package dev.leonardo.ocbeacon.ui.screens.chat.util

import dev.leonardo.ocbeacon.domain.model.Message
import dev.leonardo.ocbeacon.domain.model.TimeInfo
import dev.leonardo.ocbeacon.ui.screens.chat.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #485 完结闪烁根修回归（锚点层）：t_ 键的 Older 侧锚不得落在不可见信封上。
 *
 * 真机 18:31:42：归并失序把 user 挤到 agent-switched 下方，assistant 轮的
 * terminator 变成 agent-switched（信封——buildChatEntries 跳过不渲染）→
 * t_ 键 = t_msg_0f1de0c11（信封 id）→ 子树换血闪灭。锚点应对信封角色
 * （SYNTHETIC_ENVELOPE_ROLES）继续向 Older 侧走，锚到真实 user 消息。
 */
class TurnGroupAnchorStabilityTest {

    private fun cm(id: String, created: Long, role: String? = null, assistant: Boolean = false) =
        ChatMessage(
            message = if (assistant) {
                Message.Assistant(
                    id = id, sessionId = "s",
                    time = TimeInfo(created = created, completed = created + 1),
                    parentId = "",
                )
            } else {
                Message.User(id = id, sessionId = "s", role = role ?: "user", time = TimeInfo(created = created))
            },
            parts = emptyList(),
        )

    /** 显示序（最新在前）：[idle_n, A_n, user_n, agentSw_n, idle_k, A_k, user_k, agentSw_k] */
    private fun orderedDisplay(): List<ChatMessage> = listOf(
        cm("m_nIdle", 3011, role = "idle"),
        cm("m_nA", 2567, assistant = true),
        cm("m_nU", 2565),
        cm("m_nSw", 2562, role = "agent-switched"),
        cm("m_kIdle", 2356, role = "idle"),
        cm("m_kA", 2126, assistant = true),
        cm("m_kU", 2125),
        cm("m_kSw", 2115, role = "agent-switched"),
    )

    @Test
    fun anchorIsUserMessageInServerOrder() {
        val display = orderedDisplay()
        val anchors = computeTurnAnchors(display)
        val idxNa = display.indexOfFirst { it.message.id == "m_nA" }
        assertEquals("A_n 的锚应为 user_n", "m_nU", anchors[idxNa])
        val idxKa = display.indexOfFirst { it.message.id == "m_kA" }
        assertEquals("A_k 的锚应为 user_k", "m_kU", anchors[idxKa])
    }

    @Test
    fun anchorSkipsInvisibleEnvelopeWhenDisordered() {
        // 失序形态（真机 18:31:42 v1）：user 被挤到 agent-switched 下方
        val disordered = listOf(
            cm("m_nIdle", 3011, role = "idle"),
            cm("m_nA", 2567, assistant = true),
            cm("m_nSw", 2562, role = "agent-switched"),
            cm("m_nU", 2565),
            cm("m_kIdle", 2356, role = "idle"),
            cm("m_kA", 2126, assistant = true),
            cm("m_kSw", 2115, role = "agent-switched"),
            cm("m_kU", 2125),
        )
        val anchors = computeTurnAnchors(disordered)
        val idxNa = disordered.indexOfFirst { it.message.id == "m_nA" }
        assertEquals("失序时 A_n 的锚仍应穿透信封锚到 user_n", "m_nU", anchors[idxNa])
    }

    @Test
    fun anchorDoesNotCrossIdleBoundary() {
        // 极端：user_n 完全缺失（不可达态，防御）——锚止步于 idle 边界，不跨轮抢前一轮 user 键
        val noUser = listOf(
            cm("m_nIdle", 3011, role = "idle"),
            cm("m_nA", 2567, assistant = true),
            cm("m_nSw", 2562, role = "agent-switched"),
            cm("m_kIdle", 2356, role = "idle"),
            cm("m_kA", 2126, assistant = true),
            cm("m_kU", 2125),
        )
        val anchors = computeTurnAnchors(noUser)
        val idxNa = noUser.indexOfFirst { it.message.id == "m_nA" }
        assertEquals("user 缺失时锚止步于 idle（不跨轮）", "m_kIdle", anchors[idxNa])
    }
}
