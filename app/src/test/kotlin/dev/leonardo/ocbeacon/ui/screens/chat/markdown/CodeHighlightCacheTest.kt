package dev.leonardo.ocbeacon.ui.screens.chat.markdown

import androidx.compose.ui.text.AnnotatedString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #488 批2：代码高亮结果缓存（LRU32 / 访问序刷新 / 超大条拒收 / 清空）。
 * 与 EverStreamedPartLedgerTest 同款手法。
 */
class CodeHighlightCacheTest {

    @After
    fun tearDown() = CodeHighlightCache.clear()

    @Test
    fun `put 后 get 命中同一实例`() {
        val v = AnnotatedString("fun a() = 1", listOf())
        CodeHighlightCache.put("k1", v)
        assertSame(v, CodeHighlightCache.get("k1"))
        assertNull(CodeHighlightCache.get("k2"))
    }

    @Test
    fun `LRU 上限 32 条——最旧先逐出`() {
        repeat(33) { CodeHighlightCache.put("k$it", AnnotatedString("v$it")) }
        assertNull(CodeHighlightCache.get("k0"))
        assertEquals("v32", CodeHighlightCache.get("k32")?.text.toString())
        assertEquals("v1", CodeHighlightCache.get("k1")?.text.toString())
    }

    @Test
    fun `访问刷新保命——touch 后更旧的先逐出`() {
        repeat(10) { CodeHighlightCache.put("a$it", AnnotatedString("v$it")) }
        CodeHighlightCache.get("a0") // touch 最旧
        repeat(23) { CodeHighlightCache.put("b$it", AnnotatedString("w$it")) }
        assertEquals("a0 应因 touch 存活", "v0", CodeHighlightCache.get("a0")?.text.toString())
        assertNull("a1 应被逐出", CodeHighlightCache.get("a1"))
    }

    @Test
    fun `超大条目拒收`() {
        val big = AnnotatedString("x".repeat(64_001))
        CodeHighlightCache.put("big", big)
        assertNull(CodeHighlightCache.get("big"))
    }

    @Test
    fun `clear 清空`() {
        CodeHighlightCache.put("k", AnnotatedString("v"))
        CodeHighlightCache.clear()
        assertNull(CodeHighlightCache.get("k"))
    }
}

/**
 * #488 批2：produceState 初值策略纯函数（initialHighlightedFor）。
 *
 * 优先级链：缓存命中 > 首组合同步（≤CAP）> 纯文本。允许同步且着色成功时回写
 * 缓存——第二次调用（模拟滚动离开再回看）应命中缓存零成本取同一结果。
 */
class InitialHighlightedForTest {

    @After
    fun tearDown() = CodeHighlightCache.clear()

    private val theme = dev.snipme.highlights.model.SyntaxThemes.default(darkMode = false)

    private fun kotlinCode(chars: Int): String {
        val head = "fun handler(x: Int): String = compute(x) + suffix // "
        return buildString {
            append(head)
            var n = chars - head.length
            while (n-- > 0) append('a' + (n % 26))
        }
    }

    @Test
    fun `小块首组合同步着色并回写缓存`() {
        val code = kotlinCode(SYNC_HIGHLIGHT_CAP)
        val first = initialHighlightedFor(code, "kotlin", theme, allowSync = true)
        assertTrue("同步初值应有着色 span", first.spanStyles.isNotEmpty())
        val second = initialHighlightedFor(code, "kotlin", theme, allowSync = false)
        assertSame("二次调用应命中缓存", first, second)
    }

    @Test
    fun `超过 CAP 回退纯文本（allowSync 也无效）且不落缓存`() {
        val code = kotlinCode(SYNC_HIGHLIGHT_CAP + 1)
        val out = initialHighlightedFor(code, "kotlin", theme, allowSync = true)
        assertTrue("超 CAP 不做同步", out.spanStyles.isEmpty())
        assertEquals(code, out.text.toString())
        assertNull("纯文本初值不应占缓存槽", CodeHighlightCache.get(codeCacheKey("kotlin", theme, code)))
    }

    @Test
    fun `allowSync=false 未命中缓存时纯文本（流式 re-key 不重付同步成本）`() {
        val code = kotlinCode(200)
        val out = initialHighlightedFor(code, "kotlin", theme, allowSync = false)
        assertTrue(out.spanStyles.isEmpty())
    }

    @Test
    fun `未知语言同步结果为空——不落缓存不误报`() {
        val code = "plain text without known language"
        val out = initialHighlightedFor(code, "notalang", theme, allowSync = true)
        assertTrue(out.spanStyles.isEmpty())
        assertNull(CodeHighlightCache.get(codeCacheKey("notalang", theme, code)))
    }

    @Test
    fun `缓存命中优先于同步`() {
        val code = kotlinCode(300)
        val cached = AnnotatedString(code, listOf())
        CodeHighlightCache.put(codeCacheKey("kotlin", theme, code), cached)
        val out = initialHighlightedFor(code, "kotlin", theme, allowSync = true)
        assertSame(cached, out)
    }

    @Test
    fun `不同主题实例不串色`() {
        val other = dev.snipme.highlights.model.SyntaxThemes.default(darkMode = true)
        val code = kotlinCode(200)
        val a = initialHighlightedFor(code, "kotlin", theme, allowSync = true)
        val b = initialHighlightedFor(code, "kotlin", other, allowSync = true)
        assertFalse("不同 theme 身份应各自缓存", a === b)
    }
}
