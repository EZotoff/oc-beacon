package dev.leonardo.ocbeacon.ui.screens.chat.markdown

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.mikepenz.markdown.model.StreamingMarkdownState
import com.mikepenz.markdown.model.rememberStreamingMarkdownState
import dev.leonardo.ocbeacon.BuildConfig
import dev.leonardo.ocbeacon.logging.AppLogger
import kotlinx.coroutines.delay

/**
 * #265 流式 Markdown 增量解析试点开关。
 *
 * spec：docs/specs/2026-08-30-streaming-markdown-state-pilot-design.md §5——
 * dev flavor 默认开（先行 A/B），beta/stable 关闭；回退 = 对应 flavor 的
 * buildConfigField 置 false 一行，或 revert 接线 commit。
 *
 * #437 [stableReveal]：两级安全放行闸（spec
 * docs/specs/2026-09-25-437-streaming-md-stable-reveal-design.md）——
 * 前缀差分与 state.append 之间的 SafePrefixGate，只把定案内容交给库，
 * 从源头消除 L4 不稳定尾回溯重释义（跳变主源）。回退 = 置 false 一行。
 */
object StreamingMarkdownPilot {
    val enabled: Boolean = BuildConfig.STREAMING_MD_PILOT
    val stableReveal: Boolean = BuildConfig.STABLE_REVEAL_PILOT
}

/**
 * pilot 揭露状态（#437）：库状态 + 扣留尾部。
 *
 * [heldTail] = 快照中未放行部分（差终止符尾部）——去路恒两条：
 * 毕业（闭合→放行）或完结 EOF 全量 flush（完结切 preParsedState/async
 * 分支渲染整串，一字不丢）。阶段 B 降亮区（锁高+呼吸光标）消费此值。
 */
internal class PilotStreamingState(
    val state: StreamingMarkdownState,
    val heldTail: State<String>,
)

/**
 * 前缀差分 append 包装（spec §1）+ #437 安全放行闸接线。
 *
 * Part.Text.text 仍以整串快照到达（48ms flush 产物），在此与库状态内部的
 * StringBuilder 做前缀差分，仅把 delta 交给 append()——解析下沉在
 * org.jetbrains:markdown 0.7.9 的 StreamingMarkdownFile，只重解析不稳定尾部，
 * 稳定块 ASTNode 实例跨 append 复用。
 *
 * #437：stableReveal 开启时，差分出的全量 delta 先经 SafePrefixGate——
 * 只有定案前缀（空行毕业的闭合构造 + 纯文字安全后缀）进入 append；
 * 扣留尾部经 [PilotStreamingState.heldTail] 暴露给降亮区。放行流单调
 * 不回退（gate 不变量），与 #435 高度引擎「锚即意图」配对天然兼容。
 *
 * - 非前缀（重生成/编辑）→ prev 置空 + released 清零 + resetKey++ 经 key()
 *   整体重建状态实例，新实例首跑整串 append（无残留旧内容）。
 * - append 在组合协程（主线程）：与渲染同线程，StringBuilder 无跨线程竞态
 *   （库官方姿势同此；尾部小解析由 48ms flush 节奏摊平）。
 * - delta 未经 normalizeForRender（冲突①裁决）：流中放弃归一化，完结时由
 *   preParsedState 分支的既有归一化+分片路径接管——完结切换即 EOF 全量
 *   flush（扣留内容一字不丢），切换高度差由阶段 C 处理。
 */
/**
 * R3 滚动静止单信号源（#437 二十五世轮根修，架构审查 C1）：「滚动期静止」语义
 * 单一真相源。写点唯一（streamingGrowFlushTask 每帧驱动），四处消费者只读：
 * pilot append 暂缓 / ChatScreen 快照冻结（JankHoldGate）/ ledger rebaseAll /
 * 帽持帽。快照态（mutableStateOf）——消费侧读它即订阅失效。
 */
internal object ScrollQuiescence {
    /** 快照态：true=静止（可安全施加流式增量）；false=滚动/惯性中（一切让位）。 */
    var isQuiescent: Boolean by androidx.compose.runtime.mutableStateOf(true)
        private set

    /** 唯一写口（flush task 每帧以 isScrollInProgress 驱动）。 */
    fun onScrollStateChanged(scrolling: Boolean) {
        isQuiescent = !scrolling
    }
}

/** 迁移期兼容缝（R3）：旧消费者的 holding 读——委托单点信号，行为恒一致。 */
internal object StreamingScrollHold {
    var holding: Boolean
        get() = !ScrollQuiescence.isQuiescent
        set(_) { /* 写点已统一至 ScrollQuiescence.onScrollStateChanged；保留签名仅为源兼容 */ }
}

/**
 * [#437 二十四世轮终修] 滚动期 UI 快照冻结——默认启用。
 *
 * 根因：流式期间 messageState/messages 每 flush 间隔新实例 → 经传参旁路触发
 * ChatMessageList 整体重组（三千行函数体顶层重跑）+ chatEntries 全链重算 +
 * LazyColumn 全可见 item 重组，组合成本落在滚动帧。ScrollHold 挡 pilot append
 * 之外，本冻结把同一「滚动期静止」语义补全到快照层（rawMessages + messageState
 * 一并冻结，settle 后首个新快照原子追平）。
 *
 * 关闭（回退通道）：adb shell setprop debug.ocbeacon.jankhold 0 后重启进程。
 */
/**
 * #438① 大放行限速参数（2026-09-27）：突发/首跑铺开期单批放行 ≥[BIG_RELEASE_CH]
 * 视为大放行，两次大放行壁钟间隔 ≥[BIG_RELEASE_MIN_INTERVAL_MS]——把中继缓冲突发
 * （实测 22s TTFB + 900 delta/12s）下的 catch-up 观感从「单帧砸出」变为
 * 「快速但分块出现」。正常流式批次（p50=9ch、p90=41ch）远低于阈值，直通。
 */
internal const val BIG_RELEASE_CH = 200
internal const val BIG_RELEASE_MIN_INTERVAL_MS = 200L

/**
 * #461(2026-09-29):流式 pilot 准入契约(纯函数,单测锚)——pilot 仅服务真流式。
 *
 * 静态文本(历史/完结)一律 [asyncParse]=true 走 #428 分层解析:
 * StreamingMarkdownState 初始空、靠 LaunchedEffect 逐帧 append 填充,静态文本
 * 误入后在 CardExpandReveal ε/展开窗内与 settle 竞态 → H=0 僵尸态(真机三方
 * 定罪:settle H=0×3 / dump 无内容节点 / 像素 8dp 单档)。
 */
internal fun streamingPilotEligible(
    hasOverrideState: Boolean,
    asyncParse: Boolean,
    isUser: Boolean,
): Boolean = !hasOverrideState && !asyncParse && !isUser

/**
 * #472(2026-09-30)完结换装无缝判定(纯函数,单测锚):pilot 曾渲染(流式输出过
 * 内容)且 async 终态未就绪(State.Loading)时,完结帧保持 pilot 终帧渲染。
 *
 * 根因(真机定罪):完结(asyncParse 翻转)令 pilot 整树 dispose,>2048 字符
 * 正文切 [rememberAsyncMarkdownState] 首帧 Loading≈0 高、Default 线程解析
 * 完成后 Success 弹回全高——RESIZE 1105→865→1580(41ms 两连跳,用户主诉
 * 「轮次刚完成的一瞬高度变化」)。保持 pilot 终帧+async 并行预热,Success
 * 后无缝切换;残余归一化差(流中原文 vs 完结变换)由高度引擎帽配对吸收。
 */
internal fun pilotTerminalHold(
    pilotEverRendered: Boolean,
    asyncTerminalPending: Boolean,
): Boolean = pilotEverRendered && asyncTerminalPending

/** #472 非前缀宽限窗:数据层摆动(reconciler 竞态/完结 sync 重组)在此窗内冻结保树。 */
internal const val NON_PREFIX_GRACE_MS = 300L

/**
 * #472 验收轮回归根修(2026-09-28 真机定罪):非前缀事件是否已过宽限窗
 * (=真重生成,应重建)。窗内返回 false = 冻结保树保进度——旧串回来无缝
 * 续播;未武装(-1)恒 false。旧实现单发非前缀立即静默重建(resetKey++)→
 * 完结 part 重组时内容清空+限速回灌 = 「闪烁清空再恢复」主诉。
 */
internal fun nonPrefixRebuildDue(
    nonPrefixSinceMs: Long,
    nowMs: Long,
    graceMs: Long = NON_PREFIX_GRACE_MS,
): Boolean = nonPrefixSinceMs >= 0 && nowMs - nonPrefixSinceMs >= graceMs

internal object JankHoldGate {
    val enabled: Boolean by lazy {
        try {
            @Suppress("PrivateApi")
            val sp = Class.forName("android.os.SystemProperties")
            "0" != sp.getMethod("get", String::class.java).invoke(null, "debug.ocbeacon.jankhold")
        } catch (_: Throwable) {
            true
        }
    }
}

@Composable
internal fun rememberPilotStreamingMarkdownState(markdown: String, freeze: Boolean = false): PilotStreamingState {
    var resetKey by remember { mutableIntStateOf(0) }
    var prev by remember { mutableStateOf<String?>(null) }
    // #472:非前缀武装时刻——宽限窗内冻结,超窗才重建
    var nonPrefixSinceMs by remember { mutableLongStateOf(-1L) }
    // gate 放行长度（相对快照坐标）；非前缀重建时清零
    var released by remember { mutableIntStateOf(0) }
    val state = key(resetKey) { rememberStreamingMarkdownState() }
    val held = remember { mutableStateOf("") }
    // #437 §4：非前缀风暴探测（重建限频——冻结放行，旧串回来即恢复）
    val flap = remember { FlapDetector(now = { android.os.SystemClock.elapsedRealtime() }) }
    var lastStormCount by remember { mutableIntStateOf(0) }
    // #438①：上次大放行（≥BIG_RELEASE_CH）壁钟——大放行间隔限速（与到达解耦）
    var lastBigReleaseAt by remember { mutableLongStateOf(0L) }
    val gate = StreamingMarkdownPilot.stableReveal
    LaunchedEffect(markdown, state, StreamingScrollHold.holding) {
        val p = prev
        // #472 完结桥接期冻结:pilot 终帧即终点——async 在途的新快照(完结
        // sync/part 重组的非前缀串)一律不进 pilot,换装交给终态路径
        if (freeze) return@LaunchedEffect
        // 滚动/惯性中：暂缓增长增量（prev 不动，settle 后整段一次追平=一次重排版）
        if (StreamingScrollHold.holding && p != null && markdown.length > p.length) {
            return@LaunchedEffect
        }
        if (p == null || markdown.startsWith(p)) nonPrefixSinceMs = -1L
        when {
            // 首跑（含重建后的新实例）：整串作为初始增量（gate 后定案前缀）
            p == null -> {
                if (markdown.isNotEmpty()) {
                    if (gate) {
                        // 2026-09-27 首跑多帧铺开（真机取证：多消息 turn 的后续段
                        // 全量到达无 delta 流，首跑单帧巨量 append 1136-2087ch——
                        // 单帧 GC/解析压力集中且打穿帽揭示量子化节奏。改为逐帧铺开
                        // + #438① 大放行壁钟限速（与增量分支同语义），视觉节奏由帽
                        // （≤800px 首亮+1600px/500ms 步进）+限速共同接管。
                        var rel = 0
                        while (rel < markdown.length) {
                            val d = SafePrefixGate.releaseDelta(markdown, rel, BIG_RELEASE_CH)
                            if (d.newReleased <= rel) break // gate 拒绝（扣留中）——后续增量/EOF 接管
                            if (d.newReleased - rel >= BIG_RELEASE_CH) {
                                val wait = lastBigReleaseAt + BIG_RELEASE_MIN_INTERVAL_MS -
                                    android.os.SystemClock.elapsedRealtime()
                                if (wait > 0) delay(wait)
                                lastBigReleaseAt = android.os.SystemClock.elapsedRealtime()
                            }
                            if (d.delta.isNotEmpty()) appendAndTrace(state, d.delta)
                            rel = d.newReleased
                            if (rel < markdown.length) withFrameNanos { }
                        }
                        released = rel
                        logGate(markdown, 0, released)
                    } else {
                        appendAndTrace(state, markdown)
                        released = markdown.length
                    }
                }
                prev = markdown
            }
            // 非前缀（重生成/编辑）：下轮新实例走整串重建；
            // #437 §4 数据层摆动（reconciler vs live 竞态）会高频触发此分支——
            // 风暴抑制：冻结放行与重建（prev 保持旧值，旧串回来无缝恢复）
            !markdown.startsWith(p) -> {
                // #472 宽限冻结:瞬时摆动(reconciler 竞态/完结 sync 重组)保树
                // 保进度,旧串回来无缝续播;超窗仍非前缀才是真重生成
                val nowMs = android.os.SystemClock.elapsedRealtime()
                if (nonPrefixSinceMs < 0L) nonPrefixSinceMs = nowMs
                if (!nonPrefixRebuildDue(nonPrefixSinceMs, nowMs)) {
                    return@LaunchedEffect
                }
                nonPrefixSinceMs = -1L
                if (flap.onNonPrefix()) {
                    if (flap.stormCount != lastStormCount) {
                        lastStormCount = flap.stormCount
                        AppLogger.w("MDPilot", "flap suppress #" + flap.stormCount +
                            " — nonPrefix storm, rebuild frozen")
                    }
                    held.value = ""
                } else {
                    prev = null
                    released = 0
                    held.value = ""
                    resetKey++
                }
            }
            markdown.length > p.length -> {
                if (gate) {
                    // #438①（2026-09-27 壁钟限速）：catch-up/突发到达期 gate 按
                    // 400ch/48ms 释放过快（R9 真机实证 442ms 聚 7 批=单 note
                    // d=6236px，中继缓冲突发下观感即「整块一次性出」）。大放行
                    // （≥[BIG_RELEASE_CH]）间隔下限 [BIG_RELEASE_MIN_INTERVAL_MS]——
                    // 与到达解耦、只约束大批；正常流式小批（<200ch）直通不受影响。
                    val from = released
                    while (released < markdown.length) {
                        // #438①：每批喂 [BIG_RELEASE_CH]（含空行毕业段——原不受
                        // 批预算约束的漏洞）；批 ≥ 阈值即触发壁钟间隔
                        val d = SafePrefixGate.releaseDelta(markdown, released, BIG_RELEASE_CH)
                        if (d.newReleased <= released) break
                        if (d.newReleased - released >= BIG_RELEASE_CH) {
                            val wait = lastBigReleaseAt + BIG_RELEASE_MIN_INTERVAL_MS -
                                android.os.SystemClock.elapsedRealtime()
                            if (wait > 0) delay(wait)
                            lastBigReleaseAt = android.os.SystemClock.elapsedRealtime()
                        }
                        if (d.delta.isNotEmpty()) appendAndTrace(state, d.delta)
                        released = d.newReleased
                    }
                    logGate(markdown, from, released)
                } else {
                    appendAndTrace(state, markdown.substring(p.length))
                    released = markdown.length
                }
                prev = markdown
            }
            else -> prev = markdown // 等长：无增量
        }
        if (gate && prev != null) {
            val newHeld = markdown.substring(released.coerceIn(0, markdown.length))
            // #446 根修（2026-09-27 真机条带差分定罪）：毕业收缩侧撤销一帧延迟。
            // 旧延迟使 held 收缩落在正文扩张的下一帧——净高单帧回缩，而帽
            // reserved 单调不回改：top 对齐下统计栏/held 缝单帧上跳 Δmoved、
            // 底对齐下内容整体下滑 Δmoved = 「active 块与上方内容位移不同步」
            // 撕裂（R4 录屏 b12 带 ±8~66px 差动，26 个 single-band 异常帧全部
            // 对齐 MDPgate 毕业窗 ±50ms）。同帧收缩后，延迟原本要防的「净高
            // 先减一帧触达列表」由帽协议承接——增量当帧被帽裁掉，flush 单出口
            // 只放行净增长（trueHeight−reserved），列表永不见负增量。
            held.value = newHeld
        }
    }
    return PilotStreamingState(state, held)
}

/** gate 放行观测日志（#437 阶段 D 仪器最小版：放行量/扣留量）。 */
private fun logGate(snapshot: String, from: Int, to: Int) {
    AppLogger.i(
        "MDPilot",
        "gate release=" + (to - from) + " held=" + (snapshot.length - to) +
            " releasedTotal=" + to + " snapshotTotal=" + snapshot.length
    )
}

private suspend fun appendAndTrace(state: StreamingMarkdownState, delta: String) {
    val snap = state.append(delta)
    AppLogger.i(
        "MDPilot",
        "append " + delta.length + "ch -> stable=" + snap.stableAst.size +
            " tail=" + snap.unstableAstTail.size + " total=" + state.content.length
    )
}
