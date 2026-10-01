package dev.leonardo.ocbeacon.ui.screens.chat.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #442 R2 分片唤醒（批次 A2 内核）——武装/触发状态机测试（spec §2 语义钉死）。
 */
class StreamingSplitMachineTest {

    private fun para(tag: String) = "# $tag\n内容行。\n\n"
    private val snapshot = (1..30).joinToString("") { para("P$it") }
    private val min = 100

    @Test
    fun `未达门槛无动作`() {
        val m = StreamingSplitMachine(minFreezeChars = min)
        val act = m.onBatch(snapshot, released = 50, quiescent = true)
        assertTrue(act is SplitAction.None)
        assertEquals(0, m.origin)
        assertEquals(-1, m.armedOrigin)
    }

    @Test
    fun `达门槛武装影子并直到追平静止才换装`() {
        val m = StreamingSplitMachine(minFreezeChars = min)
        // batch1: 门槛到达 → Arm
        val arm = m.onBatch(snapshot, released = 200, quiescent = true)
        assertTrue(arm is SplitAction.Arm)
        val armedAt = (arm as SplitAction.Arm).armedOrigin
        assertTrue(armedAt in 1..200)
        assertEquals(armedAt, m.armedOrigin)
        assertEquals(0, m.origin) // 未换装前原点不动

        // batch2: 影子未追平 → Feed（滚动与否同判）
        assertTrue(m.onBatch(snapshot, released = 260, quiescent = true, shadowLen = 10) is SplitAction.Feed)
        // batch3: 影子追平但滚动中 → 仍 Feed（p90 窗口零毕业成本）
        assertTrue(
            m.onBatch(snapshot, released = 260, quiescent = false, shadowLen = 260 - armedAt) is SplitAction.Feed
        )
        // batch4: 追平 + 静止 → Fire，原点推进至武装边界
        val fire = m.onBatch(snapshot, released = 260, quiescent = true, shadowLen = 260 - armedAt)
        assertTrue(fire is SplitAction.Fire)
        assertEquals(armedAt, m.origin)
        assertEquals(armedAt, (fire as SplitAction.Fire).plan.tailFrom)
        assertTrue(fire.plan.chunks.isNotEmpty())
        assertEquals(-1, m.armedOrigin)
    }

    @Test
    fun `换装只冻结到武装边界（后续增量留下轮）`() {
        val m = StreamingSplitMachine(minFreezeChars = min)
        val arm = m.onBatch(snapshot, released = 200) as SplitAction.Arm
        val armedAt = arm.armedOrigin
        // released 已远超武装边界，Fire 仍只冻到 armedAt
        val fire = m.onBatch(snapshot, released = snapshot.length, quiescent = true, shadowLen = snapshot.length - armedAt)
        assertEquals(armedAt, (fire as SplitAction.Fire).plan.tailFrom)
        // 冻结集 append-only：上一轮（空）的前缀性质 + 区间无缝到 armedAt
        assertEquals(armedAt, fire.plan.chunks.last().to)
    }

    @Test
    fun `连续毕业多轮（武装-换装循环）+ 冻结 append-only`() {
        val m = StreamingSplitMachine(minFreezeChars = min)
        var prevChunks = listOf<FrozenChunk>()
        var rel = 200
        repeat(3) {
            var act = m.onBatch(snapshot, released = rel, quiescent = true)
            if (act is SplitAction.None) return@repeat
            assertTrue(act is SplitAction.Arm)
            val armedAt = (act as SplitAction.Arm).armedOrigin
            rel += 150
            act = m.onBatch(snapshot, released = rel, quiescent = true, shadowLen = rel - armedAt)
            assertTrue(act is SplitAction.Fire)
            val fired = (act as SplitAction.Fire).plan
            assertEquals(fired.chunks.take(prevChunks.size), prevChunks)
            assertEquals(armedAt, fired.tailFrom)
            prevChunks = fired.chunks
            rel += 100
        }
        assertTrue(m.origin > 0)
    }

    @Test
    fun `非前缀重置回 Idle`() {
        val m = StreamingSplitMachine(minFreezeChars = min)
        m.onBatch(snapshot, released = 200)
        val act = m.onBatch("短串", released = 2, quiescent = true)
        assertTrue(act is SplitAction.Reset)
        assertEquals(0, m.origin)
        assertEquals(-1, m.armedOrigin)
        assertTrue(m.plan.chunks.isEmpty())
        // 重置后可重新武装
        val arm = m.onBatch(snapshot, released = 200, quiescent = true)
        assertTrue(arm is SplitAction.Arm)
    }

    @Test
    fun `手动 reset 完结与离树`() {
        val m = StreamingSplitMachine(minFreezeChars = min)
        m.onBatch(snapshot, released = 200)
        m.reset()
        assertEquals(0, m.origin)
        assertEquals(-1, m.armedOrigin)
    }
}
