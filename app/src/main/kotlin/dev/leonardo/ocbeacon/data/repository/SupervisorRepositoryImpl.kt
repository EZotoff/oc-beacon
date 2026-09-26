package dev.leonardo.ocbeacon.data.repository

import dev.leonardo.ocbeacon.BuildConfig
import dev.leonardo.ocbeacon.data.api.message.MessageApi
import dev.leonardo.ocbeacon.data.api.session.SessionApi
import dev.leonardo.ocbeacon.data.dto.request.PromptPart
import dev.leonardo.ocbeacon.domain.model.BeaconReply
import dev.leonardo.ocbeacon.domain.model.SupervisorAttentionItem
import dev.leonardo.ocbeacon.domain.model.SupervisorDecision
import dev.leonardo.ocbeacon.domain.model.SupervisorSnapshot
import dev.leonardo.ocbeacon.domain.repository.FileRepository
import dev.leonardo.ocbeacon.domain.repository.ServerRepository
import dev.leonardo.ocbeacon.domain.repository.SupervisorRepository
import dev.leonardo.ocbeacon.logging.AppLogger
import dev.leonardo.ocbeacon.util.PathUtils
import dev.leonardo.ocbeacon.util.UuidV7
import dev.leonardo.ocbeacon.util.runCatchingCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SupervisorRepositoryImpl @Inject constructor(
    private val files: FileRepository,
    private val servers: ServerRepository,
    private val sessions: SessionApi,
    private val messages: MessageApi,
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
                        root = item.target.root,
                    )
                },
            recentDecisions = decisions,
            failingRoots = status.rootHealth
                .filterValues { it.state == "failing" }
                .keys
                .toList(),
        )
    }

    override suspend fun sendReply(serverId: String, root: String, reply: BeaconReply): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatchingCancellable {
                require(root.isNotBlank()) { "reply target root is blank" }
                val conn = servers.resolveConnection(serverId)
                val inboxTitle = inboxSessionTitle(root)
                // 收件箱会话按标题精确匹配；未命中则创建（重复创建对 supervisor 无害——
                // 它按标题约定匹配收件箱，不依赖单一会话实例）。
                val inbox = sessions.listSessions(conn, directory = root, search = null, cursor = null, limit = 100)
                    .firstOrNull { it.title == inboxTitle }
                    ?: sessions.createSession(conn, title = inboxTitle, parentId = null, directory = root)
                val envelope = reply.envelopeJson(UuidV7.generate())
                messages.promptAsync(
                    conn,
                    sessionId = inbox.id,
                    parts = listOf(PromptPart(type = "text", text = envelope)),
                    directory = root,
                )
                if (BuildConfig.DEBUG) {
                    AppLogger.d(TAG, "beacon reply -> $inboxTitle (${inbox.id})")
                }
            }
        }

    private fun inboxSessionTitle(root: String): String =
        "${INBOX_TITLE_PREFIX}${PathUtils.fileName(root).ifBlank { root }}"

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

    private companion object {
        /** 标题约定：supervisor 以此前缀识别 beacon 收件箱（禁用「[Supervisor]」）。 */
        const val INBOX_TITLE_PREFIX = "[Beacon replies] "
        const val TAG = "SupervisorRepository"
    }
}
