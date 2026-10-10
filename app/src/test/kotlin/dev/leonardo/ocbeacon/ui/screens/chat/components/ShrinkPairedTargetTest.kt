package dev.leonardo.ocbeacon.ui.screens.chat.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * #540 二轮（表述乙·揭示缘参照，裁决 2026-10-11；同日用户复测定罪修订）：
 * 窗内负向（收缩）配对决策表。
 *
 * 修订版判定语义：锚在揭示盒底**及以上**（盒内或盒上方旧内容侧——该侧内容
 * 坐标随塌缩逐帧下移）→ **逐帧镜像骑行**（保持画面固定，自然落位 fiso₀−H）；
 * 盒底以下（新内容侧，坐标不变）→ **零操作**（LazyList 默认锚定本就正确）。
 * 取代旧版「区内跳揭示缘」（锚深体内时一次性拖下半部分）与「盒上方零操作」
 * （漏配镜像=上半部分被动被拖）——用户复测定罪两 face 同除。
 *
 * 夹具基线：item 真高 5000，揭示盒距顶 4000、盒高 800（盒占 from-top
 * [4000,4800]，盒下方新内容 [4800,5000]）。锚距顶 = itemTrueH − fiso
 * （fiso 自 item 底向上计量）。
 */
class ShrinkPairedTargetTest {

    @Test
    fun `锚在盒内 逐帧镜像`() {
        // fiso=700 → 距顶 4300 ∈ [4000,4800]；镜像 = 700−300 = 400
        assertEquals(
            400,
            shrinkPairedTargetFiso(fiso = 700, itemTrueH = 5000, delta = -300, stickTop = 4000, spanDepth = 800),
        )
    }

    @Test
    fun `锚在盒上方旧内容侧 同样镜像（修订②面）`() {
        // fiso=4900 → 距顶 100 < 4000（卡上方）——上方内容坐标随塌缩下移，
        // 旧版零操作漏配镜像=上半部分被拖；镜像 = 4900−800 = 4100
        assertEquals(
            4100,
            shrinkPairedTargetFiso(fiso = 4900, itemTrueH = 5000, delta = -800, stickTop = 4000, spanDepth = 800),
        )
    }

    @Test
    fun `盒底边界（含入）镜像`() {
        // fiso=200 → 距顶 4800 = 盒底（边界含入镜像侧）
        assertEquals(
            100,
            shrinkPairedTargetFiso(fiso = 200, itemTrueH = 5000, delta = -100, stickTop = 4000, spanDepth = 800),
        )
    }

    @Test
    fun `锚在盒下方新内容侧 零操作`() {
        // fiso=100 → 距顶 4900 > 4800（盒下方）：坐标不变，默认锚定正确
        assertNull(
            shrinkPairedTargetFiso(fiso = 100, itemTrueH = 5000, delta = -800, stickTop = 4000, spanDepth = 800),
        )
    }

    @Test
    fun `镜像越界钳 item 底（跨 item 折叠的有界近似）`() {
        // fiso=400，塌缩 −800 → −400 钳 0
        assertEquals(
            0,
            shrinkPairedTargetFiso(fiso = 400, itemTrueH = 5000, delta = -800, stickTop = 4000, spanDepth = 800),
        )
    }

    @Test
    fun `镜像骑行保持锚距顶不变（跨帧追踪）`() {
        // 帧1：fiso=700 距顶 4300，塌 400 → 镜像 300
        assertEquals(
            300,
            shrinkPairedTargetFiso(fiso = 700, itemTrueH = 4600, delta = -400, stickTop = 4000, spanDepth = 800),
        )
        // 帧2：真高 4200（盒塌完），fiso 已骑至 300 → 距顶 3900 仍 ≤4800，续镜像
        assertEquals(
            0,
            shrinkPairedTargetFiso(fiso = 300, itemTrueH = 4200, delta = -400, stickTop = 4000, spanDepth = 800),
        )
    }

    @Test
    fun `未捕获几何 保守镜像钳底`() {
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
