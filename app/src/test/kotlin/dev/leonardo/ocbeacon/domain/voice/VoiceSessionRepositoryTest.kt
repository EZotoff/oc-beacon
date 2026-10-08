package dev.leonardo.ocbeacon.domain.voice

import dev.leonardo.ocbeacon.data.api.voice.*
import dev.leonardo.ocbeacon.data.voice.*
import dev.leonardo.ocbeacon.domain.model.SupervisorAttentionItem
import dev.leonardo.ocbeacon.domain.model.SupervisorSnapshot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.launch
import okio.ByteString.Companion.toByteString
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceSessionRepositoryTest {
    private class Fixture(scope: TestScope, snapshot: SupervisorSnapshot? = null) {
        val socket = FakeVoiceSocket()
        val client = VoiceWsClient(scope.backgroundScope, socket)
        val recorder = FakeVoiceRecorder()
        val player = FakeVoicePlayer()
        val focus = FakeVoiceFocus()
        val audio = VoiceAudioEngine(recorder, player, focus, scope.backgroundScope, client::sendAudio)
        val repository = VoiceSessionRepository(client, audio, scope.backgroundScope) { snapshot }
    }

    @Test fun `state transitions include live disconnect and terminal moved surface`() = runTest {
        val f = Fixture(this)
        assertEquals(VoiceSessionConnection.Disconnected, f.repository.state.value)
        f.repository.connect("https://pulse.example"); runCurrent()
        assertEquals(VoiceSessionConnection.Connecting, f.repository.state.value)
        f.socket.opened(); runCurrent()
        assertEquals(VoiceSessionConnection.Live, f.repository.state.value)
        f.socket.text(ServerControlFrame.Handoff); runCurrent()
        assertEquals(VoiceSessionConnection.MovedToAnotherSurface, f.repository.state.value)
        advanceTimeBy(30_000); runCurrent()
        assertEquals(1, f.socket.requests.size)
        f.repository.disconnect(); runCurrent()
        assertEquals(VoiceSessionConnection.Disconnected, f.repository.state.value)
    }

    @Test fun `PTT after handoff explicitly reconnects and starts capture only when live`() = runTest {
        val f = Fixture(this)
        f.repository.connect("https://pulse.example")
        f.repository.pressPtt(); runCurrent()
        assertEquals(0, f.recorder.starts)
        f.socket.opened(); runCurrent()
        assertEquals(1, f.recorder.starts)
        f.repository.releasePtt()
        assertEquals(ClientControlFrame.InputComplete, VoiceFrames.decodeClientFrame(f.socket.texts.last()))
        f.socket.text(ServerControlFrame.Handoff); runCurrent()
        f.repository.pressPtt(); runCurrent()
        assertEquals(2, f.socket.requests.size)
        f.socket.opened(); runCurrent()
        assertEquals(2, f.recorder.starts)
    }

    @Test fun `view context deduplicates and emits only changed live context`() = runTest {
        val f = Fixture(this)
        f.repository.connect("https://pulse.example")
        f.socket.opened(); runCurrent()
        assertTrue(f.repository.emitViewContext(ViewContextView.SUPERVISOR))
        assertFalse(f.repository.emitViewContext(ViewContextView.SUPERVISOR))
        assertTrue(f.repository.emitViewContext(ViewContextView.CHAT))
        assertEquals(2, f.socket.texts.size)
        val context = VoiceFrames.decodeClientFrame(f.socket.texts.first()) as ClientControlFrame.ViewContext
        assertNotNull(context.project)
        assertNotNull(context.session)
        assertEquals(emptyList<RecentEntry>(), context.recent)
    }

    @Test fun `view context is suppressed outside live and resent after reconnect`() = runTest {
        val f = Fixture(this)
        assertFalse(f.repository.emitViewContext(ViewContextView.HOME))
        f.repository.connect("https://pulse.example")
        assertFalse(f.repository.emitViewContext(ViewContextView.HOME))
        f.socket.opened(); runCurrent()
        assertTrue(f.repository.emitViewContext(ViewContextView.HOME))
        f.socket.text(ServerControlFrame.Handoff); runCurrent()
        assertFalse(f.repository.emitViewContext(ViewContextView.HOME))
        f.repository.connect("https://pulse.example")
        f.socket.opened(); runCurrent()
        assertTrue(f.repository.emitViewContext(ViewContextView.HOME))
        assertEquals(2, f.socket.texts.size)
    }

    @Test fun `selection maps context tag and index and suppresses offline send`() = runTest {
        val f = Fixture(this)
        assertFalse(f.repository.sendSelection("ctx-42", 3))
        f.repository.connect("https://pulse.example")
        f.socket.opened(); runCurrent()
        assertTrue(f.repository.sendSelection("ctx-42", 3))
        assertEquals(ClientControlFrame.Selection("ctx-42", 3), VoiceFrames.decodeClientFrame(f.socket.texts.single()))
    }

    @Test fun `snapshot supplies real project and session label and explicit context wins`() = runTest {
        val snapshot = SupervisorSnapshot(1, 0, 0, listOf(
            SupervisorAttentionItem("attention-1", "choose", "Beacon", "now",
                root = "/workspace/beacon", sessionLabel = "Walking test")), emptyList())
        val f = Fixture(this, snapshot)
        f.repository.connect("https://pulse.example")
        f.socket.opened(); runCurrent()
        f.repository.emitViewContext(ViewContextView.SUPERVISOR)
        val context = VoiceFrames.decodeClientFrame(f.socket.texts.last()) as ClientControlFrame.ViewContext
        assertEquals(VoiceProject("/workspace/beacon", "Beacon"), context.project)
        assertEquals("Walking test", context.session?.title)
        val project = VoiceProject("p1", "Explicit")
        val session = VoiceSession("s1", "Active", VoiceSessionState.RUNNING)
        val selection = VoiceSelection(SelectionKind.ROW, "r1", "Row")
        f.repository.emitViewContext(ViewContextView.CHAT, project, session, selection)
        val explicit = VoiceFrames.decodeClientFrame(f.socket.texts.last()) as ClientControlFrame.ViewContext
        assertEquals(project, explicit.project)
        assertEquals(session, explicit.session)
        assertEquals(selection, explicit.selection)
    }

    @Test fun `release before handshake does not start capture and disconnect stops audio`() = runTest {
        val f = Fixture(this)
        f.repository.connect("https://pulse.example")
        f.repository.pressPtt(); f.repository.releasePtt()
        f.socket.opened(); runCurrent()
        assertEquals(0, f.recorder.starts)
        f.repository.pressPtt()
        f.repository.disconnect(); runCurrent()
        assertEquals(1, f.recorder.stops)
        assertEquals(1, f.player.stops)
        assertTrue(f.socket.texts.isEmpty())
    }

    @Test fun `incoming audio reaches player and control frames reach presentation flow`() = runTest {
        val f = Fixture(this)
        val received = mutableListOf<ServerControlFrame>()
        backgroundScope.launch { f.repository.incoming.collect { received += it } }
        f.repository.connect("https://pulse.example")
        f.socket.opened(); runCurrent()
        val pcm = byteArrayOf(3, 0)
        f.socket.listeners.last().onMessage(f.socket.socket, pcm.toByteString())
        val transcript = ServerControlFrame.Transcript(TranscriptRole.ASSISTANT, "ready")
        f.socket.text(transcript); runCurrent()
        assertArrayEquals(pcm, f.player.played.single())
        assertEquals(listOf(transcript), received)
    }

    @Test fun `release completes input even when focus loss already stopped capture`() = runTest {
        val f = Fixture(this)
        f.repository.connect("https://pulse.example")
        f.socket.opened(); runCurrent()
        f.repository.pressPtt()
        f.focus.onLoss()
        assertFalse(f.repository.pttHeld.value)
        f.repository.releasePtt(); f.repository.releasePtt()
        assertEquals(1, f.socket.texts.size)
        assertEquals(ClientControlFrame.InputComplete, VoiceFrames.decodeClientFrame(f.socket.texts.single()))
    }
}
