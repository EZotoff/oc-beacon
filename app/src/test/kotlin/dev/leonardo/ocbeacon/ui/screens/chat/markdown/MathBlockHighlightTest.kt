package dev.leonardo.ocbeacon.ui.screens.chat.markdown

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #488③ 方案 c：buildMathAnnotatedString（数学块轻着色）+ 围栏路由单测。
 *
 * 三角色钉子：\命令整段（含反斜杠）/花括号单字符/上下标单字符；
 * \后非字母（转义字面量）保持原色；区间顺序产出（非重叠、界内）。
 * 路由钉子：language=="math"（大小写不敏感）才进数学块——tex/mathematics
 * 不误入（transformMathFallback 产物专属识别位）。
 */
class MathBlockHighlightTest {

    private val cmd = Color(0xFF1010AA)
    private val brace = Color(0xFF00AA88)
    private val script = Color(0xFFAA0066)

    private fun out(code: String): AnnotatedString = buildMathAnnotatedString(code, cmd, brace, script)

    private fun ranges(code: String) = out(code).spanStyles.map { it.start to it.end }

    // ============ \命令 ============

    @Test
    fun commandSpanCoversBackslashAndLetters() {
        val s = out("\\frac").spanStyles
        assertEquals(1, s.size)
        assertEquals(0 to 5, s[0].start to s[0].end)
        assertEquals(cmd, s[0].item.color)
    }

    @Test
    fun uppercaseCommandColored() {
        assertEquals(listOf(0 to 6), ranges("\\Alpha"))
    }

    @Test
    fun nonLetterEscapeKeptPlain() {
        listOf("\\,", "\\%", "\\\\", "a\\;b").forEach { c ->
            assertEquals("no spans for: $c", 0, out(c).spanStyles.size)
        }
    }

    @Test
    fun trailingLoneBackslashNoSpan() {
        // 末尾孤立 \（i+1 越界守卫）：不产出区间、不越界
        assertEquals(0, out("x\\").spanStyles.size)
        assertEquals("x\\", out("x\\").text.toString())
    }

    // ============ 花括号 / 上下标 ============

    @Test
    fun bracesColoredIndividually() {
        val s = out("{a}").spanStyles
        assertEquals(listOf(0 to 1, 2 to 3), s.map { it.start to it.end })
        assertEquals(brace, s[0].item.color)
        assertEquals(brace, s[1].item.color)
    }

    @Test
    fun scriptHooksColored() {
        val s = out("x^2_1").spanStyles
        assertEquals(listOf(1 to 2, 3 to 4), s.map { it.start to it.end })
        assertEquals(script, s[0].item.color)
        assertEquals(script, s[1].item.color)
    }

    // ============ 综合 / 不变量 ============

    @Test
    fun plainAndEmptyNoSpans() {
        listOf("", "E=mc2 sum 123", "普通文本").forEach { c ->
            assertEquals(c, 0, out(c).spanStyles.size)
            assertEquals(c, out(c).text.toString())
        }
    }

    @Test
    fun fullFormulaSpansOrderedDisjointInBounds() {
        val code = "\\int_0^1 x\\,dx = \\frac{a}{b}"
        val text = out(code)
        // 区间严格递增且互不重叠、界内——扫描器顺序产出不变量
        var lastEnd = 0
        text.spanStyles.forEach { s ->
            assertTrue(s.start >= lastEnd)
            assertTrue(s.end > s.start)
            assertTrue(s.end <= code.length)
            lastEnd = s.end
        }
        // \int(0,4) _(4,5) ^(6,7) \frac(17,22) {(22,23) }(24,25) {(25,26) }(27,28)
        assertEquals(
            listOf(
                0 to 4, 4 to 5, 6 to 7, 17 to 22,
                22 to 23, 24 to 25, 25 to 26, 27 to 28,
            ),
            text.spanStyles.map { it.start to it.end },
        )
    }

    @Test
    fun textPreservedVerbatim() {
        val code = "\\sqrt{x_i^2 + y^2}"
        assertEquals(code, out(code).text.toString())
    }

    // ============ 围栏路由（language == math） ============

    @Test
    fun fenceLanguageRouting() {
        assertTrue("math".equals(MATH_FENCE_LANGUAGE, ignoreCase = true))
        assertTrue("Math".equals(MATH_FENCE_LANGUAGE, ignoreCase = true))
        assertFalse("tex".equals(MATH_FENCE_LANGUAGE, ignoreCase = true))
        assertFalse("mathematics".equals(MATH_FENCE_LANGUAGE, ignoreCase = true))
        val absent: String? = null
        assertFalse(absent.equals(MATH_FENCE_LANGUAGE, ignoreCase = true))
    }

    // ============ 接线：transform 产物即数学围栏 ============

    @Test
    fun transformEmitsMathFenceForRendererRouting() {
        val md = transformMathFallback("\$\$\\frac{a}{b}\$\$")
        assertEquals("```math\n\\frac{a}{b}\n```", md)
    }
}
