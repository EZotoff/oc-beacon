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
    private var started = false
    private var recordingGeneration = 0
    private var playback: Job? = null
    private var queue: Channel<ByteArray>? = null

    @Synchronized
    fun start(): Boolean {
        if (started) return true
        if (!focus.request { stop() }) return false
        try {
            player.start()
            started = true
            mutableFailure.value = null
            startPlayback()
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
