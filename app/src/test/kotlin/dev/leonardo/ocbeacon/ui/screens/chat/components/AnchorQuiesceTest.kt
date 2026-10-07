package dev.leonardo.ocbeacon.ui.screens.chat.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #529(2026-10-08):锚点捕获静止门——纯函数族。
 *
 * 根因(E1 脏样本定罪):反射目标 (fii,fiso+H) 的绝对位语义建立在「捕获→待定区
 * 消费之间布局基不变」上;回收重组合后首个展开集恰逢滚动余尾+item 异步内容跨
 * 测量遍落地,基漂移=同一 (idx,off) 请求的绝对落点漂移(真机 topY+H 整额双计/
 * +H/2 半计两样本)。本门把 settle 的静止契约(只管本卡内容)扩到整列基:
 * 滚动静止 ∧ fii/fiso+可见 item 尺寸谱指纹连续帧不变,之后才捕获锚点。
 */
class AnchorQuiesceTest {

    @Test
    fun `fingerprint encodes anchor and item size spectrum`() {
        val a = anchorBasisFingerprint(8, 4460, listOf(7 to 520, 8 to 2359, 9 to 118))
        val b = anchorBasisFingerprint(8, 4460, listOf(7 to 520, 8 to 2359, 9 to 118))
        assertEquals(a, b)
    }

    @Test
    fun `fingerprint distinguishes item size change`() {
        // item 尺寸谱任一变化(=E1 异步内容落地)必须改变指纹——这是门的观测面
        val a = anchorBasisFingerprint(8, 4460, listOf(7 to 520, 8 to 2359))
        val b = anchorBasisFingerprint(8, 4460, listOf(7 to 520, 8 to 2553))
        assertFalse(a == b)
    }

    @Test
    fun `fingerprint distinguishes anchor position drift`() {
        // 滚动余尾:fiso 漂移(尺寸谱未变)必须改变指纹
        val a = anchorBasisFingerprint(8, 4460, listOf(7 to 520, 8 to 2359))
        val b = anchorBasisFingerprint(8, 4461, listOf(7 to 520, 8 to 2359))
        assertFalse(a == b)
    }

    @Test
    fun `fingerprint distinguishes anchor item swap`() {
        // 锚点翻转(折叠/归一换锚 item)必须改变指纹
        val a = anchorBasisFingerprint(8, 100, listOf(7 to 520, 8 to 2359))
        val b = anchorBasisFingerprint(9, 100, listOf(7 to 520, 8 to 2359))
        assertFalse(a == b)
    }

    @Test
    fun `quiesce never advances on first frame`() {
        // 首帧无基线:等值指纹也不得计稳(「未观测≠静止」,同 settle 判稳语义)
        val fp = anchorBasisFingerprint(8, 4460, listOf(8 to 2359))
        assertFalse(anchorQuiesceAdvances(fp, null))
    }

    @Test
    fun `quiesce advances only on repeated fingerprint`() {
        val fp = anchorBasisFingerprint(8, 4460, listOf(8 to 2359))
        assertTrue(anchorQuiesceAdvances(fp, fp))
    }

    @Test
    fun `quiesce resets when basis drifts mid window`() {
        // 窗内基漂移(item 落地/余尾):与上帧指纹不等 → 不计稳(门重置由调用方
        // 以 lastFp=当前值 实现,本断言验证漂移帧本身绝不计稳)
        val before = anchorBasisFingerprint(8, 4460, listOf(8 to 2359))
        val after = anchorBasisFingerprint(8, 4460, listOf(8 to 2553))
        assertFalse(anchorQuiesceAdvances(after, before))
    }

    @Test
    fun `stability sequence matches settle convention`() {
        // 判稳序列:帧1 建基(不计稳) → 帧2 等值(计稳1) → 帧3 等值(计稳2=门开)。
        // 模拟 E1 风暴:帧1 建基 → 帧2 漂移(重置) → 帧3 建基 → 帧4/5 等值 → 门开。
        var lastFp: String? = null
        var stable = 0
        fun frame(fp: String) {
            if (anchorQuiesceAdvances(fp, lastFp)) stable++ else {
                stable = 0
                lastFp = fp
            }
        }
        val drift = anchorBasisFingerprint(8, 4460, listOf(8 to 2359))
        val settled = anchorBasisFingerprint(8, 4460, listOf(8 to 2553))
        frame(drift)
        assertEquals(0, stable)
        frame(settled) // 漂移帧:重置建新基
        assertEquals(0, stable)
        frame(settled)
        assertEquals(1, stable)
        frame(settled)
        assertEquals(2, stable) // 门开
    }
}
