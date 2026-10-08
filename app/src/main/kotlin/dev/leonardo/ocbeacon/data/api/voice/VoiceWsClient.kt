package dev.leonardo.ocbeacon.data.api.voice

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.util.concurrent.TimeUnit

fun interface VoiceWebSocketOpener {
    fun open(client: OkHttpClient, request: Request, listener: WebSocketListener): WebSocket
}

enum class VoiceConnectionState { Disconnected, Connecting, Live, Handoff }

sealed interface VoiceIncoming {
    data class Control(val frame: ServerControlFrame) : VoiceIncoming
    data class Audio(val bytes: ByteArray) : VoiceIncoming
    data class Connection(val state: VoiceConnectionState) : VoiceIncoming
}

class VoiceWsClient(
    private val scope: CoroutineScope,
    private val opener: VoiceWebSocketOpener = VoiceWebSocketOpener { client, request, listener ->
        client.newWebSocket(request, listener)
    },
    private val client: OkHttpClient = OkHttpClient.Builder()
        .pingInterval(25, TimeUnit.SECONDS).build(),
) {
    private val mutableState = MutableStateFlow(VoiceConnectionState.Disconnected)
    val connectionState = mutableState.asStateFlow()
    private val events = MutableSharedFlow<VoiceIncoming>(
        extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val incoming = events.asSharedFlow()
    private var socket: WebSocket? = null
    private var retry: Job? = null
    private var generation = 0
    private var attempt = 0
    private var baseUrl: String? = null

    @Synchronized
    fun connect(omoPulseBaseUrl: String) {
        disconnect()
        baseUrl = omoPulseBaseUrl
        open(generation)
    }

    @Synchronized
    fun disconnect() {
        generation++
        retry?.cancel()
        retry = null
        socket?.cancel()
        socket = null
        baseUrl = null
        setState(VoiceConnectionState.Disconnected)
    }

    @Synchronized
    fun sendAudio(bytes: ByteArray): Boolean =
        mutableState.value == VoiceConnectionState.Live && socket?.send(bytes.toByteString()) == true

    @Synchronized
    fun sendControl(frame: ClientControlFrame): Boolean =
        mutableState.value == VoiceConnectionState.Live &&
            socket?.send(VoiceFrames.encodeClientFrame(frame)) == true

    private fun setState(state: VoiceConnectionState) {
        mutableState.value = state
        events.tryEmit(VoiceIncoming.Connection(state))
    }

    private fun open(id: Int) {
        val base = requireNotNull(baseUrl).trim().trimEnd('/')
        val url = when {
            base.startsWith("https://") -> "wss://" + base.removePrefix("https://")
            base.startsWith("http://") -> "ws://" + base.removePrefix("http://")
            else -> base
        } + "/api/voice-ws?clientClass=beacon"
        setState(VoiceConnectionState.Connecting)
        val currentAttempt = ++attempt
        val listener = object : WebSocketListener() {
            private fun active(): Boolean = generation == id && attempt == currentAttempt &&
                mutableState.value != VoiceConnectionState.Handoff

            override fun onOpen(webSocket: WebSocket, response: Response) {
                synchronized(this@VoiceWsClient) {
                    if (active()) setState(VoiceConnectionState.Live)
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                synchronized(this@VoiceWsClient) {
                    if (!active()) return
                    val frame = VoiceFrames.decodeServerFrame(text) ?: return
                    events.tryEmit(VoiceIncoming.Control(frame))
                    if (frame == ServerControlFrame.Handoff) {
                        retry?.cancel()
                        setState(VoiceConnectionState.Handoff)
                        socket = null
                        webSocket.close(1000, "handoff")
                    }
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                synchronized(this@VoiceWsClient) {
                    if (active()) events.tryEmit(VoiceIncoming.Audio(bytes.toByteArray()))
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
                ended()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = ended()
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = ended()

            private fun ended() {
                synchronized(this@VoiceWsClient) {
                    if (!active()) return
                    attempt++ // Reject late callbacks, including duplicate close/failure callbacks.
                    socket?.cancel()
                    socket = null
                    setState(VoiceConnectionState.Disconnected)
                    retry = scope.launch {
                        delay(1_500)
                        synchronized(this@VoiceWsClient) {
                            if (generation == id) open(id)
                        }
                    }
                }
            }
        }
        val opened = opener.open(client, Request.Builder().url(url).build(), listener)
        if (generation == id && attempt == currentAttempt &&
            mutableState.value != VoiceConnectionState.Handoff) socket = opened else opened.cancel()
    }
}
