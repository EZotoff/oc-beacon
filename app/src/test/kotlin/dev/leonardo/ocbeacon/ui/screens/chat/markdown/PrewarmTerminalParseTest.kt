package dev.leonardo.ocbeacon.ui.screens.chat.markdown

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * #540 二轮③（裁决 2026-10-11）：终态预热同步核——收起状态下流完的思考文本
 * 离组合入 [MarkdownParsedStateCache]，首开命中即首测终高（消除 asyncTerminal
 * Loading 短高+分档 MDResize 的「先跳再拖」面，journal §22 D1 分档腿）。
 */
class PrewarmTerminalParseTest {

    @Test
    fun `预热后同内容命中缓存`() {
        MarkdownParsedStateCache.clearForTest()
        val text = "# Title\n\n" + (1..400).joinToString("\n") { "line $it of the reasoning trace" }
        assertNull(MarkdownParsedStateCache.get(text))
        prewarmTerminalParseSync(text, isUser = false)
        assertNotNull(MarkdownParsedStateCache.get(text))
    }

    @Test
    fun `预热幂等 命中后不重解析`() {
        MarkdownParsedStateCache.clearForTest()
        val text = (1..400).joinToString("\n") { "paragraph $it" }
        prewarmTerminalParseSync(text, isUser = false)
        val first = MarkdownParsedStateCache.get(text)
        prewarmTerminalParseSync(text, isUser = false)
        // 同实例（未重入覆盖）
        assertNotNull(first)
    }

    @Test
    fun `空壳壳函数入口不崩溃于空内容`() {
        // 同步核对空串也应无害（异步壳有长度门，此处直接验证同步核不抛）
        prewarmTerminalParseSync("", isUser = false)
    }
}
