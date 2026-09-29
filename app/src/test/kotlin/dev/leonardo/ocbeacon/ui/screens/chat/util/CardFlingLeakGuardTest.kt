package dev.leonardo.ocbeacon.ui.screens.chat.util

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #474 六轮:卡内滚动泄漏分通道语义——拖拽(UserInput)自然传导零干预、
 * 惯性(SideEffect)边界吸收(逐帧+残速双通道);拖拽通道禁止任何取反/注入
 * (五轮反转注入曾致卡底下滑视口反向上移,用户实测否决)。
 */
class CardFlingLeakGuardTest {

    private val conn = cardLeakGuardConnection()

    @Test
    fun dragLeftoverPassesThroughUntouched() {
        // 拖拽到边剩余 50px:零消费零注入,协议自然上传(方向由 LazyList 归一)
        assertEquals(Offset.Zero, conn.onPostScroll(Offset.Zero, Offset(0f, -50f), NestedScrollSource.UserInput))
    }

    // 滚轮/Relocate 等其它来源(ui 1.12 中 Wheel 已并入 UserInput)由
    // leakDisposition 的 else 分支统一放行——分类测试见下,不逐常量断言
    // (Wheel/Relocate 常量本版本已标废弃)。

    @Test
    fun flingFrameLeftoverFullyAbsorbed() {
        // 惯性逐帧泄漏(五轮实证:子 fling 每帧 -8px 走 onPostScroll 通道)全额吞没
        assertEquals(Offset(0f, -8f), conn.onPostScroll(Offset.Zero, Offset(0f, -8f), NestedScrollSource.SideEffect))
    }

    @Test
    fun preFlingNeverInterceptsChild() = runTest {
        assertEquals(Velocity.Zero, conn.onPreFling(Velocity(0f, 1200f)))
    }

    @Test
    fun flingResidualVelocityFullyAbsorbed() = runTest {
        // 制动残速(原始主诉:-1480px/s 一帧拉底)全吞没,不上传不注入
        val v = Velocity(0f, -1480f)
        assertEquals(v, conn.onPostFling(Velocity.Zero, v))
    }

    @Test
    fun leakDispositionClassification() {
        assertEquals(CardLeakDisposition.ChainNaturally, leakDisposition(NestedScrollSource.UserInput))
        assertEquals(CardLeakDisposition.AbsorbAtEdge, leakDisposition(NestedScrollSource.SideEffect))
    }

    // ===== #476: 拖拽链传导解除 autoScroll 武装 =====

    @Test
    fun disarmOnlyForRealUserInput() {
        assertTrue(shouldDisarmOnChain(NestedScrollSource.UserInput))
        assertFalse(shouldDisarmOnChain(NestedScrollSource.SideEffect))
        // Relocate=程序化重定位(bringIntoView),不得当作用户滚动
        assertFalse(shouldDisarmOnChain(NestedScrollSource.Relocate))
    }

    @Test
    fun userDragChainInvokesDisarmHook() {
        var disarmed = 0
        val c = cardLeakGuardConnection(onUserChain = { disarmed++ })
        c.onPostScroll(Offset.Zero, Offset(0f, -40f), NestedScrollSource.UserInput)
        assertEquals(1, disarmed)
        // 惯性吸收通道不解除(无外层移动,用户未取得视口)
        c.onPostScroll(Offset.Zero, Offset(0f, -8f), NestedScrollSource.SideEffect)
        assertEquals(1, disarmed)
        // 程序化重定位不解除
        c.onPostScroll(Offset.Zero, Offset(0f, 30f), NestedScrollSource.Relocate)
        assertEquals(1, disarmed)
    }
}
