package dev.leonardo.ocbeacon.ui.screens.chat.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #432 贴底免派发判定:严格贴底(fii==0 ∧ offset==0)时 steady 迟到增长
 * 跳过位移派发。2026-09-29 四轮用户裁决后,展开位移统一全域反射+归一,
 * 半贴底不再豁免(向下扩展+上方不动语义全域一致)。
 *
 * normalizeExpandAnchor 于 #508 根修重写:折叠方向改为向旧侧(idx 递增)+
 * 宿主容量 +H。下方归一用例的**绝对位不变量**:结果 (m,off) 满足
 * sum(链内 item 增长后高度[m 之前]) + off == rawTarget(锚 item 的 fiso
 * 语义即距列表新端的累计距离)。
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

    // ===== normalizeExpandAnchor(#508 宿主感知版) =====

    @Test
    fun normalizeWithinSameItem() {
        // 目标仍在锚 item 容量内(item0 宿主 +260 后 1007;560<1007)原样
        assertEquals(0 to 560, normalizeExpandAnchor(listOf(0 to 747), 560, hostIndex = 0, hostGrowth = 260))
    }

    @Test
    fun hostIsAnchorNeverFoldsEvenWhenTargetExceedsPreGrowthSize() {
        // #508 回归钉子(T9 真机定罪场景数值化):锚=宿主 item7 现高 2359,
        // fiso1993+H1335=3328 > 2359(旧尺寸)——宿主=锚 ⇒ 容量 2359+1335=3694,
        // 3328<3694 恒落锚内。旧实现此处返回 (7,969)=3328−2359 丢整 item 高度
        // (真机实测展开位移恒 −1024px=H−锚尺寸,两例同值)。
        assertEquals(7 to 3328, normalizeExpandAnchor(listOf(7 to 2359, 8 to 404, 9 to 1072), 3328, hostIndex = 7, hostGrowth = 1335))
    }

    @Test
    fun hostAboveAnchorFoldsOldwardWithBoostedCapacity() {
        // 宿主在锚上方:锚(7,1000)不增长,穿过后 8(400) 穿过后落宿主 9——
        // 容量按 500+800=1300 计,余 100 落 (9,100)。
        // 绝对位核账:1000+400+100=1500=rawTarget(600+900) ✓
        assertEquals(9 to 100, normalizeExpandAnchor(listOf(7 to 1000, 8 to 400, 9 to 500), 1500, hostIndex = 9, hostGrowth = 800))
    }

    @Test
    fun hostAboveAnchorFoldingWithoutBoostWouldLandOneItemTooFar() {
        // 反向钉子:同一输入若无宿主增量会过冲到 (10,100)(绝对位 +H 错误)——
        // 有 boost 时余量落在宿主增长区内 (9,600):500+800=1300 容量内。
        // 绝对位:1000+400+600=2000=rawTarget(1200+800) ✓
        assertEquals(9 to 600, normalizeExpandAnchor(listOf(7 to 1000, 8 to 400, 9 to 500, 10 to 700), 2000, hostIndex = 9, hostGrowth = 800))
    }

    @Test
    fun unknownHostFallsBackToAnchorAsHost() {
        // 宿主未知(-1):按链首(锚)兜底——锚 item 是唯一既在链内又最常见的
        // 宿主构型(卡在锚 item 可见部=贴底带);此兜底保证最常见的
        // 超界场景(宿主=锚)不折叠。
        assertEquals(7 to 3328, normalizeExpandAnchor(listOf(7 to 2359), 3328, hostIndex = -1, hostGrowth = 1335))
    }

    @Test
    fun targetBeyondVisibleChainClampsToLastVisible() {
        // 链尽(目标超全部可见 item 含宿主增量):clamp 链尾(罕见——宿主在
        // 视口上部且 H 巨大;链外 item 尺寸未知不可继续折算)
        // 核账:2100−(1000+100)−400−500=100
        assertEquals(9 to 100, normalizeExpandAnchor(listOf(7 to 1000, 8 to 400, 9 to 500), 2100, hostIndex = 7, hostGrowth = 100))
    }

    @Test
    fun zeroSizePlaceholdersArePassedThrough() {
        // 零尺寸 item 无容量直接穿过(非宿主侧);宿主=锚(2,+50)
        // 目标 350>350 恰尽? 300+50=350 边界→穿全部→clamp 链尾 (4, 0)
        assertEquals(4 to 0, normalizeExpandAnchor(listOf(2 to 300, 3 to 0, 4 to 50), 350, hostIndex = 2, hostGrowth = 50))
        // 目标 340:340<350 落锚内
        assertEquals(2 to 340, normalizeExpandAnchor(listOf(2 to 300, 3 to 0, 4 to 50), 340, hostIndex = 2, hostGrowth = 50))
    }

    @Test
    fun normalizeMidListUnchanged() {
        // mid-list 常规:目标在锚 item 增长后容量内,原样
        assertEquals(9 to 6607, normalizeExpandAnchor(listOf(9 to 8194, 10 to 404), 6607, hostIndex = 5, hostGrowth = 120))
    }

    @Test
    fun midListDoesNotSkip() {
        assertFalse(bottomPinnedExpandSkip(fii = 7, fiso = 212))
        assertFalse(bottomPinnedExpandSkip(fii = 14, fiso = 0))
    }
}
