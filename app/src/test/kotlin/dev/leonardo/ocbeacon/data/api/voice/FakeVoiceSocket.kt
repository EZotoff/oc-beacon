package dev.leonardo.ocbeacon.data.api.voice

import io.mockk.every
import io.mockk.mockk
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

class FakeVoiceSocket : VoiceWebSocketOpener {
    val requests = mutableListOf<Request>()
    val listeners = mutableListOf<WebSocketListener>()
    val texts = mutableListOf<String>()
    val bytes = mutableListOf<ByteString>()
    val socket = mockk<WebSocket>(relaxed = true)

    init {
        every { socket.send(any<String>()) } answers { texts += firstArg<String>(); true }
        every { socket.send(any<ByteString>()) } answers { bytes += firstArg<ByteString>(); true }
    }

    override fun open(client: OkHttpClient, request: Request, listener: WebSocketListener): WebSocket {
        requests += request
        listeners += listener
        return socket
    }

    fun opened() = listeners.last().onOpen(socket, mockk(relaxed = true))
    fun text(frame: ServerControlFrame) = listeners.last().onMessage(socket, VoiceFrames.encodeServerFrame(frame))
    fun failed() = listeners.last().onFailure(socket, IllegalStateException("reset"), null)
}
