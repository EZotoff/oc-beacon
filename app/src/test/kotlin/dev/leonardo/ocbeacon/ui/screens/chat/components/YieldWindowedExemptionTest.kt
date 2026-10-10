package dev.leonardo.ocbeacon.ui.screens.chat.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #540 二轮（宽豁免裁决 2026-10-11）：配对窗开启期间外部 pending 让位守卫豁免
 * ——toggle 即最新显式意图；塌缩自带的被动锚漂移曾被误判为外部意图致收起配对
 * 76 帧饿死（journal §22 D2）。手势门（isScrollInProgress）在链路上游更硬，
 * 不在本缝。
 */
class YieldWindowedExemptionTest {

    @Test
    fun `窗外 让位判定照旧`() {
        // 读位 ≠ lastSet → 让位（存量语义）
        assertTrue(
            shouldYieldPairingWindowed(7, 4171, 7, 4331, windowOpen = false),
        )
    }

    @Test
    fun `窗外 读位一致不让位`() {
        assertFalse(
            shouldYieldPairingWindowed(7, 4331, 7, 4331, windowOpen = false),
        )
    }

    @Test
    fun `窗外 无历史基线不让位`() {
        assertFalse(
            shouldYieldPairingWindowed(0, 0, null, null, windowOpen = false),
        )
    }

    @Test
    fun `窗内 漂移读位豁免不让位`() {
        // D2 定罪形态：塌缩漂移使 read≠lastSet 逐帧成立——窗内一律豁免
        assertFalse(
            shouldYieldPairingWindowed(7, 4171, 7, 4331, windowOpen = true),
        )
        assertFalse(
            shouldYieldPairingWindowed(3, 250, 7, 4331, windowOpen = true),
        )
    }
}
