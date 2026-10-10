package dev.leonardo.ocbeacon.ui.screens.chat.tools.cards

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test

/**
 * #539 ⑤A：截断信号三层优先（结构化 outputPaths > metadata.truncated 旗标 >
 * 文本约定）。现网 2.0.26 契约（2026-10-10 seq 12000 行实测）：大输出尾部截头，
 * metadata.truncated=true，正文尾行 "… of N; full output saved to <path>"——
 * 旧前缀拼写（output truncated…）现网从不出现。
 */
class TruncationSignalTest {

    private val liveTail =
        "10001\n10002\n…\n11999\n12000\n…0001-12000 of 12000; full output saved to /home/u/.local/share/opencode/shell/abc/sh_x.out]"
    private val legacyPrefix =
        "...output truncated...\n\nFull output saved to: /tmp/opencode/f.txt"

    @Test
    fun `live tail bracket suffix stripped from path`() {
        // 现网尾注整段包方括号（真机实证 .out%5D 致 viewer 404）——路径不得带 ] 尾巴
        assertEquals(
            "/home/u/.local/share/opencode/shell/abc/sh_x.out",
            TruncationSignal.path(null, liveTail),
        )
    }

    private fun meta(truncated: Boolean) = buildJsonObject { put("truncated", truncated) }

    @Test
    fun `live contract - metadata flag true with tail path text`() {
        assertTrue(TruncationSignal.isTruncated(null, meta(true), liveTail, spillPresent = false))
        assertEquals(
            "/home/u/.local/share/opencode/shell/abc/sh_x.out",
            TruncationSignal.path(null, liveTail),
        )
    }

    @Test
    fun `live contract - flag true without any path in text`() {
        assertTrue(TruncationSignal.isTruncated(null, meta(true), "10001\n10002", spillPresent = false))
        assertNull(TruncationSignal.path(null, "10001\n10002"))
    }

    @Test
    fun `live contract - flag false with clean small output`() {
        assertFalse(TruncationSignal.isTruncated(null, meta(false), "hi\nthere", spillPresent = false))
    }

    @Test
    fun `legacy prefix contract still detected`() {
        assertTrue(TruncationSignal.isTruncated(null, null, legacyPrefix, spillPresent = false))
        assertEquals("/tmp/opencode/f.txt", TruncationSignal.path(null, legacyPrefix))
    }

    @Test
    fun `structured outputPaths wins for flag and path`() {
        assertTrue(TruncationSignal.isTruncated(listOf("/srv/full.out"), null, "plain", spillPresent = false))
        assertEquals("/srv/full.out", TruncationSignal.path(listOf("/srv/full.out"), legacyPrefix))
    }

    @Test
    fun `spill notice suppresses truncation hint entirely`() {
        assertFalse(TruncationSignal.isTruncated(listOf("/srv/f"), meta(true), legacyPrefix, spillPresent = true))
    }

    @Test
    fun `no signal no hint`() {
        assertFalse(TruncationSignal.isTruncated(null, null, "just normal output", spillPresent = false))
        assertNull(TruncationSignal.path(null, "just normal output"))
    }
}
