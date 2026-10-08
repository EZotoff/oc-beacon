package dev.leonardo.ocbeacon.data.api.voice

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Beacon-owned copy of voice-bridge server/protocol.ts and pipeline/show.ts.
 * Only WebSocket TEXT frames enter this codec; BINARY frames remain raw mono
 * PCM16 (16 kHz uplink, 24 kHz downlink), never UTF-8 or JSON.
 * Unknown keys are ignored for forward compatibility, like OperatorViewDto.
 * Show payloads stay open JSON objects: the bridge defines no per-view schema.
 * Optional context fields follow the native client model; the current bridge
 * parser still requires project, session and recent on outbound view-context.
 */
@Serializable
sealed interface ClientControlFrame {
    @Serializable
    @SerialName("inputComplete")
    data object InputComplete : ClientControlFrame

    @Serializable
    @SerialName("text")
    data class Text(val text: String) : ClientControlFrame

    @Serializable
    @SerialName("view-context")
    data class ViewContext(
        val view: ViewContextView,
        val project: VoiceProject? = null,
        val session: VoiceSession? = null,
        val selection: VoiceSelection? = null,
        val recent: List<RecentEntry>? = null,
    ) : ClientControlFrame {
        init { require(recent == null || recent.size <= 5) }
    }

    @Serializable
    @SerialName("selection")
    data class Selection(val contextTag: String, val index: Int) : ClientControlFrame {
        init {
            require(CONTEXT_TAG.matches(contextTag))
            require(index >= 0)
        }
    }
}

@Serializable
enum class ViewContextView {
    @SerialName("home") HOME,
    @SerialName("project") PROJECT,
    @SerialName("session") SESSION,
    @SerialName("comparison") COMPARISON,
    @SerialName("attention") ATTENTION,
    @SerialName("sessions") SESSIONS,
    @SerialName("workspace") WORKSPACE,
    @SerialName("chat") CHAT,
    @SerialName("supervisor") SUPERVISOR,
}

@Serializable
enum class VoiceSessionState {
    @SerialName("waiting") WAITING,
    @SerialName("running") RUNNING,
    @SerialName("error") ERROR,
}

@Serializable
enum class SelectionKind {
    @SerialName("card") CARD,
    @SerialName("option") OPTION,
    @SerialName("row") ROW,
}

@Serializable
data class VoiceProject(val id: String, val name: String)

@Serializable
data class VoiceSession(val id: String, val title: String, val state: VoiceSessionState)

@Serializable
data class VoiceSelection(val kind: SelectionKind, val id: String, val label: String)

@Serializable
data class RecentEntry(val projectId: String, val sessionId: String)

@Serializable
sealed interface ServerControlFrame {
    @Serializable
    @SerialName("state")
    data class State(val state: ConnectionState) : ServerControlFrame

    @Serializable
    @SerialName("missed-audio")
    data class MissedAudio(val dropped: Double) : ServerControlFrame {
        init { require(dropped.isFinite()) }
    }

    @Serializable
    @SerialName("transcript")
    data class Transcript(val role: TranscriptRole, val text: String) : ServerControlFrame

    @Serializable
    @SerialName("confirmation-pending")
    data class ConfirmationPending(val pending: Boolean) : ServerControlFrame

    @Serializable
    @SerialName("interrupt")
    data class Interrupt(val reason: String) : ServerControlFrame

    @Serializable
    @SerialName("error")
    data class Error(val message: String) : ServerControlFrame

    @Serializable
    @SerialName("handoff")
    data object Handoff : ServerControlFrame
}

@Serializable
enum class ConnectionState {
    @SerialName("idle") IDLE,
    @SerialName("connecting") CONNECTING,
    @SerialName("connected") CONNECTED,
    @SerialName("reconnecting") RECONNECTING,
    @SerialName("error") ERROR,
    @SerialName("closed") CLOSED,
}

@Serializable
enum class TranscriptRole {
    @SerialName("user") USER,
    @SerialName("assistant") ASSISTANT,
}

@Serializable
@SerialName("show")
data class ShowFrame(
    val view: ShowView,
    val title: String,
    val contextTag: String,
    val payload: JsonObject,
) : ServerControlFrame {
    init { require(CONTEXT_TAG.matches(contextTag)) }
}

@Serializable(with = ShowViewSerializer::class)
sealed interface ShowView {
    enum class Known(val wireValue: String) : ShowView {
        CARD("card"), LIST("list"), TABLE("table"), CHOICE("choice"),
        PROGRESS("progress"), COMPARISON("comparison"), DIFF("diff"),
    }

    data class UnknownView(val wireValue: String) : ShowView
}

object ShowViewSerializer : KSerializer<ShowView> {
    override val descriptor = PrimitiveSerialDescriptor("ShowView", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): ShowView {
        val value = decoder.decodeString()
        return ShowView.Known.entries.firstOrNull { it.wireValue == value }
            ?: ShowView.UnknownView(value)
    }

    override fun serialize(encoder: Encoder, value: ShowView) {
        encoder.encodeString(when (value) {
            is ShowView.Known -> value.wireValue
            is ShowView.UnknownView -> value.wireValue
        })
    }
}

private val CONTEXT_TAG = Regex("ctx-[0-9]+")

/** Malformed and unknown frame types return null; unknown show views survive. */
object VoiceFrames {
    private val json = Json { ignoreUnknownKeys = true }

    fun encodeClientFrame(frame: ClientControlFrame): String =
        json.encodeToString(ClientControlFrame.serializer(), frame)

    fun decodeClientFrame(text: String): ClientControlFrame? = try {
        json.decodeFromString(ClientControlFrame.serializer(), text)
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    fun encodeServerFrame(frame: ServerControlFrame): String =
        json.encodeToString(ServerControlFrame.serializer(), frame)

    fun decodeServerFrame(text: String): ServerControlFrame? = try {
        val frame = json.parseToJsonElement(text) as? JsonObject
        val type = frame?.get("type") as? JsonPrimitive
        if (type?.isString != true) null
        else json.decodeFromJsonElement(ServerControlFrame.serializer(), frame)
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }
}
