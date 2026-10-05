package dev.leonardo.ocbeacon.data.api

import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #441/#467 流相 stall 看门狗单测（虚拟时钟）。
 */
class StreamStallWatchdogTest {

    @Test
    fun noProgressFiresOnceAfterThreshold() = runTest {
        var fired = 0
        val wd = StreamStallWatchdog(
            stallThresholdMs = 45_000,
            checkIntervalMs = 5_000,
            nowMs = { testScheduler.currentTime },
            onStall = { fired++ },
        )
        val job = wd.startIn(backgroundScope)
        advanceTimeBy(50_000)
        runCurrent()
        assertEquals("阈值+巡检间隔后恰好触发一次", 1, fired)
        assertTrue("单发后协程完成", job.isCompleted)
    }

    @Test
    fun continuousProgressNeverFires() = runTest {
        var fired = 0
        val wd = StreamStallWatchdog(
            stallThresholdMs = 45_000,
            checkIntervalMs = 5_000,
            nowMs = { testScheduler.currentTime },
            onStall = { fired++ },
        )
        wd.startIn(backgroundScope)
        repeat(20) {
            advanceTimeBy(10_000)
            runCurrent()
            wd.markProgress()
        }
        assertEquals("静默从未超阈值", 0, fired)
    }

    @Test
    fun progressThenSilenceFiresOnlyPastThreshold() = runTest {
        var fired = 0
        val wd = StreamStallWatchdog(
            stallThresholdMs = 45_000,
            checkIntervalMs = 5_000,
            nowMs = { testScheduler.currentTime },
            onStall = { fired++ },
        )
        wd.startIn(backgroundScope)
        advanceTimeBy(30_000); runCurrent()
        wd.markProgress()
        advanceTimeBy(40_000); runCurrent()
        assertEquals("静默 40s < 45s 阈值不触发", 0, fired)
        advanceTimeBy(10_000); runCurrent()
        assertEquals("静默 50s > 阈值触发", 1, fired)
    }

    @Test
    fun onStallExceptionSwallowedAndJobCompletes() = runTest {
        val wd = StreamStallWatchdog(
            stallThresholdMs = 10_000,
            checkIntervalMs = 2_000,
            nowMs = { testScheduler.currentTime },
            onStall = { throw IllegalStateException("boom") },
        )
        val job = wd.startIn(backgroundScope)
        advanceTimeBy(20_000)
        runCurrent()
        assertTrue("异常不外溢，协程正常完成", job.isCompleted && !job.isCancelled)
    }
}
