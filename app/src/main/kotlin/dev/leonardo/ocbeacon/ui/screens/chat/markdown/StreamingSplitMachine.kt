package dev.leonardo.ocbeacon.ui.screens.chat.markdown

/**
 * #442 R2 分片唤醒（批次 A2 内核）：武装/触发状态机——毕业换装的决策件。
 *
 * 纯逻辑（时钟经 [onBatch] 的 nowMs 注入，JVM 可测）。职责：根据每批 gate 放行
 * 结果决定「武装影子态 / 双喂中 / 换装 / 无动作」，并维护已生效毕业计划（g 块
 * 集）与尾态原点。换装的**执行**（影子态创建/喂养/交换、entries 变更、帽 reset）
 * 由接线层承担（spec docs/specs/2026-10-01-442-r2-shard-awakening-design.md §2）。
 *
 * 语义（spec §2）：
 * - **武装**：毕业计划出现新冻结增量（boundary 越过当前原点 ≥ 门槛）→ 通知接线层
 *   创建影子态（origin=候选边界）并开始双喂；
 * - **触发**：影子追平 ∧ 滚动静止（滚动期不毕业——p90 窗口零成本）→ 换装：
 *   冻结 [origin, 武装边界) 为 g 块（经 [planStreamingGraduation] 以
 *   released=武装边界 求值——armed append-only 语义），原点推进；
 * - **重置**：回退（快照缩短/计划重置）**持续超过 [SHARD_RESET_GRACE_MS]** →
 *   清空回 Idle（接线层整树重建）。#503 R1：回退在冷启重组窗高发且瞬时（陈旧
 *   part.text/总线让位回退），单批即判死会把良性冷启变成「清空已发布集→
 *   origin 归零→重播→再毕业→再 fire」回卷（真机 9 fire/9s 定罪）——与 pilot
 *   非前缀 300ms 宽限（[NON_PREFIX_GRACE_MS]）同语义：瞬时回退等待恢复。
 */
internal class StreamingSplitMachine(
    private val minFreezeChars: Int = GRADUATE_MIN_CHARS,
) {
    /** 已生效毕业计划（g 块集；append-only）。 */
    var plan: StreamingGraduation = StreamingGraduation(emptyList(), 0)
        private set
    /** 当前尾态原点（= plan.tailFrom）。 */
    val origin: Int get() = plan.tailFrom
    /** 影子原点（-1=未武装）。 */
    var armedOrigin: Int = -1
        private set
    /** #503 R2：Fire 已发未确认的毕业计划（发布成功经 [confirmFire] 落账）。 */
    private var pendingFire: StreamingGraduation? = null
    /** #503 R1：回退武装时刻（-1=无回退）。 */
    private var regressedSinceMs: Long = -1L

    /** 每批 gate 放行后驱动。shadowLen=影子态当前内容长度（未武装时忽略）；
     *  nowMs=壁钟（回退宽限判定，接线层注入 SystemClock.elapsedRealtime）。 */
    fun onBatch(
        snapshot: String,
        released: Int,
        quiescent: Boolean = true,
        shadowLen: Int = 0,
        nowMs: Long,
    ): SplitAction {
        val candidate = planStreamingGraduation(snapshot, released, plan, minFreezeChars)
        // 回退 = 快照短于生效/武装原点，或放行回退（candidate 缩短）。
        if (snapshot.length < maxOf(plan.tailFrom, armedOrigin) ||
            candidate.tailFrom < plan.tailFrom
        ) {
            if (regressedSinceMs < 0) {
                regressedSinceMs = nowMs
                return SplitAction.None // 瞬时回退首见：等下一批（宽限窗内不动作）
            }
            if (nowMs - regressedSinceMs < SHARD_RESET_GRACE_MS) return SplitAction.None
            reset()
            return SplitAction.Reset // 回退持续=真重生成
        }
        regressedSinceMs = -1
        if (armedOrigin < 0) {
            if (candidate.tailFrom > origin) {
                armedOrigin = candidate.tailFrom
                return SplitAction.Arm(armedOrigin)
            }
            return SplitAction.None
        }
        // 武装中：影子追平 ∧ 静止 → 换装（冻结 [origin, armedOrigin)，以武装边界
        // 求值——武装后新到内容（boundary 再前进部分）留在尾块下轮毕业）
        val caughtUp = shadowLen >= released - armedOrigin
        if (caughtUp && quiescent) {
            val fired = planStreamingGraduation(snapshot, armedOrigin, plan, minFreezeChars)
            if (fired.tailFrom == armedOrigin) {
                // #503 R2 事务性：先发布后落账——fire 对未注册 no-op（回收竞态）时
                // 账本不推进，否则 machine 与 broker 脱钩=内容缺段直至回收自愈。
                // 接线层发布成功后必须 [confirmFire]；失败不调用=下批重试。
                pendingFire = fired
                return SplitAction.Fire(fired)
            }
            // 武装边界求值未成（边界内移等罕见形态）：解除武装保持 plan——不再
            // 无新内容重发旧计划（旧实现 Fire(plan) 空 publish=无谓 churn）
            armedOrigin = -1
            return SplitAction.None
        }
        return SplitAction.Feed
    }

    /** #503 R2：发布成功落账（plan 推进+解除武装）。失败不调用=账本原地重试。 */
    fun confirmFire() {
        pendingFire?.let { plan = it }
        pendingFire = null
        armedOrigin = -1
    }

    /** 完结/离树：回 Idle（接线层决定完结路径——spec §1 完结持续性）。 */
    fun reset() {
        plan = StreamingGraduation(emptyList(), 0)
        armedOrigin = -1
        pendingFire = null
        regressedSinceMs = -1
    }

    /** 冷启播种（#442 A2）：item 回收重组合后从 broker 已发布态续账——冻结
     *  append-only 语义防重复毕业已发布区间。 */
    fun adopt(seed: StreamingGraduation) {
        plan = seed
        armedOrigin = -1
        pendingFire = null
        regressedSinceMs = -1
    }
}

/** #503 R1：回退宽限窗——与 pilot 非前缀宽限（[NON_PREFIX_GRACE_MS]）同量级。 */
internal const val SHARD_RESET_GRACE_MS = 300L

internal sealed interface SplitAction {
    /** 无动作（未到门槛/武装未追平/回退宽限窗内静默）。 */
    object None : SplitAction

    /** 武装：创建影子态（origin=[armedOrigin]）并开始双喂。 */
    data class Arm(val armedOrigin: Int) : SplitAction

    /** 武装中：影子继续双喂（未追平或滚动期）。 */
    object Feed : SplitAction

    /** 换装：冻结集=[plan]（tailFrom=新原点）；接线层原子交换，成功后须
     *  [StreamingSplitMachine.confirmFire] 落账（#503 R2 事务性）。 */
    data class Fire(val plan: StreamingGraduation) : SplitAction

    /** 非前缀重置（回退持续超宽限）：接线层整树重建。 */
    object Reset : SplitAction
}
