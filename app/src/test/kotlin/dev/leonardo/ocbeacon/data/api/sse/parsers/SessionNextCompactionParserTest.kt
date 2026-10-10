package dev.leonardo.ocbeacon.data.api.sse.parsers

import dev.leonardo.ocbeacon.domain.model.SessionNextEvent
import dev.leonardo.ocbeacon.domain.model.SseEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

/**
 * #539 #146①①：session.next.compaction.* 新命名事件的解析契约。
 * 现网 2.0.26 实测只发 beta 命名族（session.compaction.*，2026-10-10 取证）——
 * 本组为上游切新命名后的防御性契约（G1/G2）。
 */
class SessionNextCompactionParserTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val parser = SessionNextEventParser(json)

    private fun props(vararg pairs: Pair<String, String>) = buildJsonObject {
        pairs.forEach { (k, v) -> put(k, v) }
    }

    // ============ G1：ended 成功/失败映射 ============

    @Test
    fun `G1 ended success maps to SessionCompacted`() {
        val event = parser.parse(
            "session.next.compaction.ended",
            props("sessionID" to "s1", "messageID" to "m1", "reason" to "manual"),
        )
        assertTrue(event is SseEvent.SessionCompacted)
        assertEquals("s1", (event as SseEvent.SessionCompacted).sessionId)
    }

    @Test
    fun `G1 ended failure maps to CompactionEnded with error message`() {
        val props = buildJsonObject {
            put("sessionID", "s1")
            put("messageID", "m1")
            putJsonObject("error") { put("message", "Model deprecated") }
        }
        val event = parser.parse("session.next.compaction.ended", props)
        assertTrue(event is SseEvent.SessionNext)
        val inner = (event as SseEvent.SessionNext).event
        assertTrue(inner is SessionNextEvent.CompactionEnded)
        assertEquals("Model deprecated", (inner as SessionNextEvent.CompactionEnded).error)
    }

    @Test
    fun `G1 ended without sessionID falls through to DTO path`() {
        val event = parser.parse("session.next.compaction.ended", props("messageID" to "m1"))
        // 无 sessionID 不手映射——DTO 解码 sessionID 必填会抛 → Unknown 兜底
        val inner = (event as SseEvent.SessionNext).event
        assertTrue(inner !is SessionNextEvent.CompactionEnded || inner.sessionId.isNotEmpty())
    }

    // ============ G2：delta 字段双读 ============

    @Test
    fun `G2 delta reads new-name text field`() {
        val event = parser.parseSessionNextEvent(
            "session.next.compaction.delta",
            props("sessionID" to "s1", "messageID" to "m1", "text" to "## 摘要"),
        )
        assertTrue(event is SessionNextEvent.CompactionDelta)
        val delta = event as SessionNextEvent.CompactionDelta
        assertEquals("", delta.delta)
        assertEquals("## 摘要", delta.text)
        assertEquals("## 摘要", delta.effectiveDelta)
    }

    @Test
    fun `G2 delta falls back to legacy delta field`() {
        val event = parser.parseSessionNextEvent(
            "session.next.compaction.delta",
            props("sessionID" to "s1", "messageID" to "m1", "delta" to "旧字段"),
        )
        assertEquals("旧字段", (event as SessionNextEvent.CompactionDelta).effectiveDelta)
    }

    @Test
    fun `G2 delta prefers text over delta when both present`() {
        val props = json.parseToJsonElement(
            """{"sessionID":"s1","messageID":"m1","delta":"旧","text":"新"}"""
        ).jsonObject
        assertEquals("新", parser.parseSessionNextEvent("session.next.compaction.delta", props)
            .let { it as SessionNextEvent.CompactionDelta }.effectiveDelta)
    }
}
