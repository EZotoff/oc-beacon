package dev.leonardo.ocbeacon.domain.supervisor

import kotlinx.serialization.json.Json
import java.time.Instant

/** 冻结原因（对应 contract reader 的 FreezeReason 词汇）。 */
enum class FreezeReason {
    READ_ERROR,
    INVALID_SCHEMA,
    STALE,
    FUTURE_SKEW,
    GENERATION_REGRESSION,
    MISSING_UPDATES,
    CLOCK_JUMP,
}

/** 读取结果：Live=可用镜像；Frozen=保留 lastGood（可为 null）并给出原因。 */
sealed class OperatorViewReadOutcome {
    data class Live(val view: OperatorViewDto) : OperatorViewReadOutcome()
    data class Frozen(val reason: FreezeReason, val lastGood: OperatorViewDto?) : OperatorViewReadOutcome()
}

/**
 * operator-view.json 契约新鲜度评估器——移植自 ez-omo-config
 * supervisor/src/operator-view-reader.ts（Reader obligations (c)），
 * 语义逐条对齐：
 *  - stale：receipt 时 producedAt 距今 > 30s；
 *  - future-skew：producedAt 超前 > 5s；
 *  - (generation,lastSeq) 字典序不得回退；同 generation 的 lastSeq 变化
 *    = 漏更新 → missing-updates 冻结；
 *  - ±5s 之外的 wall-clock 跳变只强制重读（clock-jump），绝不延长有效期；
 *  - 冻结结果携带 lastGood 镜像（绝不把 stale 卡转成空/闲置）。
 *
 * 纯 Kotlin、可注入 wall/monotonic 时钟，文件字节由调用方（repository）传入。
 */
class OperatorViewFreshness(
    private val json: Json = DEFAULT_JSON,
    private val wallMs: () -> Long = System::currentTimeMillis,
    private val monoMs: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    private var prev: Pair<Long, Long>? = null
    private var lastGood: OperatorViewDto? = null
    private var anchorWallMs = 0L
    private var anchorMonoMs = 0L
    private var anchored = false

    /** Schema 校验：把原始字节解析为 [OperatorViewDto]；任何形状违规返回 null。 */
    fun parse(raw: String): OperatorViewDto? {
        val view = runCatching { json.decodeFromString<OperatorViewDto>(raw) }.getOrNull() ?: return null
        return view.takeIf(::isValid)
    }

    private fun isValid(view: OperatorViewDto): Boolean {
        if (view.schemaVersion != 1) return false
        if (view.generation <= 0L) return false
        if (view.lastSeq < 0L) return false
        if (runCatching { Instant.parse(view.producedAt) }.isFailure) return false
        if (view.cards.size > READER_MAX_CARDS) return false
        // 值约束镜像 TS zod：id min(1)；ageSeconds 非负；severity 枚举 A–D；
        // jumpAvailable 字面 true。字段存在性由 DTO 去默认值 + MissingFieldException
        // 保证（reasonText/rootLabel/sessionLabel/premiseTexts 存在但允许空串，同 TS z.string()）。
        return view.cards.all { card ->
            card.id.isNotEmpty() &&
                card.ageSeconds >= 0L &&
                card.severity in SEVERITIES &&
                card.jumpAvailable
        }
    }

    /** 对刚读到的文件镜像做一次完整评估（每次调用即全新镜像，等价 TS read()）。 */
    fun read(raw: String): OperatorViewReadOutcome {
        val view = parse(raw) ?: return frozen(FreezeReason.INVALID_SCHEMA)
        val wall = wallMs()
        val producedMs = Instant.parse(view.producedAt).toEpochMilli()
        if (wall - producedMs > READ_STALE_AGE_MS) return frozen(FreezeReason.STALE)
        if (producedMs - wall > READ_FUTURE_SKEW_MS) return frozen(FreezeReason.FUTURE_SKEW)
        val prevSeq = prev
        if (prevSeq != null) {
            val (prevGeneration, prevLastSeq) = prevSeq
            if (view.generation < prevGeneration) return frozen(FreezeReason.GENERATION_REGRESSION)
            // (generation,lastSeq) 字典序不得回退；同一 generation 内 lastSeq 变化
            // 意味着漏掉了更新（gap/jump）。
            if (view.lastSeq < prevLastSeq) return frozen(FreezeReason.MISSING_UPDATES)
            if (view.generation == prevGeneration && view.lastSeq != prevLastSeq) {
                return frozen(FreezeReason.MISSING_UPDATES)
            }
        }
        prev = view.generation to view.lastSeq
        lastGood = view
        anchorWallMs = wall
        anchorMonoMs = monoMs()
        anchored = true
        return OperatorViewReadOutcome.Live(view)
    }

    /**
     * Receipt 后在 monotonic 计时上的新鲜度复查：monotonic 经过 30s 即 stale；
     * wall clock 与单调外推偏差超过 ±5s（前后皆算）→ clock-jump——
     * 调用方必须重新 read()，跳变绝不延长有效期。
     */
    fun validity(wallNowMs: Long, monoNowMs: Long): OperatorViewReadOutcome {
        val good = lastGood ?: return frozen(FreezeReason.READ_ERROR)
        if (!anchored) return frozen(FreezeReason.READ_ERROR)
        if (monoNowMs - anchorMonoMs > READ_STALE_AGE_MS) return frozen(FreezeReason.STALE)
        val extrapolatedWall = anchorWallMs + (monoNowMs - anchorMonoMs)
        if (kotlin.math.abs(wallNowMs - extrapolatedWall) > READ_CLOCK_JUMP_MS) {
            return frozen(FreezeReason.CLOCK_JUMP)
        }
        return OperatorViewReadOutcome.Live(good)
    }

    private fun frozen(reason: FreezeReason): OperatorViewReadOutcome =
        OperatorViewReadOutcome.Frozen(reason, lastGood)

    /** 文件字节读取失败（repository 侧 IO 错误）：冻结并保留 lastGood。 */
    fun readError(): OperatorViewReadOutcome = frozen(FreezeReason.READ_ERROR)

    companion object {
        /** Contract: stale = producedAt older than 30 s at receipt. */
        const val READ_STALE_AGE_MS = 30_000L

        /** Contract: producedAt more than 5 s in the future = future-skew freeze. */
        const val READ_FUTURE_SKEW_MS = 5_000L

        /** Contract: a wall-clock jump beyond ±5 s must force a fresh read. */
        const val READ_CLOCK_JUMP_MS = 5_000L

        /**
         * Contract: poll at most every 5 s（上限而非下限——freeze 在 30s 预算
         * 到期后 ≤5s 内落地由该节奏保证）。
         */
        const val READ_MAX_POLL_MS = 5_000L

        /** Reader-side mirror of the publisher's MAX_CARDS bound. */
        const val READER_MAX_CARDS = 20

        private val SEVERITIES = setOf("A", "B", "C", "D")
        private val DEFAULT_JSON = Json { ignoreUnknownKeys = true }
    }
}
