package dev.leonardo.ocbeacon.ui.screens.chat.markdown

/**
 * #442 R2 分片唤醒（批次 A2 内核）：武装/触发状态机——毕业换装的决策件。
 *
 * 纯逻辑（时钟/Compose 无关，JVM 可测）。职责：根据每批 gate 放行结果决定
 * 「武装影子态 / 双喂中 / 换装 / 无动作」，并维护已生效毕业计划（g 块集）与
 * 尾态原点。换装的**执行**（影子态创建/喂养/交换、entries 变更、帽 reset）由
 * 接线层承担（spec docs/specs/2026-10-01-442-r2-shard-awakening-design.md §2）。
 *
 * 语义（spec §2）：
 * - **武装**：毕业计划出现新冻结增量（boundary 越过当前原点 ≥ 门槛）→ 通知接线层
 *   创建影子态（origin=候选边界）并开始双喂；
 * - **触发**：影子追平 ∧ 滚动静止（滚动期不毕业——p90 窗口零成本）→ 换装：
 *   冻结 [origin, 武装边界) 为 g 块（经 [planStreamingGraduation] 以
 *   released=武装边界 求值——armed append-only 语义），原点推进；
 * - **重置**：非前缀（快照缩短/计划重置）→ 清空回 Idle（接线层整树重建）。
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

    /** 每批 gate 放行后驱动。shadowLen=影子态当前内容长度（未武装时忽略）。 */
    fun onBatch(
        snapshot: String,
        released: Int,
        quiescent: Boolean = true,
        shadowLen: Int = 0,
    ): SplitAction {
        // 非前缀重置：快照缩短到生效原点/武装原点之下（正常流 snapshot 只增长
        // 且 armedOrigin ≤ released ≤ snapshot.length）——计划防御（plan 侧）只
        // 覆盖已生效集，武装中的缩短须在此显式判
        if (snapshot.length < maxOf(plan.tailFrom, armedOrigin)) {
            reset()
            return SplitAction.Reset
        }
        val candidate = planStreamingGraduation(snapshot, released, plan, minFreezeChars)
        // 计划侧重置（released 回退等）→ 整树重建
        if (candidate.tailFrom < plan.tailFrom) {
            reset()
            return SplitAction.Reset
        }
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
            plan = if (fired.tailFrom == armedOrigin) fired else plan
            armedOrigin = -1
            return SplitAction.Fire(plan)
        }
        return SplitAction.Feed
    }

    /** 完结/离树：回 Idle（接线层决定完结路径——spec §1 完结持续性）。 */
    fun reset() {
        plan = StreamingGraduation(emptyList(), 0)
        armedOrigin = -1
    }
}

internal sealed interface SplitAction {
    /** 无动作（未到门槛/武装未追平）。 */
    object None : SplitAction

    /** 武装：创建影子态（origin=[armedOrigin]）并开始双喂。 */
    data class Arm(val armedOrigin: Int) : SplitAction

    /** 武装中：影子继续双喂（未追平或滚动期）。 */
    object Feed : SplitAction

    /** 换装：冻结集升格为 [plan]（tailFrom=新原点）；接线层原子交换。 */
    data class Fire(val plan: StreamingGraduation) : SplitAction

    /** 非前缀重置：接线层整树重建。 */
    object Reset : SplitAction
}
