package dev.leonardo.ocbeacon.ui.screens.chat.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 新bug 根修（SSE 输出上方内容闪烁消失）：GUARD 触底 pending 未被 measure 消费时，
 * flush task 读 stale 视口值误判非贴底 → 配对 set 覆盖 GUARD pending → 触底被抵消
 * +每批 +Δ 推走视口（取证：release set(fii=7,fiso 0→66→132…) 连续推高）。
 *
 * 修复缝：[shouldYieldPairing]——读位 ≠ 上批 set 目标 = 存在未消费的外部 pending
 * （GUARD/ForceScroll 显式意图）→ 本批让位（位置神圣：显式意图 > 引擎配对）。
 */
class PairingYieldTest {

    private fun motive(msg: String) = println("[MOTIVE] $msg")

    @Test
    fun `无上批目标不让位(首批)`() {
        assertFalse(shouldYieldPairing(7, 900, null, null))
    }

    @Test
    fun `读位等于上批目标(已消费)正常配对`() {
        assertFalse(shouldYieldPairing(7, 66, 7, 66))
        assertFalse(shouldYieldPairing(0, 0, 0, 0))
    }

    @Test
    fun `读位偏离上批目标=外部pending未消费 让位`() {
        // GUARD 触底 pending(0) 写入后，measure 前读值仍是旧位(7,66)——偏离上批目标(0,0)
        assertTrue(shouldYieldPairing(7, 66, 0, 0))
        assertTrue(shouldYieldPairing(7, 0, 0, 0))
        assertTrue(shouldYieldPairing(0, 3, 7, 66))
    }

    // ============ #502：divergence 静止采纳（防永久让位死锁） ============

    @Test
    fun `静止且lastSet陈旧的外部位 立即采纳`() {
        motive("#502 主断言：用户把列表停在新位置（读位静止两帧）且引擎近期无 set——是已定居外部滚动而非待消费 pending，必须采纳恢复配对（否则永久让位：帽冻结+流式消息被裁剪在小区域直到完结）")
        assertTrue(shouldAdoptExternalPosition(0, 0, 0, 0, framesSinceLastSet = 2))
        assertTrue(shouldAdoptExternalPosition(0, 0, 0, 0, framesSinceLastSet = 1448))
        assertTrue(shouldAdoptExternalPosition(7, 393, 7, 393, framesSinceLastSet = 5))
    }

    @Test
    fun `读位仍在移动不采纳`() {
        motive("fling 未停/pending 消费中（读位帧间在变）——保持让位观察，不得把移动中的位置错立为基线")
        assertFalse(shouldAdoptExternalPosition(0, 0, 0, 3, framesSinceLastSet = 10))
        assertFalse(shouldAdoptExternalPosition(3, 120, 0, 0, framesSinceLastSet = 10))
    }

    @Test
    fun `lastSet近帧写入不采纳（引擎pending未消费窗口保护）`() {
        motive("回归锚：引擎 set 请求时即写 lastSet、measure 消费前读位=旧位且静止——裸静止判定会误采纳旧位毁掉 pending 保护（上方内容闪烁消失根修回归口）；近 2 帧内有过 set 一律继续让位")
        assertFalse(shouldAdoptExternalPosition(7, 66, 7, 66, framesSinceLastSet = 0))
        assertFalse(shouldAdoptExternalPosition(7, 66, 7, 66, framesSinceLastSet = 1))
    }

    @Test
    fun `无上帧观测不采纳（首批让位观察期）`() {
        motive("prev 为 null（首批/无历史）——保持一帧观察期，下一帧达稳态再判")
        assertFalse(shouldAdoptExternalPosition(0, 0, null, null, framesSinceLastSet = 100))
        assertFalse(shouldAdoptExternalPosition(0, 0, 0, null, framesSinceLastSet = 100))
        assertFalse(shouldAdoptExternalPosition(0, 0, null, 0, framesSinceLastSet = 100))
    }
}
