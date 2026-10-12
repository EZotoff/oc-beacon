package dev.leonardo.ocbeacon.ui.screens.chat.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #540 B 视觉族门收窄（review 轮）：会话级 turnActive 防抖只许点亮当前轮
 * 统计栏——历史轮在任一活跃回合期间必须保持完成态固定时长。
 */
class TurnTailVisualStreamingTest {

    @Test
    fun `entry streaming wins regardless`() {
        assertTrue(turnTailVisualStreaming(entryStreaming = true, sessionTurnActive = false, isTurnLast = false))
        assertTrue(turnTailVisualStreaming(entryStreaming = true, sessionTurnActive = true, isTurnLast = true))
    }

    @Test
    fun `session debounce bridges current turn across step gaps`() {
        // DSH 步间空窗：组判据翻非流式但回合仍活，最后轮统计栏不应闪断
        assertTrue(turnTailVisualStreaming(entryStreaming = false, sessionTurnActive = true, isTurnLast = true))
    }

    @Test
    fun `historical turns never revive during an active session turn`() {
        // 会话任一回活期间历史轮尾部不得复活计时器（原会话级直并缺陷）
        assertFalse(turnTailVisualStreaming(entryStreaming = false, sessionTurnActive = true, isTurnLast = false))
    }

    @Test
    fun `idle and completed stays off`() {
        assertFalse(turnTailVisualStreaming(entryStreaming = false, sessionTurnActive = false, isTurnLast = true))
        assertFalse(turnTailVisualStreaming(entryStreaming = false, sessionTurnActive = false, isTurnLast = false))
    }
}
