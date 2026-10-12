package dev.leonardo.ocbeacon.ui.screens.chat.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #486 根修：msgId 级曾流式账本——标记/查询/LRU 逐出三态。 */
class EverStreamedPartLedgerTest {

    @Test
    fun `mark and query roundtrip`() {
        EverStreamedPartLedger.clear()
        assertFalse(EverStreamedPartLedger.wasStreamed("m1"))
        EverStreamedPartLedger.mark("m1")
        assertTrue(EverStreamedPartLedger.wasStreamed("m1"))
        assertFalse(EverStreamedPartLedger.wasStreamed("m2"))
    }

    @Test
    fun `lru evicts oldest beyond cap`() {
        EverStreamedPartLedger.clear()
        repeat(600) { EverStreamedPartLedger.mark("m$it") }
        // 前 88 个被逐出（600-512），随后保留
        assertFalse(EverStreamedPartLedger.wasStreamed("m0"))
        assertFalse(EverStreamedPartLedger.wasStreamed("m87"))
        assertTrue(EverStreamedPartLedger.wasStreamed("m88"))
        assertTrue(EverStreamedPartLedger.wasStreamed("m599"))
    }

    @Test
    fun `access refreshes lru order`() {
        EverStreamedPartLedger.clear()
        repeat(512) { EverStreamedPartLedger.mark("m$it") }
        // 触摸最旧条目后，再压一个新键——被逐出的应是次旧而非被触摸者
        assertTrue(EverStreamedPartLedger.wasStreamed("m0"))
        EverStreamedPartLedger.mark("newest")
        assertTrue(EverStreamedPartLedger.wasStreamed("m0"))
        assertFalse(EverStreamedPartLedger.wasStreamed("m1"))
    }
}
