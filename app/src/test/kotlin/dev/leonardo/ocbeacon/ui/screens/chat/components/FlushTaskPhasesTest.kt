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

    // ============ #540 方向 A：展开配对窗的 flush 接线 ============

    @Test
    fun `540 展开窗内帽释放恒配对 - 贴底原点走反射不nodis`() {
        mockkObject(LazyListReflection)
        mockkObject(ScrollQuiescence)
        val reserve = HeightReserveState().apply {
            itemKey = "k1"
            trueHeight = 1066
            reserved = 1000
            expandPairingWindows = 1
        }
        // 贴底原点存量语义是 nodis；窗内必须反射配对（消除流式期展开上推源）
        val task = streamingGrowFlushTask(mockState(0, 5), emptyLedger(), reserve)
        assertTrue(task.onPreDraw())
        assertEquals(1066, reserve.reserved)
        verify(exactly = 1) { LazyListReflection.requestScrollToItemNoCancel(any(), 0, 71, null) }
    }

    /** 可见窗内含物主 item 的 mock（物主守卫测试缝）。 */
    private fun stateWithOwnerItem(fii: Int, fiso: Int, ownerIndex: Int, ownerKey: Any = "k1"): LazyListState =
        mockk(relaxed = true) {
            every { firstVisibleItemIndex } returns fii
            every { firstVisibleItemScrollOffset } returns fiso
            every { isScrollInProgress } returns false
            every { layoutInfo } returns mockk<LazyListLayoutInfo> {
                every { visibleItemsInfo } returns listOf(
                    mockk<androidx.compose.foundation.lazy.LazyListItemInfo> {
                        every { index } returns ownerIndex
                        every { key } returns ownerKey
                    },
                )
            }
        }

    @Test
    fun `540 二轮 未捕获几何的窗内负向 - 反射保守镜像 不再走dispatch`() {
        mockkObject(LazyListReflection)
        mockkObject(ScrollQuiescence)
        val reserve = HeightReserveState().apply {
            itemKey = "k1"
            trueHeight = 900
            reserved = 1000
            expandPairingWindows = 1
            // 未捕获 stickTop/spanDepth（默认 -1）——保守镜像：100−100=0
        }
        val state = stateWithOwnerItem(0, 100, ownerIndex = 0)
        every { state.dispatchRawDelta(any()) } returns -100f
        val task = streamingGrowFlushTask(state, emptyLedger(), reserve)
        assertTrue(task.onPreDraw())
        assertEquals(900, reserve.reserved)
        // 二轮裁决（2026-10-11）：负向弃 dispatch 镜像（底缘钳制缺口 D3 定罪）
        verify(exactly = 0) { state.dispatchRawDelta(any()) }
        verify(exactly = 1) { LazyListReflection.requestScrollToItemNoCancel(any(), 0, 0, null) }
    }

    @Test
    fun `540 二轮修订 锚在塌缩区内 - 逐帧镜像骑行不跳揭示缘`() {
        mockkObject(LazyListReflection)
        mockkObject(ScrollQuiescence)
        val reserve = HeightReserveState().apply {
            itemKey = "k1"
            trueHeight = 4200 // 本帧收缩后真高
            reserved = 5000
            expandPairingWindows = 1
            expandCollapseStickTop = 4000
            expandCollapseSpanDepth = 800
        }
        // 锚 fiso=200 → 距顶 4200−200=4000 ≤ 4800（镜像侧）。修订前「跳揭示缘」
        // 在锚深体内时一次性拖下半部分（用户复测定罪）；修订后逐帧镜像：
        // 200−800 → 钳 0
        val state = stateWithOwnerItem(3, 200, ownerIndex = 3)
        every { state.dispatchRawDelta(any()) } returns 0f
        assertTrue(streamingGrowFlushTask(state, emptyLedger(), reserve).onPreDraw())
        assertEquals(4200, reserve.reserved)
        verify(exactly = 0) { state.dispatchRawDelta(any()) }
        verify(exactly = 1) { LazyListReflection.requestScrollToItemNoCancel(any(), 3, 0, null) }
    }

    @Test
    fun `540 二修订 锚在盒下方新内容侧 - 零操作仅帽回写`() {
        mockkObject(LazyListReflection)
        mockkObject(ScrollQuiescence)
        val reserve = HeightReserveState().apply {
            itemKey = "k1"
            trueHeight = 4200
            reserved = 5000
            expandPairingWindows = 1
            expandCollapseStickTop = 4000
            expandCollapseSpanDepth = 800
        }
        // 夹具：盒 [4000,4100]（span 收窄为 100），盒下方新内容 [4100,4200]；
        // 锚 fiso=50 → 距顶 4150 > 4100（盒下方）——零操作
        reserve.expandCollapseSpanDepth = 100
        val state = stateWithOwnerItem(3, 50, ownerIndex = 3)
        every { state.dispatchRawDelta(any()) } returns 0f
        assertTrue(streamingGrowFlushTask(state, emptyLedger(), reserve).onPreDraw())
        assertEquals(4200, reserve.reserved)
        verify(exactly = 0) { state.dispatchRawDelta(any()) }
        verify(exactly = 0) { LazyListReflection.requestScrollToItemNoCancel(any(), any(), any(), any()) }
    }

    @Test
    fun `540 review 物主守卫 - 锚不在物主item按区外零操作`() {
        mockkObject(LazyListReflection)
        mockkObject(ScrollQuiescence)
        val reserve = HeightReserveState().apply {
            itemKey = "k1"
            trueHeight = 4200
            reserved = 5000
            expandPairingWindows = 1
            expandCollapseStickTop = 4000
            expandCollapseSpanDepth = 800
        }
        // 排队发消息形态：物主可见于 index 7，锚在 index 3（fiso 属他 item——
        // anchorTop=itemTrueH−fiso 为跨 item 垃圾值，曾可误判入区向错误 item
        // 写 stick 目标；review 轮 P2 守卫后一律区外零操作）
        val state = stateWithOwnerItem(3, 200, ownerIndex = 7)
        every { state.dispatchRawDelta(any()) } returns 0f
        assertTrue(streamingGrowFlushTask(state, emptyLedger(), reserve).onPreDraw())
        assertEquals(4200, reserve.reserved)
        verify(exactly = 0) { state.dispatchRawDelta(any()) }
        verify(exactly = 0) { LazyListReflection.requestScrollToItemNoCancel(any(), any(), any(), any()) }
    }

    @Test
    fun `540 review 混合帧拆账 - 镜像格ledger增长折加不被吞`() {
        mockkObject(LazyListReflection)
        mockkObject(ScrollQuiescence)
        val reserve = HeightReserveState().apply {
            itemKey = "k1"
            trueHeight = 4200
            reserved = 5000
            expandPairingWindows = 1
            expandCollapseStickTop = 4000
            expandCollapseSpanDepth = 800
        }
        // 输出中收起且锚在镜像侧（距顶 4200−1500=2700 ≤4800）：帽净负 −800 镜像
        // 骑行 + 同帧 ledger 增长 +150 折加——review 轮 P1 拆账：1500−800+150=850
        val ledger = emptyLedger()
        every { ledger.hasPending } returns true
        every { ledger.takePaired(any(), any(), any()) } returns 150f
        val state = stateWithOwnerItem(3, 1500, ownerIndex = 3)
        every { state.dispatchRawDelta(any()) } returns 0f
        assertTrue(streamingGrowFlushTask(state, ledger, reserve).onPreDraw())
        assertEquals(4200, reserve.reserved)
        verify(exactly = 0) { state.dispatchRawDelta(any()) }
        verify(exactly = 1) { LazyListReflection.requestScrollToItemNoCancel(any(), 3, 850, null) }
    }

    @Test
    fun `540 review 混合帧拆账 - noop格（盒下方）ledger仍折加`() {
        mockkObject(LazyListReflection)
        mockkObject(ScrollQuiescence)
        val reserve = HeightReserveState().apply {
            itemKey = "k1"
            trueHeight = 4200
            reserved = 5000
            expandPairingWindows = 1
            expandCollapseStickTop = 4000
            expandCollapseSpanDepth = 100 // 盒 [4000,4100]，下方 [4100,4200]
        }
        // 锚 fiso=50 → 距顶 4150（盒下方）塌缩零操作，但 ledger +150 仍配对
        val ledger = emptyLedger()
        every { ledger.hasPending } returns true
        every { ledger.takePaired(any(), any(), any()) } returns 150f
        val state = stateWithOwnerItem(3, 50, ownerIndex = 3)
        every { state.dispatchRawDelta(any()) } returns 0f
        assertTrue(streamingGrowFlushTask(state, ledger, reserve).onPreDraw())
        assertEquals(4200, reserve.reserved)
        verify(exactly = 0) { state.dispatchRawDelta(any()) }
        // noop 格 ledger 折加：50+150=200（塌缩分量零操作）
        verify(exactly = 1) { LazyListReflection.requestScrollToItemNoCancel(any(), 3, 200, null) }
    }

    @Test
    fun `540 review 亚像素负余量 - 净量(-1,0)不崩flush`() {
        mockkObject(LazyListReflection)
        mockkObject(ScrollQuiescence)
        val reserve = HeightReserveState().apply {
            itemKey = "k1"
            trueHeight = 4999
            reserved = 5000
            expandPairingWindows = 1
        }
        // 帽净负 −1（整数 delta）+ ledger 分数 +0.6 → total=−0.4∈(−1,0)：
        // 旧实现 total.toInt()=0 传 require(delta<0) 抛 IAE 崩 flush（review 轮
        // P2 防御缺口）。新实现以 reserveShift（−1）过判定、ledger 折加取整。
        val ledger = emptyLedger()
        every { ledger.hasPending } returns true
        every { ledger.takePaired(any(), any(), any()) } returns 0.6f
        val state = stateWithOwnerItem(3, 500, ownerIndex = 3)
        assertTrue(streamingGrowFlushTask(state, ledger, reserve).onPreDraw())
        // 无崩溃即主断言；未捕获几何走镜像格：500−1+0=499
        verify(exactly = 1) { LazyListReflection.requestScrollToItemNoCancel(any(), 3, 499, null) }
    }
}
