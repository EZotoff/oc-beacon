package dev.leonardo.ocbeacon.ui.screens.viewer

import dev.snipme.highlights.model.SyntaxLanguage
import org.junit.Assert.*
import org.junit.Test

class HighlightBuilderTest {

    // ===== rememberLanguage =====

    @Test
    fun `kotlin extensions`() {
        assertEquals(SyntaxLanguage.KOTLIN, HighlightBuilder.rememberLanguage("Main.kt"))
        assertEquals(SyntaxLanguage.KOTLIN, HighlightBuilder.rememberLanguage("build.gradle.kts"))
    }

    @Test
    fun `major language extensions`() {
        assertEquals(SyntaxLanguage.JAVA, HighlightBuilder.rememberLanguage("App.java"))
        assertEquals(SyntaxLanguage.PYTHON, HighlightBuilder.rememberLanguage("script.py"))
        assertEquals(SyntaxLanguage.JAVASCRIPT, HighlightBuilder.rememberLanguage("index.js"))
        assertEquals(SyntaxLanguage.TYPESCRIPT, HighlightBuilder.rememberLanguage("app.tsx"))
        assertEquals(SyntaxLanguage.GO, HighlightBuilder.rememberLanguage("main.go"))
        assertEquals(SyntaxLanguage.RUST, HighlightBuilder.rememberLanguage("lib.rs"))
        assertEquals(SyntaxLanguage.SHELL, HighlightBuilder.rememberLanguage("deploy.sh"))
    }

    @Test
    fun `uppercase extension normalised`() {
        assertEquals(SyntaxLanguage.KOTLIN, HighlightBuilder.rememberLanguage("File.KT"))
        assertEquals(SyntaxLanguage.PYTHON, HighlightBuilder.rememberLanguage("SCRIPT.PY"))
    }

    @Test
    fun `unknown or missing extension returns DEFAULT`() {
        assertEquals(SyntaxLanguage.DEFAULT, HighlightBuilder.rememberLanguage("readme.unknown"))
        assertEquals(SyntaxLanguage.DEFAULT, HighlightBuilder.rememberLanguage("Makefile"))
        assertEquals(SyntaxLanguage.DEFAULT, HighlightBuilder.rememberLanguage(""))
    }

    @Test
    fun `full path with directories`() {
        assertEquals(
            SyntaxLanguage.KOTLIN,
            HighlightBuilder.rememberLanguage("/home/user/projects/App.kt")
        )
    }

    // ===== buildHighlights =====

    @Test
    fun `buildHighlights returns non-null result for kotlin code`() {
        val result = HighlightBuilder.buildHighlights(
            "val x = 42\nfun main() = println(x)",
            SyntaxLanguage.KOTLIN,
            isDark = true
        )
        assertNotNull(result)
    }

    @Test
    fun `buildHighlights works with light theme`() {
        val result = HighlightBuilder.buildHighlights(
            "print('hello')",
            SyntaxLanguage.PYTHON,
            isDark = false
        )
        assertNotNull(result)
    }

    @Test
    fun `buildHighlights handles empty content`() {
        val result = HighlightBuilder.buildHighlights("", SyntaxLanguage.DEFAULT, isDark = true)
        assertNotNull(result)
    }

    // ===== buildAnnotatedStringFromHighlights（#489：end exclusive 语义） =====

    @Test
    fun `span 映射 end exclusive——每个 span 端点等于引擎 location 原值`() {
        val content = "val answer = 42"
        val result = HighlightBuilder.buildHighlights(content, SyntaxLanguage.KOTLIN, isDark = true)
        val out = HighlightBuilder.buildAnnotatedStringFromHighlights(content, result)
        assertEquals(content, out.text.toString())

        val inRange = result.getHighlights()
            .filter { it.location.start < content.length && it.location.end.coerceAtMost(content.length) > it.location.start }
        assertTrue("kotlin 样本应产出在案高亮", inRange.isNotEmpty())
        assertEquals("每个在案高亮都应映射为 span", inRange.size, out.spanStyles.size)
        inRange.zip(out.spanStyles).forEach { (h, s) ->
            assertEquals(h.location.start, s.start)
            // 回归钉：修复前此处为 end+1（尾字符多染）
            assertEquals(h.location.end.coerceAtMost(content.length), s.end)
        }
    }

    @Test
    fun `span 映射文本文本原样保留`() {
        val content = "print('hello')"
        val result = HighlightBuilder.buildHighlights(content, SyntaxLanguage.PYTHON, isDark = false)
        val out = HighlightBuilder.buildAnnotatedStringFromHighlights(content, result)
        assertEquals(content, out.text.toString())
        assertTrue(out.spanStyles.isNotEmpty())
    }
}
