package dev.leonardo.ocbeacon.ui.screens.chat.components

import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import dev.leonardo.ocbeacon.ui.screens.chat.markdown.ScrollQuiescence
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.verify
import org.junit.Test

/**
 * #438 R-2（2026-09-28 让位防御死接线根修）的接线级回归测试。
 *
 * 机制：[streamingGrowFlushTask] 返回的 flush 任务在配对 set 后记录目标位
 * （lastSetFii/lastSetFiso），下一帧若读位 ≠ 上批 set 目标 = 存在未消费的
 * 外部 pending → shouldYieldPairing 让位（配对覆盖写会抵消显式意图）。
 *
 * 死接线（修复前）：记忆变量声明在 PreDrawFlushTask lambda 体内——flush
 * 每帧调用，每次重置 null → 让位分支从未生效（cb733d80 引入即失效；
 * #438/#442 调研双源互证）。本测试钉死「同一 task 实例跨帧保留记忆」——
 * 纯函数侧已由 PairingYieldTest 覆盖，这里测接线。
 */
class FlushTaskMemoryTest {

    private fun mockState(fii: Int, fiso: Int): LazyListState = mockk(relaxed = true) {
        every { firstVisibleItemIndex } returns fii
        every { firstVisibleItemScrollOffset } returns fiso
        every { isScrollInProgress } returns false
        every { layoutInfo } returns mockk<LazyListLayoutInfo> {
            every { visibleItemsInfo } returns emptyList()
        }
    }

    @Test
    fun `task remembers lastSet across frames and yields on external pending`() {
        mockkObject(LazyListReflection)
        mockkObject(ScrollQuiescence)
        val ledger = mockk<StreamingGrowLedger>(relaxed = true)
        val state = mockState(0, 0)

        // 帧1：账本有 pending，配对 set 目标位 (0, 100)
        every { ledger.hasPending } returns true
        every { ledger.takePaired(any(), any(), any()) } returns 100f
        val task = streamingGrowFlushTask(state, ledger, reserve = null)
        val frame1 = task.onPreDraw()
        // ledger 派发帧拒绘（终审 P1 契约）
        check(frame1 == false)
        verify(exactly = 1) { LazyListReflection.requestScrollToItemNoCancel(state, 0, 100, null) }

        // 帧2：用户外部滚动使读位偏离上批 set 目标 → 必须让位（rebase 弃配）
        every { state.firstVisibleItemIndex } returns 3
        every { state.firstVisibleItemScrollOffset } returns 555
        every { ledger.hasPending } returns true
        val frame2 = task.onPreDraw()
        verify(exactly = 1) { ledger.rebaseAll() } // yield 分支的唯一可观测行为
        check(frame2 == true)
    }

    @Test
    fun `no memory leakage between task instances`() {
        mockkObject(LazyListReflection)
        mockkObject(ScrollQuiescence)
        val ledger = mockk<StreamingGrowLedger>(relaxed = true)
        every { ledger.hasPending } returns true
        every { ledger.takePaired(any(), any(), any()) } returns 50f
        val s1 = mockState(0, 0)
        streamingGrowFlushTask(s1, ledger).onPreDraw()
        // 新 task 实例：无历史记忆——读位≠null 哨兵不成立，不 yield
        val s2 = mockState(9, 999)
        every { ledger.hasPending } returns true
        every { ledger.takePaired(any(), any(), any()) } returns 50f
        streamingGrowFlushTask(s2, ledger).onPreDraw()
        verify(exactly = 0) { ledger.rebaseAll() }
    }
}
