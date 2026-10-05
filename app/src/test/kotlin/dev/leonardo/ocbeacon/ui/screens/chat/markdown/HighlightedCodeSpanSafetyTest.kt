package dev.leonardo.ocbeacon.ui.screens.chat.markdown

import androidx.compose.ui.text.font.FontWeight
import dev.snipme.highlights.model.BoldHighlight
import dev.snipme.highlights.model.ColorHighlight
import dev.snipme.highlights.model.PhraseLocation
import dev.snipme.highlights.model.SyntaxThemes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #488②：span 区间防御（fork 版安全构建）。
 *
 * 反向区间在野外真实存在（SnipMeDev/Highlights#75：MultilineCommentLocator
 * 对星号斜杠包围的路径产出 start>end）——Reversed range 进 addStyle 即崩溃
 * （mikepenz#415 同类），守卫行为在本测试钉死。
 */
class HighlightedCodeSpanSafetyTest {

    private val rgb = 0xFFFF0000.toInt()
    private val theme = SyntaxThemes.default(darkMode = false)

    private fun spans(text: androidx.compose.ui.text.AnnotatedString) =
        text.spanStyles.map { it.start to it.end }

    @Test
    fun `end exclusive 语义——五字符全染不抛`() {
        // "1e+10" 5 字符，PhraseLocation(0,5) 官方语义 = 索引 0..4（end exclusive）
        val out = applyHighlightSpans("1e+10", listOf(ColorHighlight(PhraseLocation(0, 5), rgb)))
        assertEquals("1e+10", out.text.toString())
        assertEquals(listOf(0 to 5), spans(out))
    }

    @Test
    fun `end 越界被钳制到长度不抛`() {
        val out = applyHighlightSpans("1e+10", listOf(ColorHighlight(PhraseLocation(0, 99), rgb)))
        assertEquals(listOf(0 to 5), spans(out))
    }

    @Test
    fun `反向区间跳过不抛（issue 75 形态）`() {
        val out = applyHighlightSpans("*/path/*", listOf(ColorHighlight(PhraseLocation(3, 1), rgb)))
        assertTrue(spans(out).isEmpty())
        assertEquals("*/path/*", out.text.toString())
    }

    @Test
    fun `start 越界跳过`() {
        val out = applyHighlightSpans("abc", listOf(ColorHighlight(PhraseLocation(9, 12), rgb)))
        assertTrue(spans(out).isEmpty())
    }

    @Test
    fun `start 等于 end 的空区间跳过`() {
        val out = applyHighlightSpans("abc", listOf(ColorHighlight(PhraseLocation(1, 1), rgb)))
        assertTrue(spans(out).isEmpty())
    }

    @Test
    fun `合法区间逐个保留`() {
        val out = applyHighlightSpans(
            "val x = 1",
            listOf(
                ColorHighlight(PhraseLocation(0, 3), rgb),
                ColorHighlight(PhraseLocation(8, 9), rgb),
            ),
        )
        assertEquals(listOf(0 to 3, 8 to 9), spans(out))
    }

    @Test
    fun `BoldHighlight 映射 fontWeight`() {
        val out = applyHighlightSpans("val", listOf(BoldHighlight(PhraseLocation(0, 3))))
        assertEquals(1, out.spanStyles.size)
        assertEquals(FontWeight.Bold, out.spanStyles[0].item.fontWeight)
    }

    @Test
    fun `kotlin 真实引擎链路产出着色`() {
        val out = buildSafeHighlightedAnnotatedString("val x = 1", "kotlin", theme)
        assertEquals("val x = 1", out.text.toString())
        assertTrue("kotlin 关键字应有着色 span", out.spanStyles.isNotEmpty())
    }

    @Test
    fun `未知语言静默纯色等价现状`() {
        val out = buildSafeHighlightedAnnotatedString("some code", "ocbeacon-lang", theme)
        assertEquals("some code", out.text.toString())
        assertTrue(out.spanStyles.isEmpty())
    }

    @Test
    fun `null 语言纯文本`() {
        val out = buildSafeHighlightedAnnotatedString("plain text", null, theme)
        assertEquals("plain text", out.text.toString())
        assertTrue(out.spanStyles.isEmpty())
    }
}
