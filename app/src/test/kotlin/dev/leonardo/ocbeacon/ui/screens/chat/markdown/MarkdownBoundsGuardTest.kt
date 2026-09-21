package dev.leonardo.ocbeacon.ui.screens.chat.markdown

import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [markdownNodeInBounds] 越界防护单测（2026-09-21 崩溃取证）。
 *
 * 崩溃形态：StringIndexOutOfBoundsException "begin 0, end 26/58/2, length 0"——
 * 节点 offset 超出 content 快照长度（含 content="" 配非空节点）。用真实 GFM
 * 解析器构造合法树，再以缩短/置空的 content 复现失配形态。
 */
class MarkdownBoundsGuardTest {

    private val parser = MarkdownParser(GFMFlavourDescriptor())

    private fun parse(text: String) = parser.buildMarkdownTreeFromString(text)

    @Test
    fun `in-bounds tree passes`() {
        val text = "# Title\n\nParagraph with **bold** and `code` and [link](http://x)\n\n- item\n"
        val node = parse(text)
        assertTrue(markdownNodeInBounds(node, text))
    }

    @Test
    fun `empty content with non-empty tree fails`() {
        // 复现崩溃形态：content="" + 节点 [0, N>0]
        val node = parse("Some heading text\n\nmore")
        assertFalse(markdownNodeInBounds(node, ""))
    }

    @Test
    fun `truncated content fails`() {
        val text = "# Title\n\nSome paragraph body here\n\n"
        val node = parse(text)
        assertFalse(markdownNodeInBounds(node, text.substring(0, text.length / 2)))
    }

    @Test
    fun `descendant violation is detected`() {
        // 顶层区间看似合法但后代（行内 code span）越界——防护必须递归
        val text = "Paragraph with `code span` inside\n\n"
        val node = parse(text)
        assertFalse(markdownNodeInBounds(node, text.dropLast(3)))
    }

    @Test
    fun `empty tree on empty content passes`() {
        val node = parse("")
        assertTrue(markdownNodeInBounds(node, ""))
    }

    @Test
    fun `unicode content passes with itself`() {
        val text = "标题带 emoji 🎯🎯 与中文\n\n- 项\n\n| a | b |\n|---|---|\n| 1 | 2 |\n\n"
        val node = parse(text)
        assertTrue(markdownNodeInBounds(node, text))
    }
}
