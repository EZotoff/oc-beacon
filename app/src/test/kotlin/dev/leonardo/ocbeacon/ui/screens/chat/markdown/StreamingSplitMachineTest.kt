package dev.leonardo.ocbeacon.ui.screens.chat.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #442 R2 分片唤醒（批次 A2 内核）——武装/触发状态机测试（spec §2 语义钉死）。
 * #503 根修后语义：回退宽限（瞬时回退不 Reset）+ Fire 事务性（confirmFire 落账）。
 */
class StreamingSplitMachineTest {

    private fun para(tag: String) = "# $tag\n内容行。\n\n"
    private val snapshot = (1..30).joinToString("") { para("P$it") }
    private val min = 100

    /** 测试壁钟：每 tick 前进 50ms（宽限窗 300ms = 6 tick）。 */
    private var now = 0L
    private fun tick(): Long { now += 50; return now }

    @Test
    fun `未达门槛无动作`() {
        val m = StreamingSplitMachine(minFreezeChars = min)
        val act = m.onBatch(snapshot, released = 50, quiescent = true, nowMs = tick())
        assertTrue(act is SplitAction.None)
        assertEquals(0, m.origin)
        assertEquals(-1, m.armedOrigin)
    }

    @Test
    fun `达门槛武装影子并直到追平静止才换装`() {
        val m = StreamingSplitMachine(minFreezeChars = min)
        // batch1: 门槛到达 → Arm
        val arm = m.onBatch(snapshot, released = 200, quiescent = true, nowMs = tick())
        assertTrue(arm is SplitAction.Arm)
        val armedAt = (arm as SplitAction.Arm).armedOrigin
        assertTrue(armedAt in 1..200)
        assertEquals(armedAt, m.armedOrigin)
        assertEquals(0, m.origin) // 未换装前原点不动

        // batch2: 影子未追平 → Feed（滚动与否同判）
        assertTrue(
            m.onBatch(snapshot, released = 260, quiescent = true, shadowLen = 10, nowMs = tick()) is SplitAction.Feed
        )
        // batch3: 影子追平但滚动中 → 仍 Feed（p90 窗口零毕业成本）
        assertTrue(
            m.onBatch(snapshot, released = 260, quiescent = false, shadowLen = 260 - armedAt, nowMs = tick()) is SplitAction.Feed
        )
        // batch4: 追平 + 静止 → Fire；#503 R2：落账前 origin 不动、armed 保持
        val fire = m.onBatch(snapshot, released = 260, quiescent = true, shadowLen = 260 - armedAt, nowMs = tick())
        assertTrue(fire is SplitAction.Fire)
        assertEquals(armedAt, (fire as SplitAction.Fire).plan.tailFrom)
        assertTrue(fire.plan.chunks.isNotEmpty())
        assertEquals(0, m.origin) // 未 confirm：账本未推进
        // confirmFire：plan 推进 + 解除武装
        m.confirmFire()
        assertEquals(armedAt, m.origin)
        assertEquals(-1, m.armedOrigin)
    }

    @Test
    fun `换装只冻结到武装边界（后续增量留下轮）`() {
        val m = StreamingSplitMachine(minFreezeChars = min)
        val arm = m.onBatch(snapshot, released = 200, nowMs = tick()) as SplitAction.Arm
        val armedAt = arm.armedOrigin
        // released 已远超武装边界，Fire 仍只冻到 armedAt
        val fire = m.onBatch(snapshot, released = snapshot.length, quiescent = true, shadowLen = snapshot.length - armedAt, nowMs = tick())
        assertEquals(armedAt, (fire as SplitAction.Fire).plan.tailFrom)
        // 冻结集 append-only：上一轮（空）的前缀性质 + 区间无缝到 armedAt
        assertEquals(armedAt, fire.plan.chunks.last().to)
        m.confirmFire()
        assertEquals(armedAt, m.origin)
    }

    @Test
    fun `连续毕业多轮（武装-换装循环）+ 冻结 append-only`() {
        val m = StreamingSplitMachine(minFreezeChars = min)
        var prevChunks = listOf<FrozenChunk>()
        var rel = 200
        repeat(3) {
            var act = m.onBatch(snapshot, released = rel, quiescent = true, nowMs = tick())
            if (act is SplitAction.None) return@repeat
            assertTrue(act is SplitAction.Arm)
            val armedAt = (act as SplitAction.Arm).armedOrigin
            rel += 150
            act = m.onBatch(snapshot, released = rel, quiescent = true, shadowLen = rel - armedAt, nowMs = tick())
            assertTrue(act is SplitAction.Fire)
            val fired = (act as SplitAction.Fire).plan
            m.confirmFire() // #503 R2：每轮发布成功落账，下一轮 Arm 才有新边界
            assertEquals(fired.chunks.take(prevChunks.size), prevChunks)
            assertEquals(armedAt, fired.tailFrom)
            prevChunks = fired.chunks
            rel += 100
        }
        assertTrue(m.origin > 0)
    }

    @Test
    fun `瞬时回退宽限内不重置 - #503 R1`() {
        // 真机定罪：冷启重组窗的瞬时陈旧快照（总线让位回退）单批即 Reset →
        // 销毁已发布集 + origin 归零 → 重播→再毕业→再 fire 回卷（9 fire/9s）。
        // 宽限语义：回退首见 None；恢复后照常（origin 保留、无 Reset）。
        val m = StreamingSplitMachine(minFreezeChars = min)
        val arm = m.onBatch(snapshot, released = 200, nowMs = tick()) as SplitAction.Arm
        m.onBatch(snapshot, released = 260, quiescent = true, shadowLen = 260 - arm.armedOrigin, nowMs = tick())
        // 瞬时回退一批（宽限首见）→ None，不 Reset
        assertTrue(m.onBatch("短串", released = 2, quiescent = true, nowMs = tick()) is SplitAction.None)
        // 恢复全量快照 → 追平即 Fire（状态无损）
        val fire = m.onBatch(snapshot, released = 260, quiescent = true, shadowLen = 260 - arm.armedOrigin, nowMs = tick())
        assertTrue(fire is SplitAction.Fire)
    }

    @Test
    fun `回退持续超宽限才重置 - #503 R1`() {
        val m = StreamingSplitMachine(minFreezeChars = min)
        m.onBatch(snapshot, released = 200, nowMs = tick())
        // 持续回退（每 tick 50ms，7 tick=350ms ≥ 300ms 宽限）→ Reset
        var act: SplitAction = SplitAction.None
        repeat(7) {
            act = m.onBatch("短串", released = 2, quiescent = true, nowMs = tick())
        }
        assertTrue(act is SplitAction.Reset)
        assertEquals(0, m.origin)
        assertEquals(-1, m.armedOrigin)
        assertTrue(m.plan.chunks.isEmpty())
        // 重置后可重新武装
        val arm = m.onBatch(snapshot, released = 200, quiescent = true, nowMs = tick())
        assertTrue(arm is SplitAction.Arm)
    }

    @Test
    fun `Fire 未确认不推进账本且重试同计划 - #503 R2`() {
        // 回收竞态：fire 对未注册 no-op → 接线层不 confirmFire → 账本原地，
        // 下批追平重发同一计划（不换内容）；确认后推进。
        val m = StreamingSplitMachine(minFreezeChars = min)
        val arm = m.onBatch(snapshot, released = 200, nowMs = tick()) as SplitAction.Arm
        val armedAt = arm.armedOrigin
        val fire1 = m.onBatch(snapshot, released = 260, quiescent = true, shadowLen = 260 - armedAt, nowMs = tick())
        assertTrue(fire1 is SplitAction.Fire)
        // 未确认：下一批同条件 → 重试同一计划
        val fire2 = m.onBatch(snapshot, released = 260, quiescent = true, shadowLen = 260 - armedAt, nowMs = tick())
        assertTrue(fire2 is SplitAction.Fire)
        assertEquals((fire1 as SplitAction.Fire).plan, (fire2 as SplitAction.Fire).plan)
        assertEquals(0, m.origin)
        // 确认 → 推进
        m.confirmFire()
        assertEquals(armedAt, m.origin)
        assertEquals(-1, m.armedOrigin)
    }

    @Test
    fun `手动 reset 完结与离树`() {
        val m = StreamingSplitMachine(minFreezeChars = min)
        m.onBatch(snapshot, released = 200, nowMs = tick())
        m.reset()
        assertEquals(0, m.origin)
        assertEquals(-1, m.armedOrigin)
    }
}
