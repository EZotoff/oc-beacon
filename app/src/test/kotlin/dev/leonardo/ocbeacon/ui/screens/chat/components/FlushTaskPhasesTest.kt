package dev.leonardo.ocbeacon.ui.screens.chat.components

import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import dev.leonardo.ocbeacon.ui.screens.chat.markdown.ScrollQuiescence
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #442 批次 C：flush 八职责深拆的表征测试（characterization）——拆分**前**钉住
 * 现行行为，拆分后逐条仍绿即证等价（终审 S2 深拆的前置安全网；终审 P1
 * 「拒绘方向反转」教训的直接补课：布尔语义必须有接线级测试）。
 *
 * 覆盖职责：帽首帧初始化 / 帽-only 释放免配对（贴底原点）/ 拒绘返回值契约 /
 * 滚动弃配 rebaseAll / 空账早退 / 对齐随态翻转门（追平态或手势期）。
 * 让位记忆已有 FlushTaskMemoryTest；释放计划纯函数已有 ReserveReleasePlanTest。
 */
class FlushTaskPhasesTest {

    private fun mockState(
        fii: Int,
        fiso: Int,
        scrolling: Boolean = false,
    ): LazyListState = mockk(relaxed = true) {
        every { firstVisibleItemIndex } returns fii
        every { firstVisibleItemScrollOffset } returns fiso
        every { isScrollInProgress } returns scrolling
        every { layoutInfo } returns mockk<LazyListLayoutInfo> {
            every { visibleItemsInfo } returns emptyList()
        }
    }

    private fun emptyLedger(): StreamingGrowLedger = mockk(relaxed = true) {
        every { hasPending } returns false
    }

    @Test
    fun `帽首帧初始化 - reserved未初始化时trueHeight直通且零派发`() {
        mockkObject(LazyListReflection)
        mockkObject(ScrollQuiescence)
        val reserve = HeightReserveState().apply {
            itemKey = "k1"
            trueHeight = 500
            reserved = -1
        }
        val task = streamingGrowFlushTask(mockState(0, 0), emptyLedger(), reserve)
        assertTrue(task.onPreDraw())
        assertEquals(500, reserve.reserved)
        verify(exactly = 0) { LazyListReflection.requestScrollToItemNoCancel(any(), any(), any(), any()) }
    }

    @Test
    fun `帽only释放贴底原点 - 免配对零滚动派发且reserved原子推进不拒绘`() {
        mockkObject(LazyListReflection)
        mockkObject(ScrollQuiescence)
        val reserve = HeightReserveState().apply {
            itemKey = "k1"
            trueHeight = 1066
            reserved = 1000
        }
        // fii=0/fiso=5=贴底原点 → plan(paired=false)；visibleItems 空 → growthIndex=null 兜底同判
        val task = streamingGrowFlushTask(mockState(0, 5), emptyLedger(), reserve)
        assertTrue(task.onPreDraw())
        assertEquals(1066, reserve.reserved)
        verify(exactly = 0) { LazyListReflection.requestScrollToItemNoCancel(any(), any(), any(), any()) }
    }

    @Test
    fun `ledger派发帧必须拒绘 - 终审P1契约`() {
        mockkObject(LazyListReflection)
        mockkObject(ScrollQuiescence)
        val ledger = mockk<StreamingGrowLedger>(relaxed = true) {
            every { hasPending } returns true
            every { takePaired(any(), any(), any()) } returns 100f
        }
        val task = streamingGrowFlushTask(mockState(7, 900), ledger, reserve = null)
        // StreamingGrowNode 直报真高（无裁剪）——ledger 派发帧拒绘是中间态唯一防线
        assertEquals(false, task.onPreDraw())
        verify(exactly = 1) { LazyListReflection.requestScrollToItemNoCancel(any(), 7, 1000, null) }
    }

    @Test
    fun `滚动进行中 - 弃配rebaseAll零派发takePaired不触`() {
        mockkObject(LazyListReflection)
        mockkObject(ScrollQuiescence)
        val ledger = mockk<StreamingGrowLedger>(relaxed = true) {
            every { hasPending } returns true
        }
        val task = streamingGrowFlushTask(mockState(7, 900, scrolling = true), ledger, reserve = null)
        assertTrue(task.onPreDraw())
        verify(exactly = 1) { ledger.rebaseAll() }
        verify(exactly = 0) { ledger.takePaired(any(), any(), any()) }
        verify(exactly = 0) { LazyListReflection.requestScrollToItemNoCancel(any(), any(), any(), any()) }
    }

    @Test
    fun `空账早退 - takePaired不触`() {
        mockkObject(LazyListReflection)
        mockkObject(ScrollQuiescence)
        val ledger = emptyLedger()
        val task = streamingGrowFlushTask(mockState(3, 200), ledger, reserve = null)
        assertTrue(task.onPreDraw())
        verify(exactly = 0) { ledger.takePaired(any(), any(), any()) }
    }

    @Test
    fun `对齐随态翻转 - 追平态允许翻转`() {
        mockkObject(LazyListReflection)
        mockkObject(ScrollQuiescence)
        val reserve = HeightReserveState().apply {
            itemKey = "k1"
            trueHeight = 500
            reserved = 500 // 追平：两种对齐像素等价=零位移翻转窗
            alignBottom = false
        }
        streamingGrowFlushTask(mockState(0, 0), emptyLedger(), reserve).onPreDraw()
        assertEquals(true, reserve.alignBottom)
    }

    @Test
    fun `对齐随态翻转 - 溢出且非手势期不翻转（刀锋防振荡）`() {
        mockkObject(LazyListReflection)
        mockkObject(ScrollQuiescence)
        val reserve = HeightReserveState().apply {
            itemKey = "k1"
            trueHeight = 500
            reserved = 400 // 溢出中：place 偏移≠0，翻转=一次性位移
            alignBottom = true // wantBottom=false(fii=3) 与当前不同，但门不通电
        }
        streamingGrowFlushTask(mockState(3, 500), emptyLedger(), reserve).onPreDraw()
        assertEquals(true, reserve.alignBottom)
    }
}
