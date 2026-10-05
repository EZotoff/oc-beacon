package dev.leonardo.ocbeacon.ui.screens.chat.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * splitOversizedParagraphsByPosition（位置制超长段落空行化，#471③）单测。
 *
 * 语义（spec docs/specs/2026-09-30-471-3-streaming-normalization-unification-design.md §3.2）：
 * run 内行 j 之后的边界升级为空行 ⟺ cumEnd(j) ≥ 3000（cumEnd = run 起点到
 * 行 j 换行含的累计字符，行完成即定案）。与旧全段判定（run 总量 ≥3000 时
 * 整 run 空行化）的差异：头部 ≤3000 边界保持单换行；截断前缀稳定
 * （流式放行单调的关键性质）。
 *
 * 原始动机（2026-08-20 C-F1）：巨型单段 PARAGRAPH 让块级分片失效，
 * 空行化后每行独立成块 → 分片链路生效。
 */
class SplitOversizedParagraphsByPositionTest {

    @Test
    fun shortTextUntouched() {
        val text = "1 - one\n2 - two\n3 - three"
        assertEquals(text, splitOversizedParagraphsByPosition(text))
    }

    @Test
    fun belowThresholdRunKeepsSingleNewlines() {
        // 100 行 × ~11 字符 ≈ 1100 < 3000 → 原样
        val lines = (1..100).joinToString("\n") { "$it - item" }
        assertEquals(lines, splitOversizedParagraphsByPosition(lines))
    }

    @Test
    fun headBoundaryUnderThresholdKeepsSingleNewline() {
        // 300 行 × 16 字符 ≈ 4800：cumEnd 首越 3000 在行 ~187。
        // 位置制：头部边界保持单换行（旧全段语义这里是 \n\n——语义变更点）。
        val lines = (1..300).joinToString("\n") { "x line number $it" }
        val result = splitOversizedParagraphsByPosition(lines)
        assertTrue("头部行间保持单换行", result.contains("x line number 1\nx line number 2"))
        assertFalse("头部不得空行化（旧全段语义）",
            result.contains("x line number 1\n\nx line number 2"))
    }

    @Test
    fun tailBoundariesUpgradedAfterThreshold() {
        // cumEnd 越过 3000 后，后续每个边界都 ≥3000 → 尾部全部升级（稳定接缝）
        val lines = (1..300).joinToString("\n") { "x line number $it" }
        val result = splitOversizedParagraphsByPosition(lines)
        assertTrue("尾部边界升级为空行",
            result.contains("x line number 200\n\nx line number 201"))
        assertTrue("末段边界同样升级",
            result.contains("x line number 299\n\nx line number 300"))
    }

    @Test
    fun contentLinesConserved() {
        // 内容行守恒（无丢失/无重复）
        val lines = (1..300).joinToString("\n") { "$it - item" }
        val result = splitOversizedParagraphsByPosition(lines)
        val originalLines = lines.split("\n")
        val resultLines = result.split("\n").filter { it.isNotEmpty() }
        assertEquals(originalLines, resultLines)
    }

    @Test
    fun exactThresholdBoundaryDecision() {
        // 精确 off-by-one：999 字符行 cumEnd(2)=1000+1000+1000=3000 → 行 2/3 间升级
        val line = "a".repeat(999)
        val text = listOf(line, line, line, line).joinToString("\n")
        val result = splitOversizedParagraphsByPosition(text)
        assertEquals(line + "\n" + line + "\n" + line + "\n\n" + line, result)

        // 998 字符行 cumEnd(2)=2997 < 3000 → 不升级（≥ 语义，非 >）
        val short = "b".repeat(998)
        val text2 = listOf(short, short, short, short).joinToString("\n")
        assertEquals(text2, splitOversizedParagraphsByPosition(text2))
    }

    @Test
    fun truncationPrefixIsPrefixOfFullOutput() {
        // 流式单调的根：任意截断前缀的输出是全量输出的前缀（行中截断含）
        val lines = (1..250).joinToString("\n") { "plain line $it with some padding words to grow" }
        val full = splitOversizedParagraphsByPosition(lines)
        assertTrue("fixture 应实际触发升级（>3000）", full.contains("\n\n"))
        val cutPoints = intArrayOf(
            1, 50, 299, 2999, 3000, 3001, 3050, 4400,
            lines.length / 2, lines.length - 1,
        )
        for (k in cutPoints) {
            val partial = splitOversizedParagraphsByPosition(lines.substring(0, k))
            assertTrue("k=$k 的输出必须是全量输出的前缀", full.startsWith(partial))
        }
        assertEquals("全量截断 = 恒等", full, splitOversizedParagraphsByPosition(lines))
    }

    @Test
    fun idempotent() {
        // 升级产生的空行会断开 run → 二次运行各子 run 均低于阈值 → 不再改
        val lines = (1..250).joinToString("\n") { "idempotent line $it padded" }
        val once = splitOversizedParagraphsByPosition(lines)
        assertTrue(once.contains("\n\n"))
        assertEquals(once, splitOversizedParagraphsByPosition(once))
    }

    @Test
    fun fencedCodeBlockLinesNotSplit() {
        val code = (1..700).joinToString("\n") { "code line $it" }
        val text = "```kotlin\n$code\n```"
        val result = splitOversizedParagraphsByPosition(text)
        assertTrue("围栏内容原样", result.contains("\n$code\n"))
        assertFalse(result.contains("code line 1\n\ncode line 2"))
    }

    @Test
    fun tableLinesNotSplit() {
        val rows = (1..400).joinToString("\n") { "| $it | value |" }
        val text = "| h | v |\n$rows"
        val result = splitOversizedParagraphsByPosition(text)
        assertFalse(result.contains("| 1 | value |\n\n| 2 |"))
    }

    @Test
    fun listLinesNotSplit() {
        val items = (1..400).joinToString("\n") { "- item $it" }
        assertEquals(items, splitOversizedParagraphsByPosition(items))
    }

    @Test
    fun orderedListLinesNotSplit() {
        val items = (1..400).joinToString("\n") { "1. item $it" }
        assertEquals(items, splitOversizedParagraphsByPosition(items))
    }

    @Test
    fun mixedContentProtectedStructuresIntact() {
        // 混合内容：围栏/短列表结构原样；plain 长段尾部（越阈值后）升级
        val plainBig = (1..200).joinToString("\n") { "plain line number $it with padding text" }
        val text = "```\ncode\n```\n\n$plainBig\n\n- a\n- b"
        val result = splitOversizedParagraphsByPosition(text)
        assertTrue("代码块原样", result.contains("```\ncode\n```"))
        assertTrue("短列表原样", result.endsWith("- a\n- b"))
        // 200 行 × ~38 ≈ 7600：首越阈值在行 ~79，其后边界全部升级
        assertTrue("plain 段尾部升级", result.contains("plain line number 150 with padding text\n\nplain line number 151"))
        assertFalse("plain 段头部保持单换行",
            result.contains("plain line number 1 with padding text\n\nplain line number 2"))
    }
}
