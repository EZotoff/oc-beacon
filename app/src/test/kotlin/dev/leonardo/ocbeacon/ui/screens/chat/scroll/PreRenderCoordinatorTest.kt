package dev.leonardo.ocbeacon.ui.screens.chat.scroll

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #423 I3:视口租约计数单测——activeCount 的正确性是守卫/MSGEFFECT/PENDING/
 * 强滚锚底全部让位点的前提(租约泄漏=守卫永久哑火,重复释放=提前开战)。
 * try/finally 保证取消/异常路径必释放(LaunchedEffect 快速反向 toggle 即取消路径)。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PreRenderCoordinatorTest {

    @Test
    fun `lease held during episode and released after`() = runTest {
        assertFalse(PreRenderCoordinator.hasActiveTransactions)
        PreRenderCoordinator.withEpisode {
            assertTrue("episode 内租约必须持有", PreRenderCoordinator.hasActiveTransactions)
        }
        assertFalse("正常退出必须释放", PreRenderCoordinator.hasActiveTransactions)
    }

    @Test
    fun `nested episodes are counted`() = runTest {
        PreRenderCoordinator.withEpisode {
            PreRenderCoordinator.withEpisode {
                assertEquals(2, PreRenderCoordinator.activeCount)
            }
            assertEquals(1, PreRenderCoordinator.activeCount)
        }
        assertEquals(0, PreRenderCoordinator.activeCount)
    }

    @Test
    fun `lease released on exception`() = runTest {
        runCatching { PreRenderCoordinator.withEpisode { error("boom") } }
        assertFalse("异常路径 finally 必释放", PreRenderCoordinator.hasActiveTransactions)
    }

    @Test
    fun `lease released on cancellation`() = runTest {
        val job = launch { PreRenderCoordinator.withEpisode { awaitCancellation() } }
        advanceTimeBy(1)
        assertTrue("协程运行中租约持有", PreRenderCoordinator.hasActiveTransactions)
        job.cancel()
        advanceTimeBy(1)
        assertFalse("取消路径 finally 必释放(快速反向 toggle 依赖)", PreRenderCoordinator.hasActiveTransactions)
    }

    // ---------- FLUSH 相(批次二):单点排空语义 ----------

    @Test
    fun `flush tasks run in registration order and refusal propagates`() {
        val calls = mutableListOf<String>()
        val t1 = PreDrawFlushTask { calls.add("t1"); true }
        val t2 = PreDrawFlushTask { calls.add("t2"); false }
        val t3 = PreDrawFlushTask { calls.add("t3"); true }
        try {
            PreRenderCoordinator.registerFlushTask(t1)
            PreRenderCoordinator.registerFlushTask(t2)
            PreRenderCoordinator.registerFlushTask(t3)

            val allow = PreRenderCoordinator.runFlush()

            assertFalse("任一任务拒绘 → 本帧拒绘", allow)
            assertEquals("注册序执行,拒绘不短路后续任务(逐事务确定序)", listOf("t1", "t2", "t3"), calls)
        } finally {
            PreRenderCoordinator.unregisterFlushTask(t3)
            PreRenderCoordinator.unregisterFlushTask(t2)
            PreRenderCoordinator.unregisterFlushTask(t1)
        }
    }

    @Test
    fun `flush allows draw after refuser unregisters and when empty`() {
        val refuser = PreDrawFlushTask { false }
        PreRenderCoordinator.registerFlushTask(refuser)
        try {
            assertFalse(PreRenderCoordinator.runFlush())
        } finally {
            PreRenderCoordinator.unregisterFlushTask(refuser)
        }
        assertTrue("拒绘任务注销后放行", PreRenderCoordinator.runFlush())
        assertTrue("空任务表放行", PreRenderCoordinator.runFlush())
    }

    // ---------- #425 预热队列:串行授权语义(方案 A 队列原语) ----------

    /** 并发申请下任意时刻至多 1 个授权在途,且按到达序串行执行。 */
    @Test
    fun `prewarm grants are serialized at most one in flight`() = runTest {
        var inFlight = 0
        var maxInFlight = 0
        val order = mutableListOf<String>()
        val jobs = (1..3).map { i ->
            launch {
                PreRenderCoordinator.withPrewarmGrant {
                    inFlight++
                    maxInFlight = maxOf(maxInFlight, inFlight)
                    order.add("start" + i)
                    // 模拟 ε 组合占用的主线程窗
                    kotlinx.coroutines.delay(100)
                    order.add("end" + i)
                    inFlight--
                    "r" + i
                }
            }
        }
        jobs.forEach { it.join() }
        assertTrue("任意时刻至多 1 个预热在途(实测峰值=" + maxInFlight + ")", maxInFlight <= 1)
        assertEquals(
            "按到达序串行(无同帧双组合)",
            listOf("start1", "end1", "start2", "end2", "start3", "end3"),
            order,
        )
    }

    /** episode 活跃期(租约被持)队列冻结:授权门等待平息,平息后放行。 */
    @Test
    fun `prewarm grant waits while episode lease is held`() = runTest {
        val episodeJob = launch { PreRenderCoordinator.withEpisode { awaitCancellation() } }
        advanceTimeBy(1)
        assertTrue(PreRenderCoordinator.hasActiveTransactions)

        var executed = false
        val grantJob = launch { PreRenderCoordinator.withPrewarmGrant { executed = true; "ok" } }
        // 越过排队+多轮门轮询间隔:门必须仍关
        advanceTimeBy(PreRenderCoordinator.PREWARM_EPISODE_POLL_MS * 3)
        assertFalse("episode 进行中不得授权预热", executed)
        // 仍在有界等待轮数内(未放弃)
        advanceTimeBy(PreRenderCoordinator.PREWARM_EPISODE_POLL_MS * 2)
        assertFalse(executed)

        episodeJob.cancel()
        advanceTimeBy(PreRenderCoordinator.PREWARM_EPISODE_POLL_MS + PreRenderCoordinator.PREWARM_SLOT_GAP_MS + 100)
        assertTrue("episode 平息后授权放行", executed)
        grantJob.join()
    }

    /** episode 长期不平息(有界等待):超时放弃本窗,返回 null 且不执行 block。 */
    @Test
    fun `prewarm grant gives up after bounded episode wait`() = runTest {
        val episodeJob = launch { PreRenderCoordinator.withEpisode { awaitCancellation() } }
        advanceTimeBy(1)
        var executed = false
        val result = PreRenderCoordinator.withPrewarmGrant { executed = true; "ok" }
        assertFalse("超时放弃:block 不得执行", executed)
        assertNull("放弃语义=null 返回", result)
        episodeJob.cancel()
        advanceTimeBy(1)
    }

    /** 持约者取消/异常必释放授权——防队列死锁(对齐租约泄漏教训)。 */
    @Test
    fun `prewarm grant released on holder cancellation`() = runTest {
        val holder = launch {
            PreRenderCoordinator.withPrewarmGrant { awaitCancellation() }
        }
        // 持约者进入授权段(排队无人+门开+槽间隔)
        advanceTimeBy(PreRenderCoordinator.PREWARM_SLOT_GAP_MS + 100)
        var successorExecuted = false
        val successor = launch {
            PreRenderCoordinator.withPrewarmGrant { successorExecuted = true; "b" }
        }
        advanceTimeBy(PreRenderCoordinator.PREWARM_SLOT_GAP_MS + 100)
        // 持约者仍持有:后继者尚未执行
        assertFalse(successorExecuted)

        holder.cancel()
        advanceTimeBy(PreRenderCoordinator.PREWARM_SLOT_GAP_MS * 2 + 100)
        assertTrue("持约者取消后授权必须移交后继者", successorExecuted)
        successor.join()
    }

    /** 串行间隔:卡 A 完成到卡 B 开始 ≥ 槽间隔(防相邻预热同帧接续)。 */
    @Test
    fun `prewarm slots are separated by at least one slot gap`() = runTest {
        var aEndAt = -1L
        var bStartAt = -1L
        launch {
            PreRenderCoordinator.withPrewarmGrant {
                kotlinx.coroutines.delay(50)
                aEndAt = currentTime
            }
        }.join()
        launch {
            PreRenderCoordinator.withPrewarmGrant {
                bStartAt = currentTime
            }
        }.join()
        assertTrue("A 完成在 B 开始之前", aEndAt in 0..bStartAt)
        assertTrue(
            "串行槽间隔 ≥ PREWARM_SLOT_GAP_MS(实测 " + (bStartAt - aEndAt) + "ms)",
            bStartAt - aEndAt >= PreRenderCoordinator.PREWARM_SLOT_GAP_MS,
        )
    }
}
