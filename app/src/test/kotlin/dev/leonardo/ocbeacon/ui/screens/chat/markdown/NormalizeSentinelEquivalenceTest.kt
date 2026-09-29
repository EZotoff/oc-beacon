package dev.leonardo.ocbeacon.ui.screens.chat.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-08-26 流式卡顿根因修复的哨兵等价性测试：
 * 三个前置哨兵（无 | 跳表格正则 / 无任务字符跳标记阶段 / 手写有序列表判定）
 * 必须与原全量路径逐字节等价。
 */
class NormalizeSentinelEquivalenceTest {

    // ============ 表格哨兵：无 '|' → 原样返回；有 '|' → 原语义 ============

    @Test
    fun `table sentinel passthrough when no pipe char`() {
        val text = "纯文本段落。\n第二行继续 essay 内容。\n\n# 标题\n- 列表项"
        assertEquals(text, ensureBlankLineBeforeGfmTables(text))
    }

    @Test
    fun `table sentinel still inserts blank line for real table`() {
        val text = "前言段落\n|A|B|\n|--|--|"
        val out = ensureBlankLineBeforeGfmTables(text)
        assertEquals("前言段落\n\n|A|B|\n|--|--|", out)
    }

    @Test
    fun `table sentinel untouched for table already after blank line`() {
        val text = "前言\n\n|A|B|\n|--|--|"
        assertEquals(text, ensureBlankLineBeforeGfmTables(text))
    }

    @Test
    fun `table sentinel pipe in plain text without table does not corrupt`() {
        // 有 | 但不构成表格（无分隔行）——正则仍跑但不命中
        val text = "a|b\nc|d"
        assertEquals(text, ensureBlankLineBeforeGfmTables(text))
    }

    // ============ 任务标记哨兵 ============

    @Test
    fun `task sentinel passthrough when no marker chars`() {
        val md = "# 标题\n\n```kotlin\nval x = 1\n```\n\n- 普通列表\n正文"
        assertEquals(md, normalizeTaskListMarkers(md))
    }

    @Test
    fun `task sentinel still normalizes markers outside fences`() {
        val md = "- [ ] 空任务\n- ☐ 未选\n- ☑ 已选\n- ✅ 完成"
        val out = normalizeTaskListMarkers(md)
        assertFalse(out.contains("☐"))
        assertFalse(out.contains("☑"))
        assertFalse(out.contains("✅"))
        assertTrue(out.contains("- [ ] 未选"))
        assertTrue(out.contains("- [x] 已选"))
    }

    @Test
    fun `task sentinel preserves markers inside fences`() {
        val md = "```\n- ☐ 围栏内不改\n```\n- ☐ 围栏外改"
        val out = normalizeTaskListMarkers(md)
        assertTrue(out.contains("围栏内不改"))   // ☐ 保留在围栏内
        assertTrue(out.contains("- [ ] 围栏外改"))
    }

    // ============ 有序列表手写判定 vs 原正则 ============

    @Test
    fun `ordered list hand check matches regex semantics`() {
        // 正例（原正则 ^\d{1,9}[.)]\s 命中）
        for (s in listOf("1. a", "1) a", "123. x", "123456789. y", "42. tab-after-dot")) {
            assertTrue("should match: $s", OrderedListItemRegex.containsMatchIn(s))
        }
        // 反例
        for (s in listOf("a. x", "12345678901. too-many-digits", "1.x", "1.", " 1. indented-regex-anchored",
                     "1234567890. ten-digits", "1.-dash", "42\ttab-no-delimiter", "")) {
            assertFalse("should not match: $s", OrderedListItemRegex.containsMatchIn(s))
        }
    }

    @Test
    fun `split oversized paragraphs end-to-end position semantics`() {
        // 端到端（#471③ 位置制语义）：80 行 × ~46 ≈ 3700 > 3000——
        // 头部边界保持单换行、越过 3000 后的边界升级空行、行数守恒
        val longText = (1..80).joinToString("\n") { "line $it with some content text here padding" }
        assertTrue(longText.length > 3000)
        val out = splitOversizedParagraphsByPosition(longText)
        assertTrue("尾部升级", out.contains("\n\n"))
        assertFalse("头部保持单换行", out.contains("line 1 with some content text here padding\n\nline 2"))
        assertEquals(longText.lines().size, out.lines().filter { it.isNotBlank() }.size)
    }

    // ============ #471③ 同源不变量（终帧=流式帧的函数级保证） ============

    @Test
    fun `normalizeForRender assistant equals normalizeForStreaming byte identical`() {
        // 铁律：流式与完结必须走同一归一化——isUser=false 两条路径逐字节相等
        val fixtures = listOf(
            "# 标题\n\n正文段落。\n\n|A|B|\n|--|--|\n|1|2|",
            "前言段落\n|A|B|\n|--|--|",
            "- ☐ 未选\n- ☑ 已选\n- ✅ 完成\n\n结尾",
            "- [ ] 空任务\n- ☐ 未选\n- ☑ 已选\n- ✅ 完成",
            "$$\nE = mc^2\n\\alpha + \\beta\n$$\n\n后记",
            "行内 \\(x^2\\) 与 \\(y_i\\) 混排文字。",
            "para one\r\npara two\r\n\r\n| h |\r\n|---|\r\n| v |\r\ntail",
            "```kotlin\nval x = 1\n```\n\nafter fence",
            "Title text\n===\n\nsetext body",
            (1..400).joinToString("\n") { "streaming fixture line $it for equivalence check" },
            (1..400).joinToString("\r\n") { "crlf fixture line $it for equivalence check" },
        )
        for (f in fixtures) {
            assertEquals("fixture 等价失败: " + f.take(40),
                normalizeForRender(f, isUser = false), normalizeForStreaming(f))
        }
    }

    @Test
    fun `user single-newline and task markers swap order equivalent`() {
        // #471③ 重构把用户单换行空行化从 normalizeMarkdown 内部（task 之前）
        // 挪到 normalizeForRender 的 task 之后——两者皆行锚定操作（task 行内
        // 替换不增删换行；单换行空行化不动行内字符），交换序逐字节等价。
        val singleNewline = Regex("(?<!\n)\n(?!\n)")
        val fixtures = listOf(
            "- ☐ 任务一\n普通行\n- ✅ 任务二\n另一行",
            "首行\n☐ 独立标记\n尾行",
            "- [x] 已完成\n- ☑ 也完成\n\n- ☐ 新段",
        )
        for (f in fixtures) {
            val taskThenUser = normalizeTaskListMarkers(f).replace(singleNewline, "\n\n")
            val userThenTask = normalizeTaskListMarkers(f.replace(singleNewline, "\n\n"))
            assertEquals(f, taskThenUser, userThenTask)
        }
    }
}
