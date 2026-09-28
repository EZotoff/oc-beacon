package dev.leonardo.ocbeacon.ui.screens.chat.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #432 贴底免派发判定:严格贴底(fii==0 ∧ offset==0)时 steady 迟到增长
 * 跳过位移派发。2026-09-29 四轮用户裁决后,展开位移统一全域反射+归一,
 * 半贴底不再豁免(向下扩展+上方不动语义全域一致)。
 */
class BottomPinnedExpandSkipTest {

    @Test
    fun strictBottomPinnedSkips() {
        assertTrue(bottomPinnedExpandSkip(fii = 0, fiso = 0))
    }

    @Test
    fun halfBottomDoesNotSkip() {
        assertFalse(bottomPinnedExpandSkip(fii = 0, fiso = 424))
    }

    // ===== normalizeExpandAnchor:半贴底超界归一 =====

    @Test
    fun normalizeWithinSameItem() {
        // 目标仍在锚 item 内(747 高,item0,fiso300+H260=560<747)
        assertEquals(0 to 560, normalizeExpandAnchor(listOf(0 to 747), 560))
    }

    @Test
    fun normalizeCrossesIntoNewwardItem() {
        // fiso300+H720=1020 超 item0(747) → 折算进占位带:穿 6..1(全 0 高)落 item0 前?
        // 链=[(0,747)]:1020-747=273 仍超链 → clamp (0, 273→链尽)
        assertEquals(0 to 273, normalizeExpandAnchor(listOf(0 to 747), 1020))
    }

    @Test
    fun normalizePassesZeroSizePlaceholders() {
        // 链 [fii=2(300),占位(1,0),(0,50)] 目标 350:超 300→穿 (1,0)→off=50 恰尽 (0,50) 边界→链尽 (0,0)=列表端,数学等价
        assertEquals(0 to 0, normalizeExpandAnchor(listOf(2 to 300, 1 to 0, 0 to 50), 350))
        // 目标 340:超 300→穿占位→off=40<50 落 (0,40)
        assertEquals(0 to 40, normalizeExpandAnchor(listOf(2 to 300, 1 to 0, 0 to 50), 340))
    }

    @Test
    fun normalizeMidListUnchanged() {
        // mid-list 常规:目标在锚 item 内,原样
        assertEquals(9 to 6607, normalizeExpandAnchor(listOf(9 to 8194, 8 to 404), 6607))
    }

    @Test
    fun midListDoesNotSkip() {
        assertFalse(bottomPinnedExpandSkip(fii = 7, fiso = 212))
        assertFalse(bottomPinnedExpandSkip(fii = 14, fiso = 0))
    }
}