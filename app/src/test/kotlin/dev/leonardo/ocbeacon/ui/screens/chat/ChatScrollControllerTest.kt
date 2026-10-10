package dev.leonardo.ocbeacon.ui.screens.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-08-16 根治：死代码根因 —— 发送后滚底 ForceScrollExecutor 单测。
 *
 * 覆盖三条主路径：
 * ① tick 后 totalItemsCount 增长 → 执行 requestScrollToItem(0)
 * ② fling 惯性中（isScrollInProgress true→false）→ 等待停止后仍滚
 * ③ 5s 超时（count 永不增长）→ 超时后仍滚 + 超时日志
 * ④ 滚后位置不收敛 → 也不二次重滚（#542 退役：单次锚定即终态，
 *    事后校验重滚零承重且有强拉翻历史用户的阅读位置违例面）。
 *
 * 说明：LazyListState 依赖快照/布局体系难以干净 mock，故逻辑已抽为
 * [ForceScrollExecutor] + [ScrollListGate]（抽函数优于 hack mock），
 * 本测试用 State 驱动的 Fake 门面验证 —— snapshotFlow 可真实订阅其变化。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatScrollControllerTest {

    /** State 驱动的滚动门面 Fake：snapshotFlow 能真实订阅其属性变化。 */
    private class FakeGate(
        initialCount: Int = 10,
        initialIndex: Int = 3,
        initialOffset: Int = 0,
    ) : ScrollListGate {
        var count by mutableIntStateOf(initialCount)
        var scrolling by mutableStateOf(false)
        var index by mutableIntStateOf(initialIndex)
        var offset by mutableIntStateOf(initialOffset)

        /** 每次 requestScrollToItem 调用时 isScrollInProgress 的快照（断言时序）。 */
        val progressFlagAtScrollCalls = mutableListOf<Boolean>()

        /** 滚动后落点模拟：默认立即到位（index=0/offset=0，即 reverseLayout 底部）。 */
        var applyScroll: () -> Unit = {
            index = 0
            offset = 0
        }

        override val totalItemsCount: Int get() = count
        override val isScrollInProgress: Boolean get() = scrolling
        override val firstVisibleItemIndex: Int get() = index
        override val firstVisibleItemScrollOffset: Int get() = offset

        override fun requestScrollToItem(index: Int) {
            progressFlagAtScrollCalls.add(scrolling)
            applyScroll()
        }
    }

    private fun executor(
        gate: FakeGate,
        logs: MutableList<String>,
        leaseActive: () -> Boolean = { false },
    ) = ForceScrollExecutor(
        gate = gate,
        onGrowthTimeout = { logs.add(it) },
        leaseActive = leaseActive, // #423 I3:视口租约探针
    )

    /**
     * 显式通知全局快照应用。JVM 单测无 AndroidUiDispatcher/GlobalSnapshotManager，
     * MutableState 全局写入不会自动触发 apply observer（snapshotFlow 依赖其重发）；
     * 生产环境由帧调度保证，无需此调用。
     */
    private fun applySnapshot() = Snapshot.sendApplyNotifications()

    // ============ ① 消息增长 → 滚底 ============

    @Test
    fun `execute scrolls to bottom when totalItemsCount grows`() = runTest {
        val gate = FakeGate(initialCount = 10, initialIndex = 2)
        val logs = mutableListOf<String>()
        launch { delay(100); gate.count = 11; applySnapshot() } // 模拟 POST 往返 + SSE 回显后消息入列

        executor(gate, logs).execute()

        assertEquals("消息增长后应恰好滚动一次", 1, gate.progressFlagAtScrollCalls.size)
        assertEquals("增长路径不应记超时日志", 0, logs.size)
        assertEquals("应锚定到 index 0（底部）", 0, gate.index)
    }

    // ============ ② fling 中到达 → 等待停止后仍滚 ============

    @Test
    fun `execute waits for fling to finish before scrolling`() = runTest {
        val gate = FakeGate(initialCount = 10, initialIndex = 2)
        gate.scrolling = true // 用户 fling 惯性进行中
        launch { delay(100); gate.count = 11; applySnapshot() }
        launch { delay(300); gate.scrolling = false; applySnapshot() }

        val logs = mutableListOf<String>()
        executor(gate, logs).execute()

        assertEquals(1, gate.progressFlagAtScrollCalls.size)
        assertTrue(
            "滚动必须发生在 fling 结束之后（不得在 isScrollInProgress=true 时抢滚）",
            !gate.progressFlagAtScrollCalls.single(),
        )
        assertEquals(0, logs.size)
    }

    // ============ ③ 5s 超时兜底 → 仍滚 + 日志 ============

    @Test
    fun `execute scrolls anyway with log after growth timeout`() = runTest {
        val gate = FakeGate(initialCount = 10, initialIndex = 2)
        val logs = mutableListOf<String>()
        // 不安排 count 增长：模拟发送失败/无 SSE 回显

        executor(gate, logs).execute()

        assertEquals("超时兜底后仍应滚动", 1, gate.progressFlagAtScrollCalls.size)
        assertEquals("超时路径应记一条日志", 1, logs.size)
    }

    // ============ ④ #542：滚后位置不收敛 → 也不二次重滚（事后校验已退役） ============

    @Test
    fun `execute does not re-scroll when position never converges`() = runTest {
        val gate = FakeGate(initialCount = 10, initialIndex = 2)
        gate.applyScroll = { // 位置永不收敛（旧 verify-retry 的触发场景）
            gate.index = 0
            gate.offset = 250
        }
        launch { delay(100); gate.count = 11; applySnapshot() }

        val logs = mutableListOf<String>()
        executor(gate, logs).execute()

        assertEquals("单次锚定即终态，不得事后重滚（#542 取证退役）", 1, gate.progressFlagAtScrollCalls.size)
        assertEquals(0, logs.size)
    }

    // ============ ⑤ #423 I3:视口租约等待 ============

    @Test
    fun `execute waits for viewport lease release before anchoring`() = runTest {
        val gate = FakeGate(initialCount = 10, initialIndex = 2)
        var lease by mutableStateOf(true) // 在途 episode(如发送时正在展开卡片)
        val anchorTimes = mutableListOf<Long>()
        launch { delay(100); gate.count = 11; applySnapshot() } // 消息增长
        launch { delay(200); lease = false; applySnapshot() }   // episode 结束,租约释放
        gate.applyScroll = {
            gate.index = 0
            gate.offset = 0
            anchorTimes.add(currentTime)
        }

        executor(gate, mutableListOf(), leaseActive = { lease }).execute()

        assertEquals(1, gate.progressFlagAtScrollCalls.size)
        assertTrue(
            "锚定必须发生在租约释放之后(与 episode end-restore 互搏消除)",
            anchorTimes.single() >= 200,
        )
    }

    @Test
    fun `execute anchors anyway after lease wait timeout`() = runTest {
        val gate = FakeGate(initialCount = 10, initialIndex = 2)
        var lease by mutableStateOf(true) // 永不释放(极端:episode 卡死)
        launch { delay(100); gate.count = 11; applySnapshot() }

        executor(gate, mutableListOf(), leaseActive = { lease }).execute()

        assertEquals("租约等待超时后仍强制锚定(发送后跟随之义)", 1, gate.progressFlagAtScrollCalls.size)
    }
}
