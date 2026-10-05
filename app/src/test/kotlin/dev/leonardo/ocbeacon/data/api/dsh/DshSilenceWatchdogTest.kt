package dev.leonardo.ocbeacon.data.api.dsh

import org.junit.Test

/**
 * #441 方案A 子项一（2026-09-28）：DSH 通道应用层静默哨兵的纯逻辑测试。
 *
 * 结构性缺口（issue441-research 定罪）：V1/V2 SSE 有 40s 心跳读超时（半开
 * TCP 挂死防护），DSH 线面只有传输层 ping——WS 假活（TCP 在、帧流死）时
 * app 永远等下去，输出期间渲染静默的唯一恢复方式是重启。哨兵语义：
 * **期望活跃（streaming）∧ 帧静默超阈值 → 判死，强制走既有退避重连**。
 * 虚拟时钟（nowMs 注入）使全部时序可测。
 */
class DshSilenceWatchdogTest {

    private class Clock(var now: Long = 0L)
    private fun watchdog(clock: Clock, timeoutMs: Long = 110_000) =
        DshSilenceWatchdog(timeoutMs = timeoutMs, nowMs = { clock.now })

    @Test
    fun `prompt admission arms expectation and silence trips`() {
        // #441 深究批次（2026-09-30）：「空闲期 WS 假活 → 用户发言」形态——
        // activityFlow 门无源（无事件永不亮），onRequestSent（HTTP 受理回执）
        // 是唯一期望源：播种后静默超阈值必须判死。
        val c = Clock()
        val w = watchdog(c)
        // 无任何 streaming 期望（空闲），哨兵门关
        c.now = 500_000
        check(!w.shouldForceReconnect())
        // prompt 受理回执（t=500s）→ 播种
        w.onRequestSent()
        c.now = 500_000 + 110_000
        check(!w.shouldForceReconnect()) // 恰好阈值：未超
        c.now = 500_000 + 110_001
        check(w.shouldForceReconnect()) // 帧静默超阈值：判死
    }

    @Test
    fun `prompt admission then frames arrive no false trip`() {
        // 受理后帧流正常到达（turn 活动）→ onFrame 刷新基准不误杀；
        // turn 完结 onStreamingChanged(false) 关门后静默合法。
        val c = Clock()
        val w = watchdog(c)
        w.onRequestSent() // t=0 受理
        c.now = 30_000
        w.onFrame() // t=30s 首帧（服务器活动帧）
        c.now = 30_000 + 110_000
        check(!w.shouldForceReconnect()) // 距末帧恰好阈值：未超
        c.now = 30_000 + 200_000
        w.onStreamingChanged(false) // turn 完结，activityFlow 归零 → 关门
        c.now = 30_000 + 500_000
        check(!w.shouldForceReconnect()) // 关门后长静默合法（空闲）
    }

    @Test
    fun `silent while streaming expected trips after timeout`() {
        val c = Clock()
        val w = watchdog(c)
        w.onStreamingChanged(true) // t=0 开始期望活跃
        c.now = 110_000
        check(!w.shouldForceReconnect()) // 恰好阈值：未超
        c.now = 110_001
        check(w.shouldForceReconnect()) // 超一毫秒：判死
    }

    @Test
    fun `arriving frames keep the connection alive`() {
        val c = Clock()
        val w = watchdog(c)
        w.onStreamingChanged(true)
        repeat(10) { i ->
            c.now = i * 60_000L
            w.onFrame() // 每 60s 一帧（健康 DSH 流式远密于此）
        }
        c.now = 9 * 60_000L + 90_000 // 最后帧 t=540s，其后 90s < 110s 阈值
        check(!w.shouldForceReconnect())
    }

    @Test
    fun `silence without streaming expectation is legal`() {
        val c = Clock()
        val w = watchdog(c)
        // 未进入 streaming：follow 挂着但会话 idle = 静默合法（防误杀重连风暴）
        c.now = 10 * 60_000L
        check(!w.shouldForceReconnect())
    }

    @Test
    fun `streaming end clears expectation and stops tripping`() {
        val c = Clock()
        val w = watchdog(c)
        w.onStreamingChanged(true)
        c.now = 50_000
        w.onStreamingChanged(false) // turn 完结：期望撤销
        c.now = 500_000
        check(!w.shouldForceReconnect())
    }

    @Test
    fun `reset after forced reconnect restarts the window`() {
        val c = Clock()
        val w = watchdog(c)
        w.onStreamingChanged(true)
        c.now = 200_000
        check(w.shouldForceReconnect())
        w.reset() // 强制重连后重开计窗
        check(!w.shouldForceReconnect())
        c.now = 200_000 + 110_001
        check(w.shouldForceReconnect()) // 重连后仍静默：再次判死
    }

    @Test
    fun `request sent then silence trips but idle without requests never trips`() {
        val c = Clock()
        val w = watchdog(c)
        // 空闲（无外发请求）：永不判死（防 110s 周期重连循环——真机实证修正）
        c.now = 10 * 60_000L
        check(!w.shouldForceReconnect())
        // 外发请求后期望响应帧：超阈判死（#441 主场景）
        w.onRequestSent()
        c.now = 10 * 60_000L + 110_001
        check(w.shouldForceReconnect())
        // 请求后帧到达：撤销静默窗
        w.reset()
        w.onRequestSent()
        c.now = 10 * 60_000L + 50_000
        w.onFrame()
        c.now = 10 * 60_000L + 50_000 + 90_000 // 帧后 90s < 110s
        check(!w.shouldForceReconnect())
    }

    @Test
    fun `frame before streaming starts seeds the clock`() {
        val c = Clock()
        val w = watchdog(c)
        c.now = 1_000
        w.onFrame() // 非期望期到达的帧也刷新 lastFrameAt
        w.onStreamingChanged(true) // t=1000 起期望活跃，基准=1000（非 0）
        c.now = 1_000 + 110_001
        check(w.shouldForceReconnect())
    }
}

