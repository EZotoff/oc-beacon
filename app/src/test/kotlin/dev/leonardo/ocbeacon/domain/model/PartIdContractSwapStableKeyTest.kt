package dev.leonardo.ocbeacon.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #507 换装稳定组合键——DSH 完结换代（流式宿主 → 权威 seq）kind/ordinal 编号
 * 域不变，后缀=消息内逻辑身份：key() 跨换代连续，子树不换血。
 */
class PartIdContractSwapStableKeyTest {

    @Test
    fun `派生 id 取 kind+ordinal 后缀（DSH 换装两侧同键）`() {
        val synthetic = PartIdContract.derive("dsh-t30s1", "text", 1)
        val authoritative = PartIdContract.derive("seq-session-6d64-abc_msg1", "text", 1)
        assertEquals(
            PartIdContract.swapStableKey(synthetic),
            PartIdContract.swapStableKey(authoritative),
        )
        assertEquals("text_ord_1", PartIdContract.swapStableKey(synthetic))
    }

    @Test
    fun `reasoning 与 text 同序号可区分（kind 入键）`() {
        val r = PartIdContract.swapStableKey(PartIdContract.derive("dsh-t30s1", "reasoning", 0))
        val t = PartIdContract.swapStableKey(PartIdContract.derive("dsh-t30s1", "text", 0))
        assertEquals("reasoning_ord_0", r)
        assertEquals("text_ord_0", t)
    }

    @Test
    fun `服务器原生 id 原样返回（OpenCode 完结不换代无此症）`() {
        assertEquals("part_abc123", PartIdContract.swapStableKey("part_abc123"))
        assertEquals("", PartIdContract.swapStableKey(""))
    }

    @Test
    fun `消息 id 含下划线不误切（取最后 marker）`() {
        val id = "msg_text_with_underscore_text_ord_2"
        assertEquals("text_ord_2", PartIdContract.swapStableKey(id))
    }
}
