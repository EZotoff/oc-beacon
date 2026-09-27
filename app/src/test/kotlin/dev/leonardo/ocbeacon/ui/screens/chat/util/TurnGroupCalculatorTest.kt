package dev.leonardo.ocbeacon.ui.screens.chat.util

import dev.leonardo.ocbeacon.domain.model.Message
import dev.leonardo.ocbeacon.domain.model.TimeInfo
import dev.leonardo.ocbeacon.ui.screens.chat.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Test

class TurnGroupCalculatorTest {

    private fun assistantMsg(id: String) = ChatMessage(
        message = Message.Assistant(
            id = id,
            sessionId = "test-session",
            time = TimeInfo(created = 1000L, completed = 2000L),
            parentId = "",
            modelId = "test-model"
        ),
        parts = emptyList()
    )

    private fun userMsg(id: String) = ChatMessage(
        message = Message.User(
            id = id,
            sessionId = "test-session",
            time = TimeInfo(created = 500L)
        ),
        parts = emptyList()
    )

    @Test
    fun `empty messages returns empty map`() {
        val result = computeTurnGroups(emptyList())
        assertEquals(emptyMap<Int, List<ChatMessage>>(), result)
    }

    @Test
    fun `single assistant message returns one group`() {
        val msgs = listOf(assistantMsg("a1"))
        val result = computeTurnGroups(msgs)
        assertEquals(1, result.size)
        assertEquals(listOf(msgs[0]), result[0])
    }

    @Test
    fun `three consecutive assistants grouped as one turn`() {
        val msgs = listOf(assistantMsg("a1"), assistantMsg("a2"), assistantMsg("a3"))
        val result = computeTurnGroups(msgs)
        assertEquals(3, result.size)
        assertEquals(msgs, result[0])
        assertEquals(msgs, result[1])
        assertEquals(msgs, result[2])
    }

    @Test
    fun `mixed user and assistant correct grouping`() {
        val msgs = listOf(
            userMsg("u1"), assistantMsg("a1"), assistantMsg("a2"),
            userMsg("u2"), assistantMsg("a3")
        )
        val result = computeTurnGroups(msgs)
        assertEquals(listOf(msgs[1], msgs[2]), result[1])
        assertEquals(listOf(msgs[1], msgs[2]), result[2])
        assertEquals(listOf(msgs[4]), result[4])
        assertEquals(null, result[0])
        assertEquals(null, result[3])
    }

    @Test
    fun `only user messages returns empty map`() {
        val msgs = listOf(userMsg("u1"), userMsg("u2"))
        val result = computeTurnGroups(msgs)
        assertEquals(emptyMap<Int, List<ChatMessage>>(), result)
    }

    // ============ #452（2026-09-27）修复依据锁定 ============

    /**
     * #452 机制根源：user 消息不入 turn 组 → 纯 user 增量前后 turnGroups
     * **值相等**（空↔空）。chatEntries 的 remember 曾以 turnGroups（Map 值比较）
     * + displayItems（SnapshotStateList 同实例自反恒真）作 key —— 两者全恒等
     * → 新会话首条消息（仅 user 播种）期间条目集冻结，列表空白直到首个
     * assistant 事件。本测试钉死该值相等事实：若未来 turnGroups 语义变化
     * （如 user 入组），ChatMessageList 的 #452 修复注释与 key 设计需复核。
     */
    @Test
    fun `#452 user-only growth keeps turnGroups structurally equal`() {
        val before = computeTurnGroups(listOf(userMsg("u1")))
        val after = computeTurnGroups(listOf(userMsg("u1"), userMsg("u2")))
        assertEquals(before, after)
    }

    /**
     * #452 修复有效性前提：buildChatEntries 本身对 user-only displayItems
     * **会发射** Turn entry（isUser=true）——条目缺席纯因 remember key 恒等
     * 不重建（真机插桩：[452-display] n=2 而 ENTRIES 冻结 n=0）。修复后
     * displayItems.size 入 key，size 变化即重建 → user 气泡立即上屏。
     */
    @Test
    fun `#452 user-only displayItems emit Turn entries`() {
        val user = userMsg("u1")
        val entries = dev.leonardo.ocbeacon.ui.screens.chat.components.buildChatEntries(
            displayItems = listOf(0 to user),
            turnGroups = emptyMap(),
            streamingMsgId = null,
            chunkPlans = emptyMap(),
            recentStreamedTurnKeys = emptySet(),
        )
        assertEquals(1, entries.entries.size)
        val turn = entries.entries.first() as dev.leonardo.ocbeacon.ui.screens.chat.components.ChatEntry.Turn
        assertEquals(true, turn.isUser)
    }

    private fun syntheticMsg(id: String) = ChatMessage(
        message = Message.User(
            id = id,
            sessionId = "test-session",
            role = "synthetic",
            time = TimeInfo(created = 800L)
        ),
        parts = emptyList()
    )

    @Test
    fun `synthetic between assistants splits turns`() {
        // 2026-08-12 用户决策：synthetic 独立气泡——不并入 assistant turn，
        // 也不合并两侧 assistant（turn = 连续 assistant 序列）
        val msgs = listOf(assistantMsg("a1"), syntheticMsg("s1"), assistantMsg("a2"))
        val result = computeTurnGroups(msgs)
        assertEquals(listOf(msgs[0]), result[0])
        assertEquals(null, result[1])
        assertEquals(listOf(msgs[2]), result[2])
    }

    @Test
    fun `synthetic after assistant stays independent`() {
        // 2026-08-12 用户决策：synthetic 不再并入 assistant turn
        val msgs = listOf(assistantMsg("a1"), syntheticMsg("s1"))
        val result = computeTurnGroups(msgs)
        assertEquals(listOf(msgs[0]), result[0])
        assertEquals(null, result[1])
    }

    @Test
    fun `isolated synthetic stays independent`() {
        // 前后都无 assistant → 独立条目（不并入任何 turn）
        val msgs = listOf(userMsg("u1"), syntheticMsg("s1"), userMsg("u2"))
        val result = computeTurnGroups(msgs)
        assertEquals(null, result[1])
    }
}
