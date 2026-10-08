package dev.leonardo.ocbeacon.data.api.voice

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Voice JSON wire fixtures; binary PCM belongs to the WebSocket transport. */
class VoiceFramesTest {
    private fun client(wire: String, expected: ClientControlFrame) {
        assertEquals(expected, VoiceFrames.decodeClientFrame(wire))
        assertEquals(Json.parseToJsonElement(wire), Json.parseToJsonElement(VoiceFrames.encodeClientFrame(expected)))
        assertEquals(expected, VoiceFrames.decodeClientFrame(VoiceFrames.encodeClientFrame(expected)))
    }

    private fun server(wire: String, expected: ServerControlFrame) {
        assertEquals(expected, VoiceFrames.decodeServerFrame(wire))
        assertEquals(Json.parseToJsonElement(wire), Json.parseToJsonElement(VoiceFrames.encodeServerFrame(expected)))
        assertEquals(expected, VoiceFrames.decodeServerFrame(VoiceFrames.encodeServerFrame(expected)))
    }

    private fun payload(wire: String) = Json.parseToJsonElement(wire) as JsonObject

    @Test
    fun `input complete round trips`() = client("""{"type":"inputComplete"}""", ClientControlFrame.InputComplete)

    @Test
    fun `text round trips with escaped content`() =
        client("""{"type":"text","text":"hello\n\"voice\""}""", ClientControlFrame.Text("hello\n\"voice\""))

    @Test
    fun `selection passes stale tag unchanged`() =
        client("""{"type":"selection","contextTag":"ctx-1","index":2}""", ClientControlFrame.Selection("ctx-1", 2))

    @Test
    fun `full view context round trips`() = client(
        """{"type":"view-context","view":"chat","project":{"id":"p","name":"Project"},
            "session":{"id":"s","title":"Session","state":"running"},
            "selection":{"kind":"row","id":"r","label":"Row"},
            "recent":[{"projectId":"p","sessionId":"s"}]}""",
        ClientControlFrame.ViewContext(
            ViewContextView.CHAT, VoiceProject("p", "Project"),
            VoiceSession("s", "Session", VoiceSessionState.RUNNING),
            VoiceSelection(SelectionKind.ROW, "r", "Row"), listOf(RecentEntry("p", "s")),
        ),
    )

    @Test
    fun `all nine context views accept exact wire values`() {
        val views = listOf("home", "project", "session", "comparison", "attention", "sessions", "workspace", "chat", "supervisor")
        assertEquals(9, ViewContextView.entries.size)
        views.zip(ViewContextView.entries).forEach { (wire, view) ->
            client("""{"type":"view-context","view":"$wire"}""", ClientControlFrame.ViewContext(view))
        }
    }

    @Test
    fun `all connection states round trip`() {
        listOf("idle", "connecting", "connected", "reconnecting", "error", "closed")
            .zip(ConnectionState.entries).forEach { (wire, state) ->
                server("""{"type":"state","state":"$wire"}""", ServerControlFrame.State(state))
            }
    }

    @Test
    fun `missed audio mirrors finite number semantics`() =
        server("""{"type":"missed-audio","dropped":2.5}""", ServerControlFrame.MissedAudio(2.5))

    @Test
    fun `transcript roles round trip`() {
        listOf("user", "assistant").zip(TranscriptRole.entries).forEach { (wire, role) ->
            server("""{"type":"transcript","role":"$wire","text":"hello"}""", ServerControlFrame.Transcript(role, "hello"))
        }
    }

    @Test
    fun `confirmation pending round trips`() {
        listOf(true, false).forEach {
            server("""{"type":"confirmation-pending","pending":$it}""", ServerControlFrame.ConfirmationPending(it))
        }
    }

    @Test
    fun `interrupt round trips`() =
        server("""{"type":"interrupt","reason":"barge-in"}""", ServerControlFrame.Interrupt("barge-in"))

    @Test
    fun `error round trips`() =
        server("""{"type":"error","message":"offline"}""", ServerControlFrame.Error("offline"))

    @Test
    fun `handoff round trips`() = server("""{"type":"handoff"}""", ServerControlFrame.Handoff)

    @Test
    fun `all seven show views preserve their open payloads`() {
        val payloads = listOf(
            """{"body":"card"}""", """{"items":["one",{"label":"two"}]}""",
            """{"columns":["Name"],"rows":[["row"]]}""", """{"options":[{"label":"yes"}]}""",
            """{"current":1,"total":3}""", """{"left":{"x":1},"right":{"x":2}}""",
            """{"before":"old","after":"new","future":null}""",
        )
        ShowView.Known.entries.zip(payloads).forEach { (view, wirePayload) ->
            server(
                """{"type":"show","view":"${view.wireValue}","title":"Title","contextTag":"ctx-9","payload":$wirePayload}""",
                ShowFrame(view, "Title", "ctx-9", payload(wirePayload)),
            )
        }
    }

    @Test
    fun `unknown show view becomes fallback and round trips`() {
        val wire = """{"type":"show","view":"chart","title":"Future","contextTag":"ctx-1","payload":{"series":[1,2]}}"""
        val decoded = VoiceFrames.decodeServerFrame(wire) as ShowFrame
        assertTrue(decoded.view is ShowView.UnknownView)
        server(wire, ShowFrame(ShowView.UnknownView("chart"), "Future", "ctx-1", payload("""{"series":[1,2]}""")))
    }

    @Test
    fun `unknown server type is ignored without throwing`() {
        assertNull(VoiceFrames.decodeServerFrame("""{"type":"future-frame","payload":{"x":1}}"""))
    }

    @Test
    fun `unknown keys are ignored at every level`() {
        assertEquals(ServerControlFrame.Handoff, VoiceFrames.decodeServerFrame("""{"type":"handoff","future":true}"""))
        assertEquals(
            ClientControlFrame.ViewContext(ViewContextView.HOME, VoiceProject("p", "P")),
            VoiceFrames.decodeClientFrame("""{"type":"view-context","view":"home","future":1,"project":{"id":"p","name":"P","future":2}}"""),
        )
        assertEquals(
            ShowFrame(ShowView.Known.CARD, "T", "ctx-1", payload("""{"future":true}""")),
            VoiceFrames.decodeServerFrame("""{"type":"show","view":"card","title":"T","contextTag":"ctx-1","payload":{"future":true},"future":1}"""),
        )
    }

    @Test
    fun `malformed frames and non text JSON are rejected`() {
        listOf("not JSON", "[]", "null", "42", "{}", """{"type":1}""").forEach {
            assertNull(VoiceFrames.decodeClientFrame(it))
            assertNull(VoiceFrames.decodeServerFrame(it))
        }
        listOf(
            """{"type":"transcript","role":"system","text":"x"}""",
            """{"type":"state","state":"future"}""",
            """{"type":"error"}""",
            """{"type":"show","view":"card","title":"T","contextTag":"ctx-1","payload":[]}""",
            """{"type":"show","view":"card","title":"T","contextTag":"bad","payload":{}}""",
        ).forEach { assertNull(VoiceFrames.decodeServerFrame(it)) }
    }

    @Test
    fun `invalid selections and oversized recent lists are rejected`() {
        listOf(
            """{"type":"selection","contextTag":"bad","index":0}""",
            """{"type":"selection","contextTag":"ctx-1","index":-1}""",
            """{"type":"selection","contextTag":"ctx-1","index":0.5}""",
            """{"type":"view-context","view":"invalid"}""",
        ).forEach { assertNull(VoiceFrames.decodeClientFrame(it)) }
        val recent = List(6) { """{"projectId":"p","sessionId":"s"}""" }.joinToString(",")
        assertNull(VoiceFrames.decodeClientFrame("""{"type":"view-context","view":"home","recent":[$recent]}"""))
    }
}
