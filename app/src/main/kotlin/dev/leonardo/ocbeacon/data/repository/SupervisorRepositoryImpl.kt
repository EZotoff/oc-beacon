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
import dev.leonardo.ocbeacon.domain.supervisor.OperatorViewCardDto
import dev.leonardo.ocbeacon.domain.supervisor.OperatorViewFreshness
import dev.leonardo.ocbeacon.domain.supervisor.OperatorViewReadOutcome
import dev.leonardo.ocbeacon.logging.AppLogger
import dev.leonardo.ocbeacon.util.PathUtils
import dev.leonardo.ocbeacon.util.UuidV7
import dev.leonardo.ocbeacon.util.runCatchingCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
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

    /** 每服务器一个契约新鲜度评估器（保留 lastGood/单调锚点跨 load 调用）。 */
    private val freshness = mutableMapOf<String, OperatorViewFreshness>()

    override suspend fun load(serverId: String): Result<SupervisorSnapshot> = runCatchingCancellable {
        val home = files.getServerPaths(serverId).getOrThrow().home
        val stateDirectory = "$home/.local/state/opencode-supervisor"
        val status = json.decodeFromString<StatusDto>(
            files.getFileContent(serverId, home, "$stateDirectory/status.json").getOrThrow().content,
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

        // 注意事项只来自 operator-view.json 单镜像（契约「Operator read model」）：
        // 不再 join queue.json / ledger——那些是 supervisor 内部状态，不是操作员视图。
        val evaluator = freshness.getOrPut(serverId) { OperatorViewFreshness() }
        val raw = files.getFileContent(serverId, home, "$stateDirectory/operator-view.json")
            .getOrNull()
            ?.content
        val outcome = if (raw == null) evaluator.readError() else evaluator.read(raw)
        if (outcome is OperatorViewReadOutcome.Frozen && BuildConfig.DEBUG) {
            AppLogger.d(TAG, "operator-view frozen: reason=${outcome.reason} hasLastGood=${outcome.lastGood != null}")
        }
        val live = outcome is OperatorViewReadOutcome.Live
        val view = when (outcome) {
            is OperatorViewReadOutcome.Live -> outcome.view
            is OperatorViewReadOutcome.Frozen -> outcome.lastGood
        }

        SupervisorSnapshot(
            rootsMonitored = status.rootHealth.size,
            rootsFailing = status.rootHealth.values.count { it.state == "failing" },
            errorsPeak = status.errorsLastHourPeak,
            attentionItems = view?.cards.orEmpty().map { it.toAttentionItem() },
            recentDecisions = decisions,
            failingRoots = status.rootHealth
                .filterValues { it.state == "failing" }
                .keys
                .toList(),
            stale = !live,
            staleReason = if (live) null else (outcome as OperatorViewReadOutcome.Frozen).reason.name.lowercase().replace('_', '-'),
        )
    }

    /** LIVE 卡 → 注意事项项；root 缺省（增补前发布端）时失败安全为空串——空 root 回复守卫自然 no-op。 */
    private fun OperatorViewCardDto.toAttentionItem(): SupervisorAttentionItem =
        SupervisorAttentionItem(
            id = id,
            question = reasonText,
            project = rootLabel.ifBlank { projectName(root.orEmpty()) },
            createdAt = Instant.now().minusSeconds(ageSeconds.coerceAtLeast(0L)).toString(),
            actionClass = actionClass.orEmpty(),
            escalationKind = escalationKind.orEmpty(),
            root = root.orEmpty(),
            reasonText = reasonText,
            premiseTexts = premiseTexts,
            severity = severity,
        )

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
