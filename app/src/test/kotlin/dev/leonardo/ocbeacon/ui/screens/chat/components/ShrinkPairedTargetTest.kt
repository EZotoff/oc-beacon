package dev.leonardo.ocbeacon.ui.screens.chat.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * #540 二轮（表述乙·揭示缘参照，裁决 2026-10-11）：窗内负向（收缩）配对决策表。
 *
 * 判定语义：锚在塌缩区 [stickTop, stickTop+spanDepth]（item 空间距顶坐标）内 →
 * 粘揭示缘（目标=本帧真高−stickTop，随塌缩逐帧追踪卡头）；区外 → 零操作
 * （LazyList 默认锚定保持本就正确，盲镜像位移=col4 跳变自造，journal §22 D3
 * 修正读法）；未捕获 → 保守镜像钳底。
 *
 * 夹具基线：item 真高 5000，揭示盒距顶 4000、盒高 800（塌缩区 [4000,4800]，
 * 盒下方新内容 200）。锚距顶 = itemTrueH − fiso（fiso 自 item 底向上计量）。
 */
class ShrinkPairedTargetTest {

    @Test
    fun `锚在塌缩区内 粘揭示缘`() {
        // fiso=400 → 距顶 4600 ∈ [4000,4800]；目标 = 5000−4000 = 1000
        assertEquals(
            1000,
            shrinkPairedTargetFiso(fiso = 400, itemTrueH = 5000, delta = -800, stickTop = 4000, spanDepth = 800),
        )
    }

    @Test
    fun `锚在塌缩区下界同样粘`() {
        // fiso=1000 → 距顶 4000 = stickTop（边界含入）
        assertEquals(
            1000,
            shrinkPairedTargetFiso(fiso = 1000, itemTrueH = 5000, delta = -800, stickTop = 4000, spanDepth = 800),
        )
    }

    @Test
    fun `锚在塌缩区上界同样粘`() {
        // fiso=200 → 距顶 4800 = stickTop+spanDepth（边界含入）
        assertEquals(
            1000,
            shrinkPairedTargetFiso(fiso = 200, itemTrueH = 5000, delta = -800, stickTop = 4000, spanDepth = 800),
        )
    }

    @Test
    fun `粘住目标随塌缩逐帧追踪卡头`() {
        // 塌缩中：真高 4600（盒已塌 400），揭示盒距顶不变 4000；锚距顶 4100
        // （fiso=500）仍在区内 → 目标 = 4600−4000 = 600
        assertEquals(
            600,
            shrinkPairedTargetFiso(fiso = 500, itemTrueH = 4600, delta = -400, stickTop = 4000, spanDepth = 800),
        )
        // 终态：真高 4200（盒塌完）；锚经前帧反射已骑在缘上（fiso=200 → 距顶
        // 4000 = stickTop 边界），目标 = 4200−4000 = 200（盒下新内容上移贴卡头）
        assertEquals(
            200,
            shrinkPairedTargetFiso(fiso = 200, itemTrueH = 4200, delta = -800, stickTop = 4000, spanDepth = 800),
        )
    }

    @Test
    fun `锚在塌缩区下方新内容侧 零操作`() {
        // fiso=1500 → 距顶 3500 < 4000（盒下方）：LazyList 默认锚定本就稳定
        assertNull(
            shrinkPairedTargetFiso(fiso = 1500, itemTrueH = 5000, delta = -800, stickTop = 4000, spanDepth = 800),
        )
    }

    @Test
    fun `锚在塌缩区上方旧内容侧 零操作`() {
        // fiso=100 → 距顶 4900 > 4800（盒上方）：同免
        assertNull(
            shrinkPairedTargetFiso(fiso = 100, itemTrueH = 5000, delta = -800, stickTop = 4000, spanDepth = 800),
        )
    }

    @Test
    fun `未捕获区 保守镜像钳底`() {
        // col4 形态：fiso=277，收缩 −3655 → 镜像越界钳 0
        assertEquals(
            0,
            shrinkPairedTargetFiso(fiso = 277, itemTrueH = 5000, delta = -3655, stickTop = -1, spanDepth = -1),
        )
        // col3 形态：fiso=4287 → 镜像 632
        assertEquals(
            632,
            shrinkPairedTargetFiso(fiso = 4287, itemTrueH = 5000, delta = -3655, stickTop = -1, spanDepth = -1),
        )
    }

    @Test
    fun `spanDepth 未捕获同样走保守镜像`() {
        assertEquals(
            0,
            shrinkPairedTargetFiso(fiso = 277, itemTrueH = 5000, delta = -3655, stickTop = 4000, spanDepth = -1),
        )
    }
}
