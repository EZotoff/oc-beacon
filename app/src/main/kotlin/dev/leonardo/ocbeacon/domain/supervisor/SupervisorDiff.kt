package dev.leonardo.ocbeacon.domain.supervisor

import dev.leonardo.ocbeacon.domain.model.SupervisorAttentionItem

/**
 * Supervisor 后台轮询的 diff / 优先级纯函数（无 Android 依赖，JVM 可测）。
 *
 * 轮询 worker 用这些函数把「当前快照」与「上次已见状态」对比，决定哪些
 * 事项需要弹通知。所有函数无副作用、无状态——去重状态的读写由调用方
 * （[dev.leonardo.ocbeacon.service.SupervisorPollCoordinator]）负责。
 */
object SupervisorDiff {

    /**
     * 通知优先级排序键：数值越小越优先。
     * ESCALATE(APPROVAL) 最高（需要操作员批准），其次 ESCALATE，再 STEER/CONTINUE。
     */
    fun priorityRank(item: SupervisorAttentionItem): Int = when {
        item.actionClass == "ESCALATE" && item.escalationKind == "APPROVAL" -> 0
        item.actionClass == "ESCALATE" -> 1
        item.actionClass == "STEER" -> 2
        item.actionClass == "CONTINUE" -> 3
        else -> 4
    }

    /**
     * 尚未通知过的开放事项（按 id 判定），按优先级升序、stakes 降序排列。
     * 已 resolved 的事项不会出现在 [items] 中（repository 已过滤）。
     */
    fun newOpenItems(
        seenIds: Set<String>,
        items: List<SupervisorAttentionItem>,
    ): List<SupervisorAttentionItem> =
        items.filter { it.id !in seenIds }
            .sortedWith(compareBy({ priorityRank(it) }, { -it.stakes }))

    /** 新进入 failing 的 root（此前不在 failing 集合中），按路径排序保证确定性。 */
    fun newlyFailingRoots(
        previousFailing: Set<String>,
        currentFailing: Set<String>,
    ): List<String> = currentFailing.filter { it !in previousFailing }.sorted()

    /** errorsLastHourPeak 是否较上次快照“显著”上升（去抖：+max(10, 50%)）。
     *  峰值在 supervisor 进程生命周期内单调不降——朴素 current>previous 会在风暴爬升期
     *  每次轮询都推送（2026-10-09 实报刷屏）。显著上升才推送：+10 或 +50% 取大者。 */
    fun errorsPeakIncreased(previousPeak: Int, currentPeak: Int): Boolean =
        currentPeak >= previousPeak + maxOf(10, previousPeak / 2)
}
