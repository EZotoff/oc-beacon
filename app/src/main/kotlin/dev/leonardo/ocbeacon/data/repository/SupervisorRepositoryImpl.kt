package dev.leonardo.ocbeacon.data.repository

import dev.leonardo.ocbeacon.domain.model.SupervisorAttentionItem
import dev.leonardo.ocbeacon.domain.model.SupervisorDecision
import dev.leonardo.ocbeacon.domain.model.SupervisorSnapshot
import dev.leonardo.ocbeacon.domain.repository.FileRepository
import dev.leonardo.ocbeacon.domain.repository.SupervisorRepository
import dev.leonardo.ocbeacon.util.PathUtils
import dev.leonardo.ocbeacon.util.runCatchingCancellable
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SupervisorRepositoryImpl @Inject constructor(
    private val files: FileRepository,
    private val json: Json,
) : SupervisorRepository {
    override suspend fun load(serverId: String): Result<SupervisorSnapshot> = runCatchingCancellable {
        val home = files.getServerPaths(serverId).getOrThrow().home
        val stateDirectory = "$home/.local/state/opencode-supervisor"
        val status = json.decodeFromString<StatusDto>(
            files.getFileContent(serverId, home, "$stateDirectory/status.json").getOrThrow().content,
        )
        val queue = json.decodeFromString<QueueDto>(
            files.getFileContent(serverId, home, "$stateDirectory/queue.json").getOrThrow().content,
        )
        val decisions = files.getFileContent(serverId, home, "$stateDirectory/ledger.jsonl")
            .getOrThrow()
            .content
            .lineSequence()
            .filter(String::isNotBlank)
            .mapNotNull { line ->
                val record = json.decodeFromString<LedgerRecordDto>(line)
                if (record.type == "TICK_DECIDED") record.toDecision() else null
            }
            .toList()
            .takeLast(20)
            .reversed()

        SupervisorSnapshot(
            rootsMonitored = status.rootHealth.size,
            rootsFailing = status.rootHealth.values.count { it.state == "failing" },
            errorsPeak = status.errorsLastHourPeak,
            attentionItems = queue.items
                .filter { it.lifecycle.lastOrNull()?.state != "resolved" }
                .sortedByDescending { it.priority.stakes }
                .map { item ->
                    SupervisorAttentionItem(
                        id = item.id,
                        question = item.question,
                        project = projectName(item.target.root),
                        createdAt = item.priority.createdAt,
                        stakes = item.priority.stakes,
                        actionClass = item.actionClass,
                        escalationKind = item.escalationKind.orEmpty(),
                    )
                },
            recentDecisions = decisions,
            failingRoots = status.rootHealth
                .filterValues { it.state == "failing" }
                .keys
                .toList(),
        )
    }

    private fun LedgerRecordDto.toDecision(): SupervisorDecision? {
        val decision = payload.decision ?: return null
        return SupervisorDecision(
            action = decision.action,
            project = payload.root?.let(::projectName).orEmpty(),
            rationale = decision.rationale,
            decidedAt = timestamp,
        )
    }

    private fun projectName(root: String): String = PathUtils.fileName(root).ifBlank { root }

    @Serializable
    private data class StatusDto(
        val errorsLastHourPeak: Int = 0,
        val rootHealth: Map<String, RootHealthDto> = emptyMap(),
    )

    @Serializable
    private data class RootHealthDto(val state: String = "unknown")

    @Serializable
    private data class QueueDto(val items: List<QueueItemDto> = emptyList())

    @Serializable
    private data class QueueItemDto(
        val id: String,
        val question: String,
        val target: TargetDto,
        val priority: PriorityDto,
        val lifecycle: List<LifecycleDto> = emptyList(),
        val actionClass: String = "",
        val escalationKind: String? = null,
    )

    @Serializable
    private data class TargetDto(val root: String)

    @Serializable
    private data class PriorityDto(
        val stakes: Int,
        val createdAt: String,
    )

    @Serializable
    private data class LifecycleDto(val state: String)

    @Serializable
    private data class LedgerRecordDto(
        val timestamp: String,
        val type: String,
        val payload: LedgerPayloadDto = LedgerPayloadDto(),
    )

    @Serializable
    private data class LedgerPayloadDto(
        val root: String? = null,
        val decision: DecisionDto? = null,
    )

    @Serializable
    private data class DecisionDto(
        val action: String,
        val rationale: String,
    )
}
