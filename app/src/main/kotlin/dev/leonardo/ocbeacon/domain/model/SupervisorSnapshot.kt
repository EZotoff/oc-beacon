package dev.leonardo.ocbeacon.domain.model

data class SupervisorSnapshot(
    val rootsMonitored: Int,
    val rootsFailing: Int,
    val errorsPeak: Int,
    val attentionItems: List<SupervisorAttentionItem>,
    val recentDecisions: List<SupervisorDecision>,
    /** 处于 failing 状态的 root 路径（后台轮询 diff 用；摘要屏只展示计数）。 */
    val failingRoots: List<String> = emptyList(),
    /** operator-view 镜像冻结（stale/invalid/clock-jump…）：卡片灰显、禁跳转、禁回复。 */
    val stale: Boolean = false,
    /** 冻结原因（FreezeReason 小写形式），live 时为 null。 */
    val staleReason: String? = null,
)

data class SupervisorAttentionItem(
    val id: String,
    val question: String,
    val project: String,
    val createdAt: String,
    val stakes: Int = 0,
    /** 队列项动作类别（ESCALATE/CONTINUE/STEER…）——通知优先级排序用。 */
    val actionClass: String = "",
    /** 升级类别（DECISION/INFORMATION/APPROVAL）——ESCALATE(APPROVAL) 最高优先级。 */
    val escalationKind: String = "",
    /** 完整 root 路径——Seam 4 回复信封按 root 寻址 per-root 收件箱会话。 */
    val root: String = "",
    /** 卡片 reasonText 原文（question 的来源；Detail 页标注为可能有截断的摘要）。 */
    val reasonText: String = "",
    /** 前提列表（Detail「Why this came up」节）。 */
    val premiseTexts: List<String> = emptyList(),
    /** 契约频段 A/B/C/D（A 最紧急）。 */
    val severity: String = "",
)

data class SupervisorDecision(
    val action: String,
    val project: String,
    val rationale: String,
    val decidedAt: String,
)
