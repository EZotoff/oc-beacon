package dev.leonardo.ocbeacon.ui.screens.chat.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * #536：DSH spill 通知识别器测试——语义逐条移植自上游 dsh-spill-policy@0.2.0-rc.2
 * `lib/types/notice.js`（hasSpillNotice/isOmission/formatSpillNotice），另含
 * 2026-10-09 调研报告中的现网样本形态。
 */
class DshSpillNoticeTest {

    private val notice = "(Omitted 51234 bytes. Full formatted result stored at: /root/.dsh/spill/x-web_fetch.txt. Use read with offset/limit, or grep this path to search within it.)"

    @Test
    fun `matches preview plus exact notice and parses all fields`() {
        val text = "head of output\nsecond line\n\n$notice"
        val m = DshSpillNoticeMatcher.match(text)!!
        assertEquals(51234L, m.omittedBytes)
        assertNull(m.omittedImages)
        assertEquals("/root/.dsh/spill/x-web_fetch.txt", m.locator)
        assertEquals("Use read with offset/limit, or grep this path to search within it.", m.retrievalHint)
        assertEquals("head of output\nsecond line", DshSpillNoticeMatcher.previewOf(text))
    }

    @Test
    fun `format spill notice roundtrip strips to preview`() {
        // formatSpillNotice({exact,100},{locator,hint}) 重建
        val text = "PREVIEW BODY" + "\n\n" +
            "(Omitted 100 bytes. Full formatted result stored at: /tmp/f.txt. read it back.)"
        assertEquals("PREVIEW BODY", DshSpillNoticeMatcher.previewOf(text))
    }

    @Test
    fun `images clause parses alongside exact bytes`() {
        val m = DshSpillNoticeMatcher.match(
            "x\n\n(Omitted 100 bytes. Omitted 2 images. Full formatted result stored at: /p. hint here.)"
        )!!
        assertEquals(100L, m.omittedBytes)
        assertEquals(2, m.omittedImages)
    }

    @Test
    fun `unknown omission clause matches without byte count`() {
        val m = DshSpillNoticeMatcher.match("x\n\n(More bytes were omitted. Full formatted result stored at: /p. hint.)")!!
        assertNull(m.omittedBytes)
        assertEquals("/p", m.locator)
    }

    @Test
    fun `none clause notice-only output matches with empty preview`() {
        // omission 为 none 档（空子句）+ 整条输出即通知（无预览）
        val m = DshSpillNoticeMatcher.match("( Full formatted result stored at: /p. hint.)")!!
        assertNull(m.omittedBytes)
        assertEquals("", DshSpillNoticeMatcher.previewOf("( Full formatted result stored at: /p. hint.)"))
    }

    @Test
    fun `skips non-notice segments containing separator`() {
        val text = "para one\n\n(just a parenthetical) para two\n\n$notice"
        assertEquals("para one\n\n(just a parenthetical) para two", DshSpillNoticeMatcher.previewOf(text))
    }

    @Test
    fun `rejects malformed variants`() {
        assertNull(DshSpillNoticeMatcher.match("no closing paren $notice".dropLast(1)))
        assertNull(DshSpillNoticeMatcher.match("x\n\n(Omitted ABC bytes. Full formatted result stored at: /p. hint.)"))
        assertNull(DshSpillNoticeMatcher.match("x\n\n(Omitted 5 bytes. Full formatted result stored at: no guidance here"))
        assertNull(DshSpillNoticeMatcher.match("x\n\n(Omitted 5 bytes. Stored somewhere else. hint.)"))
        assertNull(DshSpillNoticeMatcher.match("ordinary output without any notice"))
    }

    @Test
    fun `previewOf is idempotent on already stripped text`() {
        val once = DshSpillNoticeMatcher.previewOf("p\n\n$notice")
        assertEquals(once, DshSpillNoticeMatcher.previewOf(once))
    }
}
