package dev.leonardo.ocbeacon.data.api.voice

import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okio.ByteString.Companion.toByteString
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceWsClientTest {
    @Test fun `connect uses secure beacon endpoint and exposes live state`() = runTest {
        val fake = FakeVoiceSocket()
        val client = VoiceWsClient(backgroundScope, fake)
        client.connect("https://pulse.example/")
        assertEquals("https://pulse.example/api/voice-ws?clientClass=beacon", fake.requests.single().url.toString())
        assertEquals(VoiceConnectionState.Connecting, client.connectionState.value)
        fake.opened()
        assertEquals(VoiceConnectionState.Live, client.connectionState.value)
        client.disconnect()
        assertEquals(VoiceConnectionState.Disconnected, client.connectionState.value)
        verify { fake.socket.cancel() }
    }

    @Test fun `control frames round trip through fake socket`() = runTest {
        val fake = FakeVoiceSocket()
        val client = VoiceWsClient(backgroundScope, fake)
        val received = mutableListOf<VoiceIncoming>()
        backgroundScope.launch { client.incoming.collect { received += it } }
        runCurrent()
        client.connect("https://pulse.example")
        fake.opened()
        val selection = ClientControlFrame.Selection("ctx-12", 2)
        assertTrue(client.sendControl(selection))
        assertEquals(selection, VoiceFrames.decodeClientFrame(fake.texts.single()))
        val transcript = ServerControlFrame.Transcript(TranscriptRole.ASSISTANT, "ready")
        fake.text(transcript)
        runCurrent()
        assertTrue(received.contains(VoiceIncoming.Control(transcript)))
        assertTrue(received.contains(VoiceIncoming.Connection(VoiceConnectionState.Live)))
    }

    @Test fun `binary audio passes through unchanged in both directions`() = runTest {
        val fake = FakeVoiceSocket()
        val client = VoiceWsClient(backgroundScope, fake)
        val received = mutableListOf<VoiceIncoming>()
        backgroundScope.launch { client.incoming.collect { received += it } }
        runCurrent()
        client.connect("https://pulse.example")
        fake.opened()
        val pcm = byteArrayOf(0, -128, -1, 127)
        assertTrue(client.sendAudio(pcm))
        assertArrayEquals(pcm, fake.bytes.single().toByteArray())
        fake.listeners.last().onMessage(fake.socket, pcm.toByteString())
        runCurrent()
        assertArrayEquals(pcm, received.filterIsInstance<VoiceIncoming.Audio>().single().bytes)
    }

    @Test fun `failure reconnects only after 1500ms backoff`() = runTest {
        val fake = FakeVoiceSocket()
        val client = VoiceWsClient(backgroundScope, fake)
        client.connect("https://pulse.example")
        fake.failed()
        runCurrent()
        advanceTimeBy(1499); runCurrent()
        assertEquals(1, fake.requests.size)
        advanceTimeBy(1); runCurrent()
        assertEquals(2, fake.requests.size)
        assertEquals(fake.requests.first().url, fake.requests.last().url)
    }

    @Test fun `handoff is terminal despite close failure and elapsed backoff`() = runTest {
        val fake = FakeVoiceSocket()
        val client = VoiceWsClient(backgroundScope, fake)
        client.connect("https://pulse.example")
        fake.opened()
        fake.text(ServerControlFrame.Handoff)
        fake.failed()
        fake.listeners.last().onClosed(fake.socket, 1000, "handoff")
        advanceTimeBy(30_000); runCurrent()
        assertEquals(VoiceConnectionState.Handoff, client.connectionState.value)
        assertEquals(1, fake.requests.size)
        assertFalse(client.sendAudio(byteArrayOf(0, 0)))
        verify { fake.socket.close(1000, "handoff") }
    }

    @Test fun `explicit connect after handoff works and ignores old callbacks`() = runTest {
        val fake = FakeVoiceSocket()
        val client = VoiceWsClient(backgroundScope, fake)
        client.connect("https://pulse.example")
        val old = fake.listeners.single()
        fake.text(ServerControlFrame.Handoff)
        client.connect("https://pulse.example")
        fake.opened()
        old.onMessage(fake.socket, "{\"type\":\"handoff\"}")
        old.onFailure(fake.socket, IllegalStateException(), null)
        advanceTimeBy(30_000); runCurrent()
        assertEquals(2, fake.requests.size)
        assertEquals(VoiceConnectionState.Live, client.connectionState.value)
    }

    @Test fun `disconnect cancels retry and suppresses idle sends and malformed frames`() = runTest {
        val fake = FakeVoiceSocket()
        val client = VoiceWsClient(backgroundScope, fake)
        assertFalse(client.sendControl(ClientControlFrame.InputComplete))
        client.connect("https://pulse.example")
        fake.listeners.last().onMessage(fake.socket, "invalid")
        fake.failed()
        client.disconnect()
        advanceTimeBy(30_000); runCurrent()
        assertEquals(1, fake.requests.size)
        assertFalse(client.sendAudio(byteArrayOf(0, 0)))
    }
}
