package dev.leonardo.ocbeacon.data.api.sse.parsers

import dev.leonardo.ocbeacon.logging.AppLogger

import dev.leonardo.ocbeacon.domain.model.SessionNextEvent
import dev.leonardo.ocbeacon.domain.model.SseEvent
import kotlinx.serialization.json.*

private const val TAG = "SessionNextEventParser"

/**
 * 通过前缀匹配解析 session.next.* 事件。
 * 委托给 kotlinx.serialization 处理类型判别。
 */
class SessionNextEventParser(private val json: Json) : SseEventParser {

    private val prefix = "session.next."

    override fun canParse(eventType: String): Boolean = eventType.startsWith(prefix)

    override fun parse(eventType: String, props: JsonObject): SseEvent? {
        // #539 G1：新命名 session.next.compaction.ended 成功态映射 SessionCompacted
        // ——复刻 beta 命名 V2EventParser 对 session.compaction.ended 的既有语义
        //（完成 snackbar + 消息刷新由 compactedSessions 计数判变驱动；横幅终结由
        // EventDispatcher 跨 handler 处理）。此前新命名只走 SessionNext(CompactionEnded)
        // 终结横幅，完成 UX 链整段断裂。失败态（error.message 非空）保持
        // CompactionEnded——失败广播走 compactionFailures，无完成语义。手映射而非
        // DTO：wire 的 error 是对象而 DTO.error 是 String，直解码会抛 Unknown。
        if (eventType == "session.next.compaction.ended") {
            val sid = props["sessionID"]?.jsonPrimitive?.contentOrNull
            if (sid != null) {
                val err = props["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
                return if (err.isNullOrBlank()) {
                    SseEvent.SessionCompacted(sessionId = sid)
                } else {
                    SseEvent.SessionNext(
                        SessionNextEvent.CompactionEnded(
                            sessionId = sid,
                            messageId = props["messageID"]?.jsonPrimitive?.contentOrNull ?: "",
                            error = err,
                        )
                    )
                }
            }
        }
        val nextEvent = parseSessionNextEvent(eventType, props)
        return SseEvent.SessionNext(nextEvent)
    }

    /**
     * 从类型字符串和属性解析 session.next.* 事件。
     * 在 SSE 事件类型以 "session.next." 开头时调用。
     * 使用 kotlinx.serialization 的 Json 解码为对应的 SessionNextEvent 变体。
     */
    fun parseSessionNextEvent(type: String, props: JsonObject): SessionNextEvent {
        return try {
            // 将 type 注入 props，使判别器能选择正确的变体
            val propsWithType = JsonObject(props + ("type" to JsonPrimitive(type)))
            // #97（H-5）：decodeFromJsonElement 单遍解析（原 props.toString()
            // 再 decodeFromString = 树→字符串→再解析 三遍，流式高频事件放大）
            val result = json.decodeFromJsonElement<SessionNextEvent>(propsWithType)
            // 序列化器将未知类型路由到 Unknown，但不会从 "type" 填充 rawType
            if (result is SessionNextEvent.Unknown && result.rawType.isEmpty()) {
                result.copy(rawType = type, rawJson = props.toString())
            } else {
                result
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed to parse session.next event: $type — ${e.message}")
            SessionNextEvent.Unknown(rawType = type, rawJson = props.toString())
        }
    }
}
