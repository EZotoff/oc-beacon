package dev.leonardo.ocbeacon.data.voice

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class FakeVoiceRecorder : VoiceRecorder {
    var starts = 0
    var stops = 0
    var onAudio: (ByteArray) -> Unit = {}
    var onFailure: (Throwable) -> Unit = {}
    override fun start(onAudio: (ByteArray) -> Unit, onFailure: (Throwable) -> Unit) {
        starts++; this.onAudio = onAudio; this.onFailure = onFailure
    }
    override fun stop() { stops++ }
}

class FakeVoicePlayer : VoicePlayer {
    var starts = 0
    var stops = 0
    val played = mutableListOf<ByteArray>()
    override fun start() { starts++ }
    override suspend fun play(bytes: ByteArray) { played += bytes }
    override fun stop() { stops++ }
}

class FakeVoiceFocus : VoiceAudioFocus {
    var granted = true
    var requests = 0
    var abandons = 0
    var onLoss: () -> Unit = {}
    override fun request(onLoss: () -> Unit): Boolean { requests++; this.onLoss = onLoss; return granted }
    override fun abandon() { abandons++ }
}

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceAudioEngineTest {
    @Test fun `idle and released PTT never send audio`() = runTest {
        val recorder = FakeVoiceRecorder()
        val sent = mutableListOf<ByteArray>()
        val engine = VoiceAudioEngine(recorder, FakeVoicePlayer(), FakeVoiceFocus(), backgroundScope) { sent += it; true }
        engine.start()
        recorder.onAudio(byteArrayOf(0, 0))
        assertTrue(sent.isEmpty())
        engine.pressPtt()
        recorder.onAudio(byteArrayOf(1, 0))
        engine.releasePtt()
        recorder.onAudio(byteArrayOf(2, 0))
        assertEquals(1, sent.size)
        assertArrayEquals(byteArrayOf(1, 0), sent.single())
        engine.stop()
    }

    @Test fun `repeated press release and stop preserve platform lifecycle`() = runTest {
        val recorder = FakeVoiceRecorder()
        val player = FakeVoicePlayer()
        val focus = FakeVoiceFocus()
        val engine = VoiceAudioEngine(recorder, player, focus, backgroundScope) { true }
        engine.pressPtt(); engine.pressPtt()
        engine.releasePtt(); engine.releasePtt()
        engine.stop(); engine.stop()
        assertEquals(1, recorder.starts)
        assertEquals(1, recorder.stops)
        assertEquals(1, player.starts)
        assertEquals(1, player.stops)
        assertEquals(1, focus.abandons)
    }

    @Test fun `incoming playback copies bytes and preserves order`() = runTest {
        val player = FakeVoicePlayer()
        val engine = VoiceAudioEngine(FakeVoiceRecorder(), player, FakeVoiceFocus(), backgroundScope) { true }
        engine.start()
        val bytes = byteArrayOf(1, 0)
        engine.playAudio(bytes)
        bytes[0] = 9
        engine.playAudio(byteArrayOf(2, 0))
        runCurrent()
        assertEquals(2, player.played.size)
        assertArrayEquals(byteArrayOf(1, 0), player.played.first())
        engine.stop()
    }

    @Test fun `focus denial prevents capture and playback`() = runTest {
        val recorder = FakeVoiceRecorder()
        val player = FakeVoicePlayer()
        val focus = FakeVoiceFocus().apply { granted = false }
        val engine = VoiceAudioEngine(recorder, player, focus, backgroundScope) { true }
        engine.pressPtt()
        assertFalse(engine.pttHeld.value)
        assertEquals(0, recorder.starts)
        assertEquals(0, player.starts)
    }

    @Test fun `focus loss stops capture playback and rejects late recorder callbacks`() = runTest {
        val recorder = FakeVoiceRecorder()
        val player = FakeVoicePlayer()
        val focus = FakeVoiceFocus()
        var sent = 0
        val engine = VoiceAudioEngine(recorder, player, focus, backgroundScope) { sent++; true }
        engine.pressPtt()
        val oldCallback = recorder.onAudio
        focus.onLoss()
        engine.pressPtt()
        oldCallback(byteArrayOf(0, 0))
        assertEquals(0, sent)
        assertEquals(1, player.stops)
        engine.stop()
    }

    @Test fun `focus loss does not reacquire focus from incoming audio`() = runTest {
        val focus = FakeVoiceFocus()
        val engine = VoiceAudioEngine(FakeVoiceRecorder(), FakeVoicePlayer(), focus, backgroundScope) { true }
        engine.start()
        focus.onLoss()
        assertFalse(engine.playAudio(byteArrayOf(0, 0)))
        assertEquals(1, focus.requests)
    }

    @Test fun `capture failure stops platform resources and exposes error`() = runTest {
        val recorder = FakeVoiceRecorder()
        val player = FakeVoicePlayer()
        val focus = FakeVoiceFocus()
        val engine = VoiceAudioEngine(recorder, player, focus, backgroundScope) { true }
        engine.pressPtt()
        recorder.onFailure(IllegalStateException("capture failed"))
        assertFalse(engine.pttHeld.value)
        assertEquals("capture failed", engine.failure.value)
        assertEquals(1, recorder.stops)
        assertEquals(1, player.stops)
        assertEquals(1, focus.abandons)
    }

    @Test fun `interrupt discards queued playback without ending PTT`() = runTest {
        val recorder = FakeVoiceRecorder()
        val player = FakeVoicePlayer()
        val engine = VoiceAudioEngine(recorder, player, FakeVoiceFocus(), backgroundScope) { true }
        engine.pressPtt()
        engine.playAudio(byteArrayOf(1, 0))
        engine.interrupt()
        engine.playAudio(byteArrayOf(2, 0))
        runCurrent()
        assertTrue(engine.pttHeld.value)
        assertEquals(0, recorder.stops)
        assertArrayEquals(byteArrayOf(2, 0), player.played.single())
        engine.stop()
    }
}
