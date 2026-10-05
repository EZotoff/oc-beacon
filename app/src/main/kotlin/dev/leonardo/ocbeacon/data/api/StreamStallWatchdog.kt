package dev.leonardo.ocbeacon.data.api

import dev.leonardo.ocbeacon.logging.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/**
 * SSE 流相 stall 看门狗（#441/#467 根修，2026-09-30）。
 *
 * 背景（journal 2026-09-30-467-v1retained-subtree §2→§4 演进）：#108 行级 40s
 * 超时（withTimeoutOrNull）对纯零字节停顿有效（SIGSTOP 实测精确触发）；但 reverse
 * 僵尸拆链竞态曾一次 4min04s 零检测挂死（death-snapshot lastEventAgoMs=240737，
 * 4 次复现尝试未再现，机制未定）；Ktor socketTimeout 按设计只封顶响应头等待。
 * 本看门狗以独立字节级计时兜底该盲区。
 *
 * 机制：**不依赖协程取消语义**。stall 时由 [onStall] 强杀底层 HTTP call
 * （\`response.cancel()\` 关 socket → 阻塞原生读立即以 IO 异常解除），
 * 读循环退出走既有异常路径 → 退避梯子 → 重连 + SSE_PRIORITY backfill。
 *
 * 存活契约：v1.18.32 /global/event 心跳 ~6s/次（wire 实证含流式期间）；
 * 默认阈值 [SSE_SOCKET_TIMEOUT_MS]（45s = 7 拍无声判死）。
 */
internal class StreamStallWatchdog(
    private val stallThresholdMs: Long,
    private val checkIntervalMs: Long = 5_000L,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val onStall: suspend () -> Unit,
) {
    private val lastProgressAtMs = AtomicLong(0L)

    /** 任何行/帧/字节到达即标记存活（调用方在读循环里逐行打点）。 */
    fun markProgress() {
        lastProgressAtMs.set(nowMs())
    }

    /**
     * 在 [scope] 启动巡检协程；返回 Job 供调用方在流正常结束时取消。
     * 触发一次 [onStall] 后协程即完成（单发语义——重连由上层梯子负责）。
     * [onStall] 内部异常被吞并记录（巡检协程不得把异常泄给宿主作用域）。
     */
    fun startIn(scope: CoroutineScope): Job {
        lastProgressAtMs.set(nowMs())
        return scope.launch {
            while (true) {
                delay(checkIntervalMs)
                val idleMs = nowMs() - lastProgressAtMs.get()
                if (idleMs > stallThresholdMs) {
                    runCatching { onStall() }
                        .onFailure { AppLogger.w(TAG, "stall watchdog onStall failed: " + it.message) }
                    return@launch
                }
            }
        }
    }

    private companion object {
        const val TAG = "StreamStallWatchdog"
    }
}
