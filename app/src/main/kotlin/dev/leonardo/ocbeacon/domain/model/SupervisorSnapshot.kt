package dev.leonardo.ocbeacon.domain.model

data class SupervisorSnapshot(
    val rootsMonitored: Int,
    val rootsFailing: Int,
    val errorsPeak: Int,
    val attentionItems: List<SupervisorAttentionItem>,
    val recentDecisions: List<SupervisorDecision>,
    /** 处于 failing 状态的 root 路径（后台轮询 diff 用；摘要屏只展示计数）。 */
    val failingRoots: List<String> = emptyList(),
)

data class SupervisorAttentionItem(
    val id: String,
    val question: String,
    val project: String,
    val createdAt: String,
    val stakes: Int,
    /** 队列项动作类别（ESCALATE/CONTINUE/STEER…）——通知优先级排序用。 */
    val actionClass: String = "",
    /** 升级类别（DECISION/INFORMATION/APPROVAL）——ESCALATE(APPROVAL) 最高优先级。 */
    val escalationKind: String = "",
)

data class SupervisorDecision(
    val action: String,
    val project: String,
    val rationale: String,
    val decidedAt: String,
)
