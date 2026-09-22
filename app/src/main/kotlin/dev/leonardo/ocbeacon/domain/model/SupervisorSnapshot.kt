package dev.leonardo.ocbeacon.domain.model

data class SupervisorSnapshot(
    val rootsMonitored: Int,
    val rootsFailing: Int,
    val errorsPeak: Int,
    val attentionItems: List<SupervisorAttentionItem>,
    val recentDecisions: List<SupervisorDecision>,
)

data class SupervisorAttentionItem(
    val id: String,
    val question: String,
    val project: String,
    val createdAt: String,
    val stakes: Int,
)

data class SupervisorDecision(
    val action: String,
    val project: String,
    val rationale: String,
    val decidedAt: String,
)
