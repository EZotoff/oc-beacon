package dev.leonardo.ocbeacon.data.repository

import dev.leonardo.ocbeacon.domain.model.ContentType
import dev.leonardo.ocbeacon.domain.model.FileContent
import dev.leonardo.ocbeacon.domain.model.ServerPaths
import dev.leonardo.ocbeacon.domain.repository.FileRepository
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SupervisorRepositoryImplTest {
    private val files: FileRepository = mockk()
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

        val result = SupervisorRepositoryImpl(files, json).load("server-1")

        assertTrue(result.isSuccess)
        val snapshot = result.getOrThrow()
        assertEquals(2, snapshot.rootsMonitored)
        assertEquals(1, snapshot.rootsFailing)
        assertEquals(4, snapshot.errorsPeak)
        assertEquals(listOf("att_open"), snapshot.attentionItems.map { it.id })
        assertEquals("oc-beacon", snapshot.attentionItems.single().project)
        assertEquals(4, snapshot.attentionItems.single().stakes)
        assertEquals("ESCALATE", snapshot.recentDecisions.single().action)
        assertEquals("oc-beacon", snapshot.recentDecisions.single().project)
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
