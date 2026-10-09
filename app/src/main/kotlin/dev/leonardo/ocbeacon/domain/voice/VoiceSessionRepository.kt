package dev.leonardo.ocbeacon.domain.voice

import dev.leonardo.ocbeacon.data.api.voice.ClientControlFrame
import dev.leonardo.ocbeacon.data.api.voice.ServerControlFrame
import dev.leonardo.ocbeacon.data.api.voice.ViewContextView
import dev.leonardo.ocbeacon.data.api.voice.VoiceConnectionState
import dev.leonardo.ocbeacon.data.api.voice.VoiceIncoming
import dev.leonardo.ocbeacon.data.api.voice.VoiceProject
import dev.leonardo.ocbeacon.data.api.voice.VoiceSelection
import dev.leonardo.ocbeacon.data.api.voice.VoiceSession
import dev.leonardo.ocbeacon.data.api.voice.VoiceSessionState
import dev.leonardo.ocbeacon.data.api.voice.VoiceWsClient
import dev.leonardo.ocbeacon.data.voice.VoiceAudioEngine
import dev.leonardo.ocbeacon.domain.model.SupervisorSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class VoiceSessionConnection { Disconnected, Connecting, Live, MovedToAnotherSurface }

class VoiceSessionRepository(
    private val client: VoiceWsClient,
    private val audio: VoiceAudioEngine,
    scope: CoroutineScope,
    private val snapshot: () -> SupervisorSnapshot? = { null },
) {
    private val mutableState = MutableStateFlow(VoiceSessionConnection.Disconnected)
    val state = mutableState.asStateFlow()
    val pttHeld = audio.pttHeld
    val audioFailure = audio.failure
    /** 2026-10-08 Wave 2: passthrough for the focus-loss overlay (additive). */
    val focusLost = audio.focusLost
    private val frames = MutableSharedFlow<ServerControlFrame>(extraBufferCapacity = 32, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val incoming = frames.asSharedFlow()
    private val mutableShowFrame = MutableStateFlow<dev.leonardo.ocbeacon.data.api.voice.ShowFrame?>(null)
    val showFrame = mutableShowFrame.asStateFlow()
    private var baseUrl: String? = null
    private var voiceModel: String = VoiceUrl.DEFAULT_VOICE_MODEL
    private val mutableHeld = kotlinx.coroutines.flow.MutableStateFlow(false)
    /** Authoritative PTT state — the ONLY thing UI toggles and buttons may read. */
    val held = mutableHeld.asStateFlow()
    private var isHeld: Boolean
        get() = mutableHeld.value
        set(value) { mutableHeld.value = value }
    private var inputPending = false
    private var lastContext: ClientControlFrame.ViewContext? = null
    private var pendingContext: ClientControlFrame.ViewContext? = null

    init {
        // Engine self-stops (audio-focus loss, recorder failure) release the mic
        // without passing through releasePtt() — the utterance is dead; reflect it.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            audio.pttHeld.collect { capturing ->
                if (!capturing) synchronized(this@VoiceSessionRepository) {
                    if (audio.failure.value != null || audio.focusLost.value) isHeld = false
                }
            }
        }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            client.connectionState.collect { connection ->
                synchronized(this@VoiceSessionRepository) {
                    val wasLive = mutableState.value == VoiceSessionConnection.Live
                    mutableState.value = when (connection) {
                        VoiceConnectionState.Disconnected -> VoiceSessionConnection.Disconnected
                        VoiceConnectionState.Connecting -> VoiceSessionConnection.Connecting
                        VoiceConnectionState.Live -> VoiceSessionConnection.Live
                        VoiceConnectionState.Handoff -> VoiceSessionConnection.MovedToAnotherSurface
                    }
                    if (connection == VoiceConnectionState.Live) {
                        pendingContext?.let { context ->
                            if (client.sendControl(context)) lastContext = context
                        }
                        audio.start()
                        if (isHeld) {
                            audio.pressPtt()
                            inputPending = audio.pttHeld.value
                        }
                    } else {
                        lastContext = null
                        audio.stop()
                        inputPending = false
                        // 2026-10-09 (oracle-reviewed): an utterance ends when we LEAVE
                        // Live (flap/handoff/disconnect) — but NOT on Connecting, which
                        // is the transit of an explicit pressPtt reconnect; clearing
                        // there broke capture-on-Live after handoff.
                        if (wasLive) isHeld = false
                    }
                }
            }
        }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            client.incoming.collect { event ->
                when (event) {
                    is VoiceIncoming.Audio -> if (state.value == VoiceSessionConnection.Live) {
                        audio.playAudio(event.bytes)
                    }
                    is VoiceIncoming.Control -> {
                        if (event.frame is dev.leonardo.ocbeacon.data.api.voice.ShowFrame) mutableShowFrame.value = event.frame
                        if (event.frame is ServerControlFrame.Interrupt) audio.interrupt()
                        frames.emit(event.frame)
                    }
                    is VoiceIncoming.Connection -> Unit
                }
            }
        }
    }

    @Synchronized
    fun connect(omoPulseBaseUrl: String, voiceModel: String = VoiceUrl.DEFAULT_VOICE_MODEL) {
        isHeld = false
        inputPending = false
        audio.stop()
        lastContext = null
        baseUrl = omoPulseBaseUrl
        this.voiceModel = VoiceUrl.normalizeVoiceModel(voiceModel)
        client.connect(omoPulseBaseUrl, this.voiceModel)
    }

    @Synchronized
    fun disconnect() {
        isHeld = false
        inputPending = false
        audio.stop()
        lastContext = null
        client.disconnect()
    }

    @Synchronized
    fun pressPtt() {
        if (isHeld) return
        isHeld = true
        when (client.connectionState.value) {
            VoiceConnectionState.Live -> {
                audio.pressPtt()
                inputPending = audio.pttHeld.value
            }
            VoiceConnectionState.Handoff, VoiceConnectionState.Disconnected -> {
                val url = baseUrl
                if (url == null) isHeld = false else client.connect(url, voiceModel)
            }
            VoiceConnectionState.Connecting -> Unit
        }
    }

    @Synchronized
    fun releasePtt() {
        isHeld = false
        audio.releasePtt()
        if (inputPending) client.sendControl(ClientControlFrame.InputComplete)
        inputPending = false
    }

    fun sendSelection(contextTag: String, index: Int): Boolean =
        client.sendControl(ClientControlFrame.Selection(contextTag, index))

    @Synchronized
    fun emitViewContext(
        view: ViewContextView,
        project: VoiceProject? = null,
        session: VoiceSession? = null,
        selection: VoiceSelection? = null,
    ): Boolean {
        val item = snapshot()?.takeUnless { it.stale }?.attentionItems?.firstOrNull()
        val context = ClientControlFrame.ViewContext(
            view = view,
            project = project ?: VoiceProject(item?.root.orEmpty(), item?.project.orEmpty()),
            session = session ?: VoiceSession("", item?.sessionLabel.orEmpty(), VoiceSessionState.WAITING),
            selection = selection,
            recent = emptyList(),
        )
        pendingContext = context
        if (client.connectionState.value != VoiceConnectionState.Live) return false
        if (context == lastContext) return false
        if (!client.sendControl(context)) return false
        lastContext = context
        return true
    }
}
