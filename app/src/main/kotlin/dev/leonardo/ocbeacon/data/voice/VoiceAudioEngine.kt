package dev.leonardo.ocbeacon.data.voice

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

interface VoiceRecorder {
    fun start(onAudio: (ByteArray) -> Unit, onFailure: (Throwable) -> Unit)
    fun stop()
}

interface VoicePlayer {
    fun start()
    suspend fun play(bytes: ByteArray)
    fun stop()
}

interface VoiceAudioFocus {
    fun request(onLoss: () -> Unit): Boolean
    fun abandon()
}

class VoiceAudioEngine(
    private val recorder: VoiceRecorder,
    private val player: VoicePlayer,
    private val focus: VoiceAudioFocus,
    private val scope: CoroutineScope,
    private val sendAudio: (ByteArray) -> Boolean,
) {
    private val mutablePtt = MutableStateFlow(false)
    val pttHeld = mutablePtt.asStateFlow()
    private val mutableFailure = MutableStateFlow<String?>(null)
    val failure = mutableFailure.asStateFlow()
    /** 2026-10-08 Wave 2: true while audio is suspended after a transient focus loss (additive). */
    private val mutableFocusLost = MutableStateFlow(false)
    val focusLost = mutableFocusLost.asStateFlow()
    private var started = false
    private var recordingGeneration = 0
    /** 2026-10-10: communication audio (focus + VOICE_COMMUNICATION track) is ACTIVITY-scoped:
     * acquired on capture/playback, auto-released after ~2s of silence. Holding it while the
     * session is merely warm put the whole device in call-audio mode (media routed to the
     * earpiece, notification sounds flipping the route). */
    private var lastPlaybackAt = 0L
    private var idleWatch: kotlinx.coroutines.Job? = null
    private var playback: Job? = null
    private var queue: Channel<ByteArray>? = null

    @Synchronized
    fun start(): Boolean {
        if (started) return true
        if (!focus.request {
            mutableFocusLost.value = true
            stop()
        }) return false
        mutableFocusLost.value = false
        try {
            player.start()
            started = true
            mutableFailure.value = null
            startPlayback()
            idleWatch?.cancel()
            idleWatch = scope.launch {
                while (true) {
                    kotlinx.coroutines.delay(1_000)
                    synchronized(this@VoiceAudioEngine) {
                        if (started && !mutablePtt.value &&
                            System.currentTimeMillis() - lastPlaybackAt > 2_000) {
                            stop()
                        }
                    }
                }
            }
        } catch (error: Exception) {
            player.stop()
            focus.abandon()
            mutableFailure.value = error.message ?: "Audio playback failed"
            return false
        }
        return true
    }

    @Synchronized
    fun pressPtt() {
        if (mutablePtt.value || !start()) return
        mutablePtt.value = true
        val id = ++recordingGeneration
        try {
            recorder.start(onAudio = { bytes ->
                synchronized(this) {
                    if (mutablePtt.value && recordingGeneration == id) sendAudio(bytes)
                }
            }, onFailure = { error ->
                synchronized(this) {
                    if (recordingGeneration == id) {
                        mutableFailure.value = error.message ?: "Audio capture failed"
                        stop()
                    }
                }
            })
        } catch (error: Exception) {
            releasePtt()
            mutableFailure.value = error.message ?: "Audio capture failed"
        }
    }

    @Synchronized
    fun releasePtt() {
        if (!mutablePtt.value) return
        mutablePtt.value = false
        recordingGeneration++
        recorder.stop()
    }

    /** Activity-scoped acquisition for the incoming-audio path: starts the engine if
     *  idle (e.g., Vox greeting without a press) and always refreshes the idle timer. */
    @Synchronized
    fun ensureActive(): Boolean {
        lastPlaybackAt = System.currentTimeMillis()
        return started || start()
    }

    @Synchronized
    fun playAudio(bytes: ByteArray): Boolean {
        return started && queue?.trySend(bytes.copyOf())?.isSuccess == true
    }

    @Synchronized
    fun interrupt() {
        if (!started) return
        cancelPlayback()
        player.stop()
        try {
            player.start()
            startPlayback()
        } catch (error: Exception) {
            mutableFailure.value = error.message ?: "Audio playback failed"
            stop()
        }
    }

    private fun startPlayback() {
        val audioQueue = Channel<ByteArray>(32)
        queue = audioQueue
        playback = scope.launch {
            try {
                for (bytes in audioQueue) player.play(bytes)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                synchronized(this@VoiceAudioEngine) {
                    if (queue === audioQueue) {
                        mutableFailure.value = error.message ?: "Audio playback failed"
                        stop()
                    }
                }
            }
        }
    }

    private fun cancelPlayback() {
        playback?.cancel()
        playback = null
        queue?.cancel()
        queue = null
    }

    @Synchronized
    fun stop() {
        idleWatch?.cancel()
        idleWatch = null
        releasePtt()
        cancelPlayback()
        if (started) {
            started = false
            try {
                player.stop()
            } finally {
                focus.abandon()
            }
        }
    }
}
