package dev.leonardo.ocbeacon.ui.screens.chat.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 2026-09-16（用户需求）回归：
 * 1. 建议触发从「整串以 / 开头」放宽为「光标所在空白分隔词以 / 开头」——
 *    中段输入也可呼出建议；
 * 2. 查询词 = "/" 后到光标的部分（过滤建议列表）。
 */
class SlashCommandTriggerTest {

    @Test
    fun `empty input no suggestions`() {
        assertNull(slashQueryAt("", 0))
    }

    @Test
    fun `slash only yields empty query`() {
        assertEquals("", slashQueryAt("/", 1))
    }

    @Test
    fun `partial command yields query`() {
        assertEquals("comp", slashQueryAt("/comp", 5))
    }

    @Test
    fun `slash token mid-text triggers`() {
        // 旧实现 text.startsWith("/") → 中段不触发；新实现按光标所在词判定
        assertEquals("comp", slashQueryAt("hello /comp", 11))
        assertEquals("comp", slashQueryAt("look at /comp", 13))
    }

    @Test
    fun `non slash token does not trigger`() {
        assertNull(slashQueryAt("hello world", 11))
        assertNull(slashQueryAt("plain", 5))
    }

    @Test
    fun `cursor after space ends suggestions`() {
        // /comp 后补了空格 → 当前词不再是 slash token
        assertNull(slashQueryAt("/comp action", 12))
    }

    @Test
    fun `cursor inside partial token truncates query`() {
        // 光标停在 "/comp" 的 "com" 之后 → 查询词是 "com"
        assertEquals("com", slashQueryAt("/compact", 4))
    }

    @Test
    fun `newline separated token triggers`() {
        assertEquals("co", slashQueryAt("line1\n/co", 9))
    }

    @Test
    fun `token range covers slash to cursor`() {
        assertEquals(IntRange(6, 10), slashTokenRangeAt("hello /comp", 11))
        assertEquals(IntRange(0, 0), slashTokenRangeAt("/", 1))
    }


    @Test
    fun `token range covers suffix when cursor is in middle`() {
        // cursor after "/com", but token continues with "pact" → selection replaces all of /compact
        assertEquals(IntRange(6, 13), slashTokenRangeAt("hello /compact later", 10))
        assertEquals("com", slashQueryAt("hello /compact later", 10))
    }

    @Test
    fun `selection replaces token without sending or losing surrounding text`() {
        val insertion = insertSlashCommandAt("hello /com there", 10, "/compact ")
        assertEquals("hello /compact there", insertion.text)
        assertEquals(15, insertion.cursor)
    }

    @Test
    fun `selection replaces full token when cursor is in middle`() {
        val insertion = insertSlashCommandAt("hello /compact later", 10, "/new ")
        assertEquals("hello /new later", insertion.text)
        assertEquals(11, insertion.cursor)
    }

    @Test
    fun `cursor out of bounds is safe`() {
        assertNull(slashQueryAt("/comp", -1))
        assertNull(slashQueryAt("/comp", 99))
    }
}
