package dev.leonardo.ocbeacon.data.repository

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
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SupervisorRepositoryImplTest {
    private val files: FileRepository = mockk()
    private val servers: ServerRepository = mockk()
    private val sessions: SessionApi = mockk()
    private val messages: MessageApi = mockk()
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `load returns glance metrics open items and recent decisions`() = runTest {
        val home = "/home/operator"
        coEvery { files.getServerPaths("server-1") } returns Result.success(ServerPaths(home = home))
        coEvery { files.getFileContent("server-1", home, "$home/.local/state/opencode-supervisor/status.json") } returns
            text("status.json", STATUS_JSON)
        coEvery { files.getFileContent("server-1", home, "$home/.local/state/opencode-supervisor/queue.json") } returns
            text("queue.json", QUEUE_JSON)
        coEvery { files.getFileContent("server-1", home, "$home/.local/state/opencode-supervisor/ledger.jsonl") } returns
            text("ledger.jsonl", LEDGER_JSONL)

        val result = SupervisorRepositoryImpl(files, servers, sessions, messages, json).load("server-1")

        assertTrue(result.isSuccess)
        val snapshot = result.getOrThrow()
        assertEquals(2, snapshot.rootsMonitored)
        assertEquals(1, snapshot.rootsFailing)
        assertEquals(4, snapshot.errorsPeak)
        assertEquals(listOf("att_open"), snapshot.attentionItems.map { it.id })
        assertEquals("oc-beacon", snapshot.attentionItems.single().project)
        assertEquals("/work/oc-beacon", snapshot.attentionItems.single().root)
        assertEquals(4, snapshot.attentionItems.single().stakes)
        assertEquals("ESCALATE", snapshot.recentDecisions.single().action)
        assertEquals("oc-beacon", snapshot.recentDecisions.single().project)
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

        val result = SupervisorRepositoryImpl(files, servers, sessions, messages, json)
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

        val result = SupervisorRepositoryImpl(files, servers, sessions, messages, json)
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

        SupervisorRepositoryImpl(files, servers, sessions, messages, json)
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
        assertTrue(!envelope.containsKey("index") && !envelope.containsKey("contextTag"))
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

        val result = SupervisorRepositoryImpl(files, servers, sessions, messages, json)
            .sendReply("server-1", root, BeaconReply.text("hello", explicitItemID = "att_open"))

        assertTrue(result.isFailure)
    }

    private fun inboxSession() = Session(
        id = "ses_inbox",
        title = "[Beacon replies] oc-beacon",
        time = Session.Time(created = 0L, updated = 0L),
    )

    private fun text(path: String, content: String): Result<FileContent> =
        Result.success(FileContent(path, ContentType.TEXT, content))

    private companion object {
        val STATUS_JSON = """
            {"lastReconcile":"2026-09-22T10:00:00Z","queueDepths":{"/work/oc-beacon":1,"/work/voice-bridge":0},
             "ticksByAction":{},"unknownOriginRate":0,"machineMarkedRate":1,"errorsLastHourPeak":4,
             "rootHealth":{"/work/oc-beacon":{"state":"ok","consecutiveFailures":0},
             "/work/voice-bridge":{"state":"failing","consecutiveFailures":2}}}
        """.trimIndent()

        val QUEUE_JSON = """
            {"schemaVersion":1,"items":[
              {"schemaVersion":1,"id":"att_open","version":1,"decisionKey":"a","kind":"decision",
               "origin":{"tickID":"tick_1","ledgerSeq":1,"decision":{"action":"ESCALATE","rationale":"Need operator","citations":[],"confidence":0.9},"citations":[],"informationNeeds":[],"contextDigest":"x"},
               "target":{"root":"/work/oc-beacon","sessionID":"ses_1","userMessageID":"msg_1"},"actionClass":"ESCALATE",
               "question":"Choose the release path","rationale":"The branch is blocked","priority":{"stakes":4,"urgency":3,"confidence":0.9,"freshness":1,"createdAt":"2026-09-22T09:00:00Z"},
               "premises":[],"relatedItemIDs":[],"lifecycle":[{"state":"proposed","at":"2026-09-22T09:00:00Z","actor":"tick"}],"poisonCount":0},
              {"schemaVersion":1,"id":"att_done","version":2,"decisionKey":"b","kind":"decision",
               "origin":{"tickID":"tick_2","ledgerSeq":2,"decision":{"action":"ACCEPT","rationale":"Done","citations":[],"confidence":1},"citations":[],"informationNeeds":[],"contextDigest":"y"},
               "target":{"root":"/work/voice-bridge","sessionID":"ses_2","userMessageID":"msg_2"},"actionClass":"ACCEPT",
               "question":"Old item","rationale":"Resolved","priority":{"stakes":1,"urgency":1,"confidence":1,"freshness":1,"createdAt":"2026-09-22T08:00:00Z"},
               "premises":[],"relatedItemIDs":[],"lifecycle":[{"state":"resolved","at":"2026-09-22T08:30:00Z","disposition":"propagated","evidence":[]}],"poisonCount":0}
            ],"surfaceLog":[],"digest":[]}
        """.trimIndent()

        val LEDGER_JSONL = """
            {"seq":1,"timestamp":"2026-09-22T09:30:00Z","type":"TICK_DECIDED","payload":{"root":"/work/oc-beacon","sessionID":"ses_1","messageID":"msg_1","decision":{"action":"ESCALATE","rationale":"Need operator input","citations":[],"confidence":0.9}},"prevHash":"GENESIS","hash":"ignored"}
            {"seq":2,"timestamp":"2026-09-22T09:31:00Z","type":"METRICS_SNAPSHOT","payload":{},"prevHash":"ignored","hash":"ignored"}
        """.trimIndent()
    }
}
