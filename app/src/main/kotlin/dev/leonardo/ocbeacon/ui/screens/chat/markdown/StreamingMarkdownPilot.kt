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
 * #504（2026-10-02 真机定罪）完结换装闪塌：DSH 合成 id→权威 seq id 换代经
 * `key(item.group.part.id)` 销毁 pilot 子树 → #472 本地保持记忆丢失 + 异步
 * 终态 State.Loading 占位（实测 8754px→200px 塌缩 260ms）。
 *
 * 桥=**活跃内容指纹**（多槽 LRU×4——reasoning/text 多部件并行流式各自登记，单槽 last-writer-wins 会被推理块终态抢占（真机 20:53 定罪 stash=1165/inc=3988））：pilot 渲染期每次内容更新登记归一化终帧
 * （主线程组合期直写 @Volatile——CML-tick 同款纪律，非快照零重组成本）；换代
 * 后新组合以 [completionHandoffMatches] 内容门查指纹，命中=「刚流式渲染过的
 * 同文」→ MarkdownContent 换装帧改走同步解析首帧全高。
 *
 * 为什么指纹而非状态实例交接：①Compose 派发次序——旧节点 onDispose 在 apply
 * 后、新节点 remember 在组合中内联，dispose 侧 stash 恒慢一拍（首验 miss 实
 * 证）；②库 StreamingMarkdownState 对新收集器零重放（次验 hold 渲染空态
 * 200px 实证）——实例复用两条路都不通，指纹门+同步解析是最小可靠面。
 */
internal object CompletionHandoff {
    /** 多槽 LRU（容量 4）：reasoning/text 等多部件并行流式各自登记（真机 20:53
     * 定罪：单槽 last-writer-wins 被推理块终态抢占，正文指纹丢失 → 换装 miss）。 */
    private val slots = object : LinkedHashMap<String, String>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > 4
    }

    /** pilot 组合期登记（remember(markdown) 归一化后直写——每内容变更一次；主线程组合期，非快照零重组成本）。 */
    fun noteActive(normalizedContent: String) {
        slots[normalizedContent] = normalizedContent
    }

    /** 换装帧查询：任一槽内容门命中=刚流式渲染过的同文。 */
    fun takeIfMatches(content: String): Boolean =
        slots.keys.any { completionHandoffMatches(it, content) }

    /** [504-forensic] miss 取证（DEBUG-only，#505 升级）：最优槽的前缀/后缀
     *  公共长度 + 缺口 + 分叉点上下文采样——miss 形态当场可判（尾差/中段洞/
     *  全文异构），终结逐轮猜测。 */
    fun forensicProbe(incomingRaw: String) {
        val inc = normalizeForStreaming(incomingRaw)
        val desc = if (slots.isEmpty()) "槽空" else {
            var best: String? = null
            var bestI = -1
            for (st in slots.keys) {
                var i = 0
                val n = minOf(st.length, inc.length)
                while (i < n && st[i] == inc[i]) i++
                if (i > bestI) { bestI = i; best = st }
            }
            val st = best!!
            var s = 0
            val m = minOf(st.length, inc.length)
            while (s < m - bestI && st[st.length - 1 - s] == inc[inc.length - 1 - s]) s++
            val ctx = inc.substring(maxOf(0, bestI - 20).coerceAtMost(bestI),
                minOf(inc.length, bestI + 20))
                .replace("\n", "\\n")
            "slot=" + st.length + " pfxLen=" + bestI + " sfxLen=" + s +
                " gap=" + (inc.length - st.length) + " ctx=@" + bestI + " " + ctx
        }
        android.util.Log.w("504-forensic", "miss: inc=" + inc.length + " " + desc)
    }

    /**
     * #509（2026-10-03 真机定罪）重灌检测：当前 markdown 是否为某登记指纹的
     * **短前缀**（余量 >[REPLAY_HOLD_MARGIN_CH]）。毕业重灌（DSH 权威转写以
     * delta 序列重放，实测 4→5→15→…→1182ch 跨 ~380ms）的每个中间态都是终文
     * 的前缀——配合调用侧的槽位卫（pilotEverRendered，本槽刚流式渲染过）即
     * 构成「重灌在途」判据。真重生成走 asyncParse=false 流式分支不经此门
     * （调用侧以 asyncParse 门 freeze，重生成不被冻结）。
     */
    fun replayHoldCandidate(markdown: String): Boolean {
        val inc = normalizeForStreaming(markdown)
        if (inc.isEmpty()) return false
        return slots.keys.any { st ->
            st.length > inc.length + REPLAY_HOLD_MARGIN_CH && st.startsWith(inc)
        }
    }

    /** 测试缝：单例跨用例隔离。 */
    fun resetForTest() {
        slots.clear()
    }
}

/** #509 重灌保持余量：指纹比当前 markdown 至少长此值才视为「重灌在途」而非
 *  正常尾批补齐（与 #504 尾容错 512 同源取半）。 */
internal const val REPLAY_HOLD_MARGIN_CH = 256

/**
 * #504 交接内容门（纯函数，单测锚）：stash 侧存归一化形态、取用侧对原文
 * 归一化后比对——两侧同变换（[normalizeForStreaming]）保证 DSH 换装
 * 「流式终帧 vs 权威 seq 文本」在归一化坐标下命中。**尾差容错**：pilot 终帧
 * 可落后终态数字符（末批 delta 未入终帧/扣留尾，真机实测 2 字符）——严格
 * 相等会恒 miss（首验即未命中）；前缀相等且缺口 ≤[COMPLETION_HANDOFF_TAIL_TOLERANCE_CH]
 * 视为同文档（hold 渲染旧帧，终态就绪原子补齐）。
 *
 * #505 头尾锚容错：流式累积与权威文本可存在**中段小分叉**（换装前残余：
 * 归一化跨快照变换差、传输层字符差异等）——首尾各留 ≥[COMPLETION_HANDOFF_ANCHOR_CH]
 * 干净区且缺口在容忍内即同文档。真机 turn 30 定罪：中段 10 字缺口使
 * startsWith 恒 false → miss → 200px 占位闪塌。安全边界：本门只选解析策略
 * （同步/异步），不选内容——误命中代价=一次 ~10ms 主线程同步解析，无正确性
 * 风险（宁缺勿错配的「错配」后果已由内容恒取 markdown 参数消除）。全文异构
 * （前缀锚或后缀锚破坏）恒 miss。
 */
internal fun completionHandoffMatches(stashedNormalized: String?, incomingRaw: String): Boolean {
    if (stashedNormalized.isNullOrEmpty()) return false
    val incoming = normalizeForStreaming(incomingRaw)
    if (incoming == stashedNormalized) return true
    if (kotlin.math.abs(incoming.length - stashedNormalized.length) > COMPLETION_HANDOFF_TAIL_TOLERANCE_CH) {
        return false
    }
    // 公共前缀 + 尾部重写松弛（真机取证 19:58：gap=16 但 prefix=false——完结
    // 内容对流式终帧的**尾部区域**有 ~16 字符改写，非纯追加，startsWith 恒
    // false）。分叉点落在两串末 [COMPLETION_HANDOFF_TAIL_REWRITE_CH] 内=同文档
    // 尾部改写（围栏闭合/末段修正族）；中段分叉交由下方头尾锚裁决。
    var i = 0
    val n = minOf(stashedNormalized.length, incoming.length)
    while (i < n && stashedNormalized[i] == incoming[i]) i++
    if (i >= stashedNormalized.length - COMPLETION_HANDOFF_TAIL_REWRITE_CH &&
        i >= incoming.length - COMPLETION_HANDOFF_TAIL_REWRITE_CH
    ) return true
    // #505 头尾锚：中段小分叉——前缀与后缀各留干净区即同文档（后缀扫描止于
    // 前缀边界，防共享前缀重复计数）。分叉带宽 > 两串任意一端锚区=真异构 miss。
    if (i < COMPLETION_HANDOFF_ANCHOR_CH) return false
    var s = 0
    while (s < n - i &&
        stashedNormalized[stashedNormalized.length - 1 - s] == incoming[incoming.length - 1 - s]
    ) s++
    return s >= COMPLETION_HANDOFF_ANCHOR_CH
}

/** #504 尾差容错上限：换装缺口（终态−终帧）正常 ≤ 末批 delta 量级；超此=不同文档。 */
internal const val COMPLETION_HANDOFF_TAIL_TOLERANCE_CH = 512

/** #504 尾部重写松弛：分叉点须落在两串末此字符数内（围栏闭合/末段修正族改写面）。 */
internal const val COMPLETION_HANDOFF_TAIL_REWRITE_CH = 256

/** #505 头尾锚干净区：中段分叉命中需首/尾各留 ≥ 此字符数的公共区——两锚
 *  皆在=分叉隔离在中带（同文档换装残余形态）；任一锚破坏=真异构。 */
internal const val COMPLETION_HANDOFF_ANCHOR_CH = 256

/**
 * 前缀差分 append 包装（spec §1）+ #437 安全放行闸接线。
 *
 * Part.Text.text 仍以整串快照到达（STREAM_FLUSH_INTERVAL_MS 批 flush 产物），在此与库状态内部的
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
 *   （库官方姿势同此；尾部小解析由批 flush 节奏摊平）。
 * - #471③ 归一化前移（冲突①裁决解除，spec §3.4）：快照先经
 *   normalizeForStreaming（与完结 normalizeForRender 同核心同序、逐字节
 *   一致），delta 为归一化文本——终帧=流式帧，完结换装不再有归一化
 *   重排。归一化的四类流式破口（$$ 逐字符凑对/表格行内数学/表头行
 *   待定三行结构/栏状态分歧）已随本批在 gate 与变换侧修订（性质测试
 *   NormalizationStreamingMonotonicityTest 钉死）；未预见的非前缀由
 *   #472 宽限窗 + resetKey 重建兜底（一次重建闪，正确性不破）。
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
 * #H4 重建快速重灌（2026-09-30 坍缩重建根修，真机 13:55 定罪）：RESETKEY
 * 重建后的再铺开沿用首跑限速（200ch + 200ms 壁钟）——4108ch 以 200ch/210ms
 * 重灌 4.4s（MDResize 每 210ms +988px ≈ 200 CJK 字符排版高），用户主诉
 * 「整个回答坍缩并重建」的可感知重建段。重建内容是用户刚看过的材料：
 * 逐帧 4× 批量、免壁钟限速（~6 帧≈100ms 完成换装，高度引擎帽配对吸收）；
 * 限速仅保留给首跑铺开（其设计场景：多消息 turn 后续段全量到达的单帧
 * GC/解析压力分散）。
 */
internal const val REBUILD_REFEED_CHUNK_CH = BIG_RELEASE_CH * 4
internal const val REBUILD_REFEED_MIN_INTERVAL_MS = 0L

/** 再铺开节奏（纯函数，单测锚 RefeedPacingTest）：首跑=限速铺开；重建=快速重灌。 */
internal data class RefeedPacing(val chunkCh: Int, val minIntervalMs: Long)

internal fun refeedPacing(rebuild: Boolean): RefeedPacing =
    if (rebuild) RefeedPacing(REBUILD_REFEED_CHUNK_CH, REBUILD_REFEED_MIN_INTERVAL_MS)
    else RefeedPacing(BIG_RELEASE_CH, BIG_RELEASE_MIN_INTERVAL_MS)

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
internal fun rememberPilotStreamingMarkdownState(
    markdown: String,
    freeze: Boolean = false,
    /** #442 R2 分片唤醒（A2）：注册在案的流式大文本 part 携带控制器——毕业
     *  时机由 [StreamingSplitMachine] 决策，Fire 时切尾重建（#H4 快速重灌）+
     *  发布冻结块。null（未注册/开关关）= 原路径零改造。 */
    shard: ShardController? = null,
): PilotStreamingState {
    // #471③ 归一化前移（终帧=流式帧，spec §3.4）：快照先归一化再前缀差分——
    // prev/released/heldTail 坐标皆归一化坐标，heldTail 随之显示归一化文本
    //（- [ ] 预览、tex 围栏行——WYSIWYG）。流式显示文本与完结渲染逐字节
    // 一致，完结换装从「文本不同→排版重排→跳变」变为「同文本换渲染器→
    // 视觉无事发生」。放行单调性（归一化回改点全落 gate 扣留区）由
    // NormalizationStreamingMonotonicityTest 性质测试钉死；主线程成本由
    // 各变换哨兵快路径约束（无 | /无任务字符/无数学痕迹时零正则）。
    val normalized = remember(markdown) { normalizeForStreaming(markdown) }
    var resetKey by remember { mutableIntStateOf(0) }
    // #442 A2 分片切尾：已毕业冻结前缀的原点（归一化全坐标）。state 内容与
    // prev/released 为「尾坐标」（eff = normalized.substring(sliceOrigin)）；
    // machine 与 broker 发布为全坐标。冷启（item 回收重组合）从 broker 续账。
    var sliceOrigin by remember { mutableIntStateOf(shard?.coldStartOrigin() ?: 0) }
    // machine 冷启播种：已发布冻结集 append-only 续账（防重复毕业已发布区间）
    val shardMachine = remember(shard) {
        StreamingSplitMachine().also { m -> shard?.coldStartPlan()?.let { m.adopt(it) } }
    }
    // #471③ 差分基准修正：prev 存「放行前缀」（normalized.take(released)）
    // 而非全文快照——归一化闭合重写（$$→tex 围栏等）天然使全文对 prev
    // 非前缀，但重写点全部落在 gate 扣留区（released 之后），放行前缀跨
    // 快照稳定（NormalizationStreamingMonotonicityTest 性质）。以全文为基准
    // 会把合法的扣留区重写误判为「重生成」→ 300ms 宽限后 resetKey 整树
    // 静默重建（真机 P1 复现：divergeAt=146 prev=[$$ new=[tex 围栏 →
    // 卡高塌缩 -1128px + pilot 从零重铺）。
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
    // #H4：非前缀重建后的再铺开走快速重灌（免壁钟限速）；首跑保持限速铺开
    var fastRefeed by remember { mutableStateOf(false) }
    val gate = StreamingMarkdownPilot.stableReveal
    LaunchedEffect(normalized, state, StreamingScrollHold.holding) {
        val p = prev
        // #442 A2 分片切尾：差分/放行在尾坐标（eff）上进行——machine 与 broker
        // 为全坐标（sliceOrigin + released 换算）。coerce 防御非前缀缩短窗的
        // 越界（随后 startsWith 判负 → 既有重建路径接管）。
        val eff = normalized.substring(sliceOrigin.coerceAtMost(normalized.length))
        // #472 完结桥接期冻结:pilot 终帧即终点——async 在途的新快照(完结
        // sync/part 重组的非前缀串)一律不进 pilot,换装交给终态路径
        if (freeze) return@LaunchedEffect
        // 滚动/惯性中：暂缓增长增量（prev 不动，settle 后整段一次追平=一次重排版）
        if (StreamingScrollHold.holding && p != null && eff.length > p.length) {
            return@LaunchedEffect
        }
        if (p == null || eff.startsWith(p)) nonPrefixSinceMs = -1L
        when {
            // 首跑（含重建后的新实例）：整串作为初始增量（gate 后定案前缀）
            p == null -> {
                if (eff.isNotEmpty()) {
                    if (gate) {
                        // 2026-09-27 首跑多帧铺开（真机取证：多消息 turn 的后续段
                        // 全量到达无 delta 流，首跑单帧巨量 append 1136-2087ch——
                        // 单帧 GC/解析压力集中且打穿帽揭示量子化节奏。改为逐帧铺开
                        // + #438① 大放行壁钟限速（与增量分支同语义），视觉节奏由帽
                        //（≤800px 首亮+1600px/500ms 步进）+限速共同接管。
                        // #H4（2026-09-30）：RESETKEY 重建后的再铺开改快速重灌
                        //（4× 批量/帧、免壁钟限速）——重建限速重铺 4.4s 是「坍缩并
                        // 重建」主诉的可感知重建段（节奏见 [refeedPacing]）。
                        // #442 A2 冷续单帧：item 回收重组合（sliceOrigin 继承、
                        // fastRefeed=false）的尾块内容一次性入树——静态内容不得
                        // 限速重铺；Fire 重建仍走 #H4 快灌。
                        val pacing = if (fastRefeed || sliceOrigin <= 0) refeedPacing(fastRefeed)
                        else RefeedPacing(Int.MAX_VALUE, 0L)
                        var rel = 0
                        while (rel < eff.length) {
                            val d = SafePrefixGate.releaseDelta(eff, rel, pacing.chunkCh)
                            if (d.newReleased <= rel) break // gate 拒绝（扣留中）——后续增量/EOF 接管
                            if (pacing.minIntervalMs > 0 && d.newReleased - rel >= pacing.chunkCh) {
                                val wait = lastBigReleaseAt + pacing.minIntervalMs -
                                    android.os.SystemClock.elapsedRealtime()
                                if (wait > 0) delay(wait)
                                lastBigReleaseAt = android.os.SystemClock.elapsedRealtime()
                            }
                            if (d.delta.isNotEmpty()) appendAndTrace(state, d.delta)
                            rel = d.newReleased
                            if (rel < eff.length) withFrameNanos { }
                        }
                        released = rel
                        logGate(eff, 0, released)
                        fastRefeed = false
                    } else {
                        appendAndTrace(state, eff)
                        released = eff.length
                    }
                }
                prev = eff.take(released) // 放行前缀（#471③ 差分基准）
            }
            // 非前缀（重生成/编辑）：下轮新实例走整串重建；
            // #437 §4 数据层摆动（reconciler vs live 竞态）会高频触发此分支——
            // 风暴抑制：冻结放行与重建（prev 保持旧值，旧串回来无缝恢复）
            !eff.startsWith(p) -> {
                // #472 宽限冻结:瞬时摆动(reconciler 竞态/完结 sync 重组)保树
                // 保进度,旧串回来无缝续播;超窗仍非前缀才是真重生成
                val nowMs = android.os.SystemClock.elapsedRealtime()
                // #471③ 验收探针（DEBUG-only）：非前缀事件取证——武装时刻与
                // 重建时刻此前静默（仅风暴打 flap），单次重建无日志=定位盲区
                //（真机 P1 复现：h=1304→176 塌缩+pilot 从零重铺=resetKey 静默重建）。
                if (dev.leonardo.ocbeacon.BuildConfig.DEBUG) {
                    val p0 = p
                    if (p0 != null) {
                        var i = 0
                        val lim = minOf(p0.length, eff.length)
                        while (i < lim && p0[i] == eff[i]) i++
                        val ctxA = p0.substring(i.coerceAtMost(p0.length), (i + 16).coerceAtMost(p0.length))
                        val ctxB = eff.substring(i.coerceAtMost(eff.length), (i + 16).coerceAtMost(eff.length))
                        AppLogger.w("MDPilot", "nonPrefix " +
                            (if (nonPrefixSinceMs < 0L) "armed" else "hold") +
                            " prevLen=" + p0.length + " newLen=" + eff.length +
                            " divergeAt=" + i + " prevCtx=[" + ctxA + "] newCtx=[" + ctxB + "]")
                    }
                }
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
                    if (dev.leonardo.ocbeacon.BuildConfig.DEBUG) {
                        // #H1 取证增强（2026-09-30）：raw（归一化前）尾部同报——
                        // 区分「数据层改写」（raw 亦异）与「归一化回改」（仅归一化
                        // 坐标分歧）。13:55 事件仅 normalized ctx 可见，raw 侧归因
                        // 靠本探针在下一次出现时补齐。
                        AppLogger.w("MDPilot", "RESETKEY rebuild — nonPrefix survived grace window" +
                            " prevLen=" + (p?.length ?: -1) + " newLen=" + eff.length +
                            " rawTail=[" + markdown.takeLast(24) + "]")
                    }
                    // #442 A2：真重建——冻结文本已陈旧，清发布回单容器
                    if (shard != null) shard.onRebuild()
                    sliceOrigin = 0
                    prev = null
                    released = 0
                    held.value = ""
                    fastRefeed = true // #H4：重建再铺开走快速重灌
                    resetKey++
                }
            }
            eff.length > p.length -> {
                if (gate) {
                    // #438①（2026-09-27 壁钟限速）：catch-up/突发到达期 gate 按
                    // 400ch/批 释放过快（R9 真机实证 442ms 聚 7 批=单 note
                    // d=6236px，中继缓冲突发下观感即「整块一次性出」）。大放行
                    //（≥[BIG_RELEASE_CH]）间隔下限 [BIG_RELEASE_MIN_INTERVAL_MS]——
                    // 与到达解耦、只约束大批；正常流式小批（<200ch）直通不受影响。
                    val from = released
                    while (released < eff.length) {
                        // #438①：每批喂 [BIG_RELEASE_CH]（含空行毕业段——原不受
                        // 批预算约束的漏洞）；批 ≥ 阈值即触发壁钟间隔
                        val d = SafePrefixGate.releaseDelta(eff, released, BIG_RELEASE_CH)
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
                    logGate(eff, from, released)
                } else {
                    appendAndTrace(state, eff.substring(p.length))
                    released = eff.length
                }
                prev = eff.take(released) // 放行前缀（#471③ 差分基准）
            }
            else -> prev = eff.take(released) // 等长：无增量（#471③ 基准统一）
        }
        // #442 A2：毕业时机决策（全坐标；quiescent=!holding——滚动期不毕业，
        // 滑动 p90 窗口零毕业成本）。首跑铺开完成（prev!=null）后才参与。
        if (shard != null && gate && prev != null) {
            val act = shardMachine.onBatch(
                normalized,
                sliceOrigin + released,
                quiescent = !StreamingScrollHold.holding,
                // A2 中间态（spec §2）：无影子态——武装即视为追平，Fire=切尾
                // 重建走 #H4 快速重灌（≤800ch 尾单帧完成；大尾块数帧回涨，
                // A3 影子态换装消除该窗口）
                shadowLen = if (shardMachine.armedOrigin >= 0) sliceOrigin + released - shardMachine.armedOrigin else 0,
                // #503 R1：回退宽限判定的壁钟注入（纯函数 machine 无时钟）
                nowMs = android.os.SystemClock.elapsedRealtime(),
            )
            when (act) {
                is SplitAction.Fire -> {
                    // #503 R2 事务性：发布成功才落账/推进——未注册（回收竞态）
                    // 返回 false 时账本与 pilot 原地不动，下批重试
                    val texts = act.plan.chunks.map { c -> normalized.substring(c.from, c.to) }
                    val published = shard.fire(act.plan.chunks, texts, act.plan.tailFrom)
                    if (published) {
                        shardMachine.confirmFire()
                        if (dev.leonardo.ocbeacon.BuildConfig.DEBUG) {
                            AppLogger.i("MDPilot", "shard fire origin=" + act.plan.tailFrom +
                                " chunks=" + act.plan.chunks.size +
                                " tail=" + (normalized.length - act.plan.tailFrom) + "ch")
                        }
                        // 帽 hardReset → 发布 → 切尾重建：同协程步，换装帧原子见三者
                        sliceOrigin = act.plan.tailFrom
                        prev = null
                        released = 0
                        fastRefeed = true
                        resetKey++
                        return@LaunchedEffect // 新实例+新原点由重启的 effect 首跑接管
                    } else if (dev.leonardo.ocbeacon.BuildConfig.DEBUG) {
                        AppLogger.w("MDPilot", "shard fire dropped (unregistered) — retry next batch")
                    }
                }
                SplitAction.Reset -> {
                    if (dev.leonardo.ocbeacon.BuildConfig.DEBUG) {
                        AppLogger.w("MDPilot", "shard reset — snapshot shrink/release regression")
                    }
                    shard.onRebuild()
                    sliceOrigin = 0
                    prev = null
                    released = 0
                    fastRefeed = true
                    resetKey++
                    return@LaunchedEffect
                }
                else -> {}
            }
        }
        if (gate && prev != null) {
            val newHeld = normalized.substring(
                (sliceOrigin + released).coerceIn(0, normalized.length)
            )
            // #446 根修（2026-09-27 真机条带差分定罪）：毕业收缩侧撤销一帧延迟。
            // 旧延迟使 held 收缩落在正文扩张的下一帧——净高单帧回缩，而帽
            // reserved 单调不回改：top 对齐下统计栏/held 缝单帧上跳 Δmoved、
            // 底对齐下内容整体下滑 Δmoved = 「active 块与上方内容位移不同步」
            // 撕裂（R4 录屏 b12 带 ±8~66px 差动，26 个 single-band 异常帧全部
            // 对齐 MDPgate 毕业窗 ±50ms）。同帧收缩后，延迟原本要防的「净高
            // 先减一帧触达列表」由帽协议承接——增量当帧被帽裁掉，flush 单出口
            // 只放行净增长（trueHeight−reserved），列表永不见负增量。
            // #471③ 验收探针（DEBUG-only）：heldTail 长度变化（毕业交接时刻）
            if (dev.leonardo.ocbeacon.BuildConfig.DEBUG && newHeld.length != held.value.length) {
                AppLogger.i(
                    "MDPilot",
                    "held size " + held.value.length + " -> " + newHeld.length +
                        " (released=" + released + ")",
                )
            }
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
