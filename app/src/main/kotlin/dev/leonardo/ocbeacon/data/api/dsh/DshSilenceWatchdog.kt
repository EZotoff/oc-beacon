package dev.leonardo.ocbeacon.data.api.dsh

/**
 * #441 方案A 子项一（2026-09-28）：DSH 通道应用层静默哨兵。
 *
 * 结构性缺口（issue441-research.md §3.5 定罪）：V1/V2 SSE 线面有 40s 心跳读
 * 超时（SseClient.readRawLineBytesWithTimeout——半开 TCP 挂死 #108 防护）；
 * **DSH 线面（WS /api/remote.mux）只有传输层 ping 25s**——TCP 活着但帧流死亡
 * （会话级/路由级死亡）时 app 无限等待，输出期间渲染静默，唯一恢复=重启 app。
 *
 * 哨兵语义：**期望活跃（streaming）∧ 帧静默超阈值 → 判死**，由接线方强制
 * 走既有退避重连（muxLoop 世代重建 + followed.clear + 兜底重订阅）。
 * - 非期望期静默合法（follow 挂着但会话 idle——防误杀重连风暴）；
 * - 帧到达刷新基准（任何帧，不限类型）；
 * - 虚拟时钟注入（nowMs），全部时序可测（DshSilenceWatchdogTest）。
 *
 * 阈值 110s：报告建议 90-120s 折中；显著大于 DSH 健康 streaming 的帧间隔
 * （48ms 批处理上限）且小于「用户可感知卡死即离开」的典型窗口。
 */
internal class DshSilenceWatchdog(
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private var lastFrameAtMs: Long = -1L
    private var expectActive: Boolean = false

    /** 任何帧到达（不限类型——帧流活着即通道活着）。 */
    fun onFrame() {
        lastFrameAtMs = nowMs()
    }

    /** streaming 期望变更：true=期望帧流（turn 进行中）。激活时若无帧基准以当前时刻播种。 */
    fun onStreamingChanged(active: Boolean) {
        expectActive = active
        if (active && lastFrameAtMs < 0) lastFrameAtMs = nowMs()
    }

    /**
     * 外发请求（follow open / respond / 任何 ws.send）：期望响应帧——播种基准
     * 并开启期望。**静默判死的唯一常态触发源**（#441 主场景=prompt accepted 后
     * 渲染静默=请求挂起无响应帧）。空闲（无外发请求）不判死——DSH 服务器空闲
     * 会话无周期帧，常开期望会形成 110s 周期重连循环（2026-09-28 真机实证）。
     */
    fun onRequestSent() {
        expectActive = true
        lastFrameAtMs = nowMs()
    }

    /** 判死：期望活跃 ∧ 静默超阈值。 */
    fun shouldForceReconnect(): Boolean =
        expectActive && lastFrameAtMs >= 0 && nowMs() - lastFrameAtMs > timeoutMs

    /** 强制重连后重开计窗（期望保持，基准=当前时刻）。 */
    fun reset() {
        lastFrameAtMs = nowMs()
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS: Long = 110_000L
    }
}
