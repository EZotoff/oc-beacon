package dev.leonardo.ocbeacon.data.repository

import dev.leonardo.ocbeacon.data.adapter.ServerAdapterRegistry
import dev.leonardo.ocbeacon.data.adapter.ServerPorts
import dev.leonardo.ocbeacon.data.api.message.MessageApi
import dev.leonardo.ocbeacon.data.api.session.SessionApi
import dev.leonardo.ocbeacon.data.dto.request.PromptPart
import dev.leonardo.ocbeacon.domain.model.BeaconReply
import dev.leonardo.ocbeacon.domain.model.ContentType
import dev.leonardo.ocbeacon.domain.model.FileContent
import dev.leonardo.ocbeacon.domain.model.ServerPaths
import dev.leonardo.ocbeacon.domain.model.Session
import dev.leonardo.ocbeacon.domain.repository.FileRepository
import dev.leonardo.ocbeacon.domain.repository.ServerRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertTrue
import org.junit.Test

class SupervisorRepositoryImplTest {
    private val files: FileRepository = mockk()
    private val servers: ServerRepository = mockk()
    private val sessions: SessionApi = mockk()
    private val messages: MessageApi = mockk()
    private val adapters: ServerAdapterRegistry = mockk {
        every { ports(any()) } returns ServerPorts(sessions, messages, mockk())
    }
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `load builds items from operator-view single image without queue or ledger joins`() = runTest {
        val home = "/home/operator"
        coEvery { files.getServerPaths("server-1") } returns Result.success(ServerPaths(home = home))
        coEvery { files.getFileContent("server-1", home, "$home/.local/state/opencode-supervisor/status.json") } returns
            text("status.json", STATUS_JSON)
        coEvery { files.getFileContent("server-1", home, "$home/.local/state/opencode-supervisor/operator-view.json") } returns
            text("operator-view.json", operatorViewJson())
        coEvery { files.getFileContent("server-1", home, "$home/.local/state/opencode-supervisor/ledger.jsonl") } returns
            text("ledger.jsonl", LEDGER_JSONL)

        val result = SupervisorRepositoryImpl(files, servers, adapters, json).load("server-1")

        assertTrue(result.isSuccess)
        val snapshot = result.getOrThrow()
        assertEquals(2, snapshot.rootsMonitored)
        assertEquals(1, snapshot.rootsFailing)
        assertEquals(4, snapshot.errorsPeak)
        assertFalse(snapshot.stale)
        assertNull(snapshot.staleReason)
        assertEquals(listOf("att_open", "att_info"), snapshot.attentionItems.map { it.id })
        val first = snapshot.attentionItems.first()
        assertEquals("Choose the release path", first.question)
        assertEquals("oc-beacon", first.project)
        assertEquals("/work/oc-beacon", first.root)
        assertEquals("ESCALATE", first.actionClass)
        assertEquals("DECISION", first.escalationKind)
        assertEquals("B", first.severity)
        assertEquals(listOf("branch blocked", "tests red"), first.premiseTexts)
        assertTrue(first.createdAt.isNotBlank())
        assertEquals("ESCALATE", snapshot.recentDecisions.single().action)
        assertEquals("oc-beacon", snapshot.recentDecisions.single().project)
    }

    @Test
    fun `frozen image retains previous snapshot items with stale true`() = runTest {
        val home = "/home/operator"
        coEvery { files.getServerPaths("server-1") } returns Result.success(ServerPaths(home = home))
        coEvery { files.getFileContent("server-1", home, "$home/.local/state/opencode-supervisor/status.json") } returns
            text("status.json", STATUS_JSON)
        coEvery { files.getFileContent("server-1", home, "$home/.local/state/opencode-supervisor/ledger.jsonl") } returns
            text("ledger.jsonl", LEDGER_JSONL)
        coEvery { files.getFileContent("server-1", home, "$home/.local/state/opencode-supervisor/operator-view.json") } returnsMany
            listOf(
                text("operator-view.json", operatorViewJson()),
                text("operator-view.json", operatorViewJson(producedAtOffsetMs = -60_000)),
            )

        val repository = SupervisorRepositoryImpl(files, servers, adapters, json)
        val live = repository.load("server-1").getOrThrow()
        assertFalse(live.stale)

        val frozen = repository.load("server-1").getOrThrow()
        assertTrue(frozen.stale)
        assertEquals("stale", frozen.staleReason)
        assertEquals(live.attentionItems.map { it.id }, frozen.attentionItems.map { it.id })
    }

    @Test
    fun `invalid json freezes and never yields a live empty idle state`() = runTest {
        val home = "/home/operator"
        coEvery { files.getServerPaths("server-1") } returns Result.success(ServerPaths(home = home))
        coEvery { files.getFileContent("server-1", home, "$home/.local/state/opencode-supervisor/status.json") } returns
            text("status.json", STATUS_JSON)
        coEvery { files.getFileContent("server-1", home, "$home/.local/state/opencode-supervisor/ledger.jsonl") } returns
            text("ledger.jsonl", LEDGER_JSONL)
        coEvery { files.getFileContent("server-1", home, "$home/.local/state/opencode-supervisor/operator-view.json") } returns
            text("operator-view.json", "not json at all")

        val snapshot = SupervisorRepositoryImpl(files, servers, adapters, json).load("server-1").getOrThrow()

        assertTrue(snapshot.stale)
        assertEquals("invalid-schema", snapshot.staleReason)
    }

    @Test
    fun `twenty-card burst renders every card with stable ids and kind metadata`() = runTest {
        val home = "/home/operator"
        coEvery { files.getServerPaths("server-1") } returns Result.success(ServerPaths(home = home))
        coEvery { files.getFileContent("server-1", home, "$home/.local/state/opencode-supervisor/status.json") } returns
            text("status.json", STATUS_JSON)
        coEvery { files.getFileContent("server-1", home, "$home/.local/state/opencode-supervisor/ledger.jsonl") } returns
            text("ledger.jsonl", LEDGER_JSONL)
        coEvery { files.getFileContent("server-1", home, "$home/.local/state/opencode-supervisor/operator-view.json") } returns
            text("operator-view.json", operatorViewJson(cards = 20))

        val snapshot = SupervisorRepositoryImpl(files, servers, adapters, json).load("server-1").getOrThrow()

        assertFalse(snapshot.stale)
        assertEquals((1..19).map { "att_$it" } + "att_open", snapshot.attentionItems.map { it.id })
        // 混合 kind 全量映射：INFORMATION 只留在应用内，APPROVAL/DECISION 保留元数据供推送过滤。
        assertEquals(
            listOf("APPROVAL", "DECISION", "INFORMATION"),
            snapshot.attentionItems.take(3).map { it.escalationKind },
        )
        // 已有事项 id 不被 burst 位移（att_open 仍在末位，键稳定）。
        assertEquals("att_open", snapshot.attentionItems.last().id)
    }


    @Test
    fun `sendReply creates inbox session when absent and sends correlated reply`() = runTest {
        val root = "/work/oc-beacon"
        coEvery { servers.resolveConnection("server-1") } returns mockk()
        coEvery {
            sessions.listSessions(any(), directory = root, search = null, cursor = null, limit = 100)
        } returns emptyList()
        coEvery {
            sessions.createSession(any(), title = "[Beacon replies] oc-beacon", parentId = null, directory = root)
        } returns inboxSession()
        coEvery {
            messages.promptAsync(any(), sessionId = "ses_inbox", parts = any(), model = null, agent = null, variant = null, directory = root)
        } returns null

        val result = SupervisorRepositoryImpl(files, servers, adapters, json)
            .sendReply("server-1", root, BeaconReply.text("use Qdrant", explicitItemID = "att_open"))

        assertTrue(result.isSuccess)
        coVerify(exactly = 1) {
            sessions.createSession(any(), title = "[Beacon replies] oc-beacon", parentId = null, directory = root)
            messages.promptAsync(any(), sessionId = "ses_inbox", parts = any(), model = null, agent = null, variant = null, directory = root)
        }
    }

    @Test
    fun `sendReply reuses existing inbox session by exact title`() = runTest {
        val root = "/work/oc-beacon"
        val other = Session(id = "ses_other", title = "random", time = Session.Time(0L, 0L))
        coEvery { servers.resolveConnection("server-1") } returns mockk()
        coEvery {
            sessions.listSessions(any(), directory = root, search = null, cursor = null, limit = 100)
        } returns listOf(other, inboxSession())
        coEvery {
            messages.promptAsync(any(), sessionId = "ses_inbox", parts = any(), model = null, agent = null, variant = null, directory = root)
        } returns null

        val result = SupervisorRepositoryImpl(files, servers, adapters, json)
            .sendReply("server-1", root, BeaconReply.text("go ahead", explicitItemID = "att_open"))

        assertTrue(result.isSuccess)
        coVerify(exactly = 0) { sessions.createSession(any(), any(), any(), any()) }
        coVerify(exactly = 1) {
            messages.promptAsync(any(), sessionId = "ses_inbox", parts = any(), model = null, agent = null, variant = null, directory = root)
        }
    }

    @Test
    fun `sendReply message text is the v1 envelope with client uuid and explicit item id`() = runTest {
        val root = "/work/voice-bridge"
        coEvery { servers.resolveConnection("server-1") } returns mockk()
        coEvery {
            sessions.listSessions(any(), directory = root, search = null, cursor = null, limit = 100)
        } returns emptyList()
        coEvery {
            sessions.createSession(any(), title = "[Beacon replies] voice-bridge", parentId = null, directory = root)
        } returns inboxSession()
        coEvery {
            messages.promptAsync(any(), sessionId = "ses_inbox", parts = any(), model = null, agent = null, variant = null, directory = root)
        } returns null

        SupervisorRepositoryImpl(files, servers, adapters, json)
            .sendReply("server-1", root, BeaconReply.text("skip", explicitItemID = "att_x"))

        val partSlot = slot<List<PromptPart>>()
        coVerify {
            messages.promptAsync(any(), sessionId = "ses_inbox", parts = capture(partSlot), model = null, agent = null, variant = null, directory = root)
        }
        val envelope = Json.parseToJsonElement(partSlot.captured.single().text!!).jsonObject
        assertEquals(1, envelope["v"]!!.jsonPrimitive.content.toInt())
        assertTrue(envelope.containsKey("clientMessageID"))
        assertEquals("text", envelope["kind"]!!.jsonPrimitive.content)
        assertEquals("skip", envelope["text"]!!.jsonPrimitive.content)
        assertEquals("att_x", envelope["explicitItemID"]!!.jsonPrimitive.content)
        assertTrue(envelope.containsKey("note"))
        assertTrue(!envelope.containsKey("index") && !envelope.containsKey("contextTag"))
        assertTrue(!envelope.containsKey("question") && !envelope.containsKey("sessionTitle"))
    }

    @Test
    fun `sendReply propagates send failure`() = runTest {
        val root = "/work/oc-beacon"
        coEvery { servers.resolveConnection("server-1") } returns mockk()
        coEvery {
            sessions.listSessions(any(), directory = root, search = null, cursor = null, limit = 100)
        } returns emptyList()
        coEvery {
            sessions.createSession(any(), title = "[Beacon replies] oc-beacon", parentId = null, directory = root)
        } returns inboxSession()
        coEvery {
            messages.promptAsync(any(), sessionId = "ses_inbox", parts = any(), model = null, agent = null, variant = null, directory = root)
        } throws java.io.IOException("offline")

        val result = SupervisorRepositoryImpl(files, servers, adapters, json)
            .sendReply("server-1", root, BeaconReply.text("hello", explicitItemID = "att_open"))

        assertTrue(result.isFailure)
    }

    private fun inboxSession() = Session(
        id = "ses_inbox",
        title = "[Beacon replies] oc-beacon",
        time = Session.Time(created = 0L, updated = 0L),
    )

    private fun operatorViewJson(producedAtOffsetMs: Long = 0L, cards: Int = 0): String {
        val producedAt = java.time.Instant.now().plusMillis(producedAtOffsetMs).toString()
        val body = if (cards > 0) {
            // burst 形状：cards-1 张混合 kind 卡 + 末位固定的既有 ESCALATE/DECISION 卡。
            val kinds = listOf("APPROVAL" to "ESCALATE", "DECISION" to "ESCALATE", "INFORMATION" to "CONTINUE")
            (1 until cards).joinToString(",") { i ->
                val (kind, action) = kinds[(i - 1) % 3]
                """{"id":"att_$i","rootLabel":"proj-$i","root":"/work/p$i","sessionLabel":"ses_$i",
                 "reasonText":"burst reason $i","premiseTexts":[],
                 "ageSeconds":$i,"severity":"C","jumpAvailable":true,"actionClass":"$action","escalationKind":"$kind"}"""
            } +
                """,{"id":"att_open","rootLabel":"oc-beacon","root":"/work/oc-beacon","sessionLabel":"ses_1",
                 "reasonText":"Choose the release path","premiseTexts":["branch blocked","tests red"],
                 "ageSeconds":600,"severity":"B","jumpAvailable":true,"actionClass":"ESCALATE","escalationKind":"DECISION"}"""
        } else {
            """{"id":"att_open","rootLabel":"oc-beacon","root":"/work/oc-beacon","sessionLabel":"ses_1",
             "reasonText":"Choose the release path","premiseTexts":["branch blocked","tests red"],
             "ageSeconds":600,"severity":"B","jumpAvailable":true,"actionClass":"ESCALATE","escalationKind":"DECISION"},
            {"id":"att_info","rootLabel":"voice-bridge","root":"/work/voice-bridge","sessionLabel":"ses_2",
             "reasonText":"FYI only","premiseTexts":[],
             "ageSeconds":120,"severity":"D","jumpAvailable":true,"actionClass":"CONTINUE","escalationKind":"INFORMATION"}"""
        }
        return """{"schemaVersion":1,"generation":1,"lastSeq":7,"producedAt":"$producedAt","cards":[$body]}""".trimIndent()
    }

    private fun text(path: String, content: String): Result<FileContent> =
        Result.success(FileContent(path, ContentType.TEXT, content))

    private companion object {
        val STATUS_JSON = """
            {"lastReconcile":"2026-09-22T10:00:00Z","queueDepths":{"/work/oc-beacon":1,"/work/voice-bridge":0},
             "ticksByAction":{},"unknownOriginRate":0,"machineMarkedRate":1,"errorsLastHourPeak":4,
             "rootHealth":{"/work/oc-beacon":{"state":"ok","consecutiveFailures":0},
             "/work/voice-bridge":{"state":"failing","consecutiveFailures":2}}}
        """.trimIndent()

        val LEDGER_JSONL = """
            {"seq":1,"timestamp":"2026-09-22T09:30:00Z","type":"TICK_DECIDED","payload":{"root":"/work/oc-beacon","sessionID":"ses_1","messageID":"msg_1","decision":{"action":"ESCALATE","rationale":"Need operator input","citations":[],"confidence":0.9}},"prevHash":"GENESIS","hash":"ignored"}
            {"seq":2,"timestamp":"2026-09-22T09:31:00Z","type":"METRICS_SNAPSHOT","payload":{},"prevHash":"ignored","hash":"ignored"}
        """.trimIndent()
    }
}
