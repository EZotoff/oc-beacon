package dev.leonardo.ocbeacon.data.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class AndroidVoiceRecorder(
    private val context: Context,
    private val scope: CoroutineScope,
) : VoiceRecorder {
    private var record: AudioRecord? = null
    private var capture: Job? = null

    @Synchronized
    override fun start(onAudio: (ByteArray) -> Unit, onFailure: (Throwable) -> Unit) {
        if (record != null) return
        check(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED) { "Microphone permission is required" }
        val minimum = AudioRecord.getMinBufferSize(16_000, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT)
        check(minimum > 0) { "PCM16 recording is unavailable" }
        val current = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16_000,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum, 1_280))
        try {
            check(current.state == AudioRecord.STATE_INITIALIZED)
            current.startRecording()
        } catch (error: Exception) {
            current.release()
            throw error
        }
        record = current
        capture = scope.launch(Dispatchers.IO) {
            val buffer = ByteArray(640)
            try {
                while (isActive) {
                    val count = synchronized(this@AndroidVoiceRecorder) {
                        if (record !== current) return@launch
                        current.read(buffer, 0, buffer.size, AudioRecord.READ_NON_BLOCKING)
                    }
                    check(count >= 0) { "AudioRecord read failed: $count" }
                    if (count > 0) onAudio(buffer.copyOf(count)) else delay(10)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                onFailure(error)
            } finally {
                synchronized(this@AndroidVoiceRecorder) {
                    if (record === current) {
                        record = null
                        current.release()
                    }
                }
            }
        }
    }

    @Synchronized
    override fun stop() {
        capture?.cancel()
        capture = null
        val current = record
        record = null
        if (current != null) {
            try {
                current.stop()
            } finally {
                current.release()
            }
        }
    }
}

class AndroidVoicePlayer : VoicePlayer {
    private var track: AudioTrack? = null

    @Synchronized
    override fun start() {
        if (track != null) return
        val minimum = AudioTrack.getMinBufferSize(24_000, AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT)
        check(minimum > 0) { "PCM16 playback is unavailable" }
        val current = AudioTrack.Builder()
            .setAudioAttributes(voiceAudioAttributes())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(24_000)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(maxOf(minimum, 2_400))
            .setTransferMode(AudioTrack.MODE_STREAM).build()
        try {
            check(current.state == AudioTrack.STATE_INITIALIZED)
            current.play()
            track = current
        } catch (error: Exception) {
            current.release()
            throw error
        }
    }

    override suspend fun play(bytes: ByteArray) {
        val current = synchronized(this) { track } ?: return
        var offset = 0
        while (offset < bytes.size) {
            val written = synchronized(this) {
                if (track !== current) return
                current.write(bytes, offset, bytes.size - offset, AudioTrack.WRITE_NON_BLOCKING)
            }
            check(written >= 0) { "AudioTrack write failed: $written" }
            offset += written
            if (written == 0) delay(10)
        }
    }

    @Synchronized
    override fun stop() {
        val current = track ?: return
        track = null
        try {
            current.pause()
            current.flush()
        } finally {
            current.release()
        }
    }
}

class AndroidVoiceAudioFocus(context: Context) : VoiceAudioFocus {
    private val manager = context.getSystemService(AudioManager::class.java)
    private var request: AudioFocusRequest? = null

    override fun request(onLoss: () -> Unit): Boolean {
        val current = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(voiceAudioAttributes())
            .setOnAudioFocusChangeListener { change ->
                if (change < 0) onLoss()
            }.build()
        request = current
        val granted = manager.requestAudioFocus(current) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (!granted) abandon()
        return granted
    }

    override fun abandon() {
        request?.let { manager.abandonAudioFocusRequest(it) }
        request = null
    }
}

private fun voiceAudioAttributes(): AudioAttributes = AudioAttributes.Builder()
    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
