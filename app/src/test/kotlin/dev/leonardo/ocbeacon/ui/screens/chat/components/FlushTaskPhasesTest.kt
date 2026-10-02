package dev.leonardo.ocbeacon.ui.screens.chat.components

import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import dev.leonardo.ocbeacon.ui.screens.chat.markdown.ScrollQuiescence
import dev.leonardo.ocbeacon.ui.screens.chat.scroll.PreDrawFlushTask
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

    private fun motive(msg: String) = println("[MOTIVE] $msg")

    // ============ #502：外部定居位静止采纳（永久让位死锁根修） ============

    /** 可变读位 mock——同 task 跨帧驱动（模拟用户甩动后落位静止）。 */
    private fun mutableState(): LazyListState {
        val state = mockk<LazyListState>(relaxed = true) {
            every { layoutInfo } returns mockk<LazyListLayoutInfo> {
                every { visibleItemsInfo } returns emptyList()
            }
            every { isScrollInProgress } returns false
            every { firstVisibleItemIndex } returns 7
            every { firstVisibleItemScrollOffset } returns 393
        }
        return state
    }

    private fun readAt(state: LazyListState, fii: Int, fiso: Int) {
        every { state.firstVisibleItemIndex } returns fii
        every { state.firstVisibleItemScrollOffset } returns fiso
    }

    /** 反射读 task 闭包捕获的 mem（相位行为断言需要 lastSet 可观测——测试缝）。 */
    private fun memOf(task: PreDrawFlushTask): FlushTaskMemory {
        val fld = task.javaClass.getDeclaredFields().first { it.type == FlushTaskMemory::class.java }
        fld.isAccessible = true
        return fld.get(task) as FlushTaskMemory
    }

    @Test
    fun `外部定居位两帧静止后采纳并恢复帽释放 - 永久让位死锁根修`() {
        motive("#502 相位级主断言：真机 17:08 场景——引擎在阅读位 set(7,393+Δ) 后用户甩回底 (0,0)。旧行为：yield 每帧作废帽释放计划（真机 yield×1448 / reserved 冻 685 达 12s = 流式消息被裁剪在视口小块直到完结）。新行为：首帧让位观察，次帧静止即采纳基线并当帧释放帽")
        mockkObject(LazyListReflection)
        mockkObject(ScrollQuiescence)
        val state = mutableState()
        val reserve = HeightReserveState().apply { itemKey = "k1"; reserved = 685; trueHeight = 685 }
        val ledger = mockk<StreamingGrowLedger>(relaxed = true)
        every { ledger.hasPending } returns true
        every { ledger.takePaired(any(), any(), any()) } returns 66f
        val task = streamingGrowFlushTask(state, ledger, reserve)

        // 帧 1：阅读位增长配对——引擎 apply set → lastSet=(7,459)（真机 set(7,393) 同构）
        task.onPreDraw()
        val mem = memOf(task)
        assertEquals(7, mem.lastSetFii)

        // 帧 2：用户甩回底 (0,0)，真高增长（1128>685 待释放）→ divergence 首帧：让位观察，帽不动
        every { ledger.hasPending } returns false
        every { ledger.takePaired(any(), any(), any()) } returns 0f
        readAt(state, 0, 0)
        reserve.trueHeight = 1128
        assertTrue(task.onPreDraw())
        assertEquals("让位观察帧不得释放帽", 685, reserve.reserved)

        // 帧 3：读位静止 (0,0) → 采纳基线 + 当帧帽释放（旧代码此处永远 yield——死锁点）
        assertTrue(task.onPreDraw())
        assertEquals("采纳后帽释放必须当帧生效（真高直通）", 1128, reserve.reserved)
        assertEquals(0, mem.lastSetFii)
    }

    @Test
    fun `引擎pending未消费窗口不采纳 - 近帧set保护`() {
        motive("#502 回归锚：引擎 set 请求时即写 lastSet、measure 消费前读位=旧位且静止（上方内容闪烁消失根修场景）——近帧（<2 帧）set 存在时不得把旧位采纳为基线毁掉 pending 保护")
        mockkObject(LazyListReflection)
        mockkObject(ScrollQuiescence)
        val state = mutableState()
        readAt(state, 7, 66)
        val reserve = HeightReserveState().apply { itemKey = "k1"; reserved = 100; trueHeight = 100 }
        val ledger = mockk<StreamingGrowLedger>(relaxed = true)
        every { ledger.hasPending } returns true
        every { ledger.takePaired(any(), any(), any()) } returns 66f
        val task = streamingGrowFlushTask(state, ledger, reserve)

        // 帧 1：引擎配对 set → lastSet=(7,132)（目标位=读位+Δ），lastSetFrame=帧1
        task.onPreDraw()
        assertEquals(7, memOf(task).lastSetFii)

        // 帧 2：pending 未消费——读位仍旧位 (7,66) ≠ lastSet(7,132)；静止但 set 在近帧 → 继续让位
        every { ledger.hasPending } returns false
        reserve.trueHeight = 200
        assertTrue(task.onPreDraw())
        assertEquals("近帧 set 保护期不得采纳释放帽", 100, reserve.reserved)
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
