package dev.leonardo.ocbeacon.data.api.dsh

import dev.leonardo.ocbeacon.domain.model.Message
import dev.leonardo.ocbeacon.domain.model.SseEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #525：轮内注入族 repack——journal 实证（session-7300b09e，standard agent）：
 * agent 指令（system/message）与提问同毫秒、runtime-context 注入
 * （user/message source.kind≠user）在提问之后 +1ms 落盘；#524 把提问 created
 * 回拨到真实发送时刻后，注入族信封时刻恒晚于提问 → 整体落到提问气泡下方。
 * mapper 台账按首见序把注入族钉到提问上方（T_p−n … T_p−1），变化者补发
 * MessageUpdated 校正（消费端 earliestCreated 取 min）。
 */
class DshEventMapperInjectionRepack525Test {

    private val json = Json

    private fun env(type: String, seq: Long, time: Long, data: String): JsonObject =
        json.parseToJsonElement("""{"type":"$type","seq":$seq,"time":$time,"data":$data}""").jsonObject

    private fun eventsOf(sid: String, type: String, seq: Long, time: Long, data: String): List<SseEvent> =
        DshEventMapper.mapSessionEvent(sid, env(type, seq, time, data))
            .filterIsInstance<DshMappedEvent.Sse>().map { it.event }

    /** 按事件序折叠出各 user 族消息的最终 created。 */
    private fun foldCreated(events: List<SseEvent>): Map<String, Long> {
        val out = LinkedHashMap<String, Long>()
        events.forEach { e ->
            val u = (e as? SseEvent.MessageUpdated)?.info as? Message.User ?: return@forEach
            out[u.id] = u.time.created
        }
        return out
    }

    @Test
    fun `injection family repacked above prompt in first-seen order`() {
        val sid = "s-525-a"
        val all = mutableListOf<SseEvent>()
        all += eventsOf(sid, "turn/start", 8, 1000L, """{"turn":1}""")
        // agent 指令（先于提问落盘，同毫秒）
        all += eventsOf(
            sid, "system/message", 9, 1062L,
            """{"turn":1,"step":1,"message":{"role":"system","id":"sys-1","content":[{"type":"text","text":"You are an agent."}],"source":{"kind":"agent-instructions"}}}""",
        )
        // 提问（rpcId 无发送时刻记忆 → created=信封 1062）
        all += eventsOf(
            sid, "user/message", 10, 1062L,
            """{"content":[{"type":"text","text":"你好"}],"source":{"kind":"user","rpcId":"rpc-525-a"},"role":"user","id":"u-1"}""",
        )
        // runtime-context 注入（提问之后 +1ms）
        all += eventsOf(
            sid, "user/message", 11, 1063L,
            """{"content":[{"type":"text","text":"Current runtime context."}],"source":{"kind":"runtime-context"},"role":"user","id":"u-2"}""",
        )

        val final = foldCreated(all)
        val promptCreated = final.getValue("seq-s-525-a-10")
        val sysCreated = final.getValue("dsh-sys-sys-1")
        val ctxCreated = final.getValue("seq-s-525-a-11")

        // 注入族整体在提问上方，且首见序：agent 指令最高、runtime-context 紧贴提问
        assertEquals(1062L, promptCreated)
        assertEquals(1061L, ctxCreated)
        assertEquals(1060L, sysCreated)
        assertTrue(sysCreated < ctxCreated && ctxCreated < promptCreated)

        // 校正事件确实发射过（原始发射 1062/1063 被 repack 覆写）
        val userUpdates = all.filterIsInstance<SseEvent.MessageUpdated>()
            .count { (it.info as? Message.User)?.injectionKind != null }
        assertTrue(userUpdates >= 4) // 原始 2 条 + 校正 ≥2 条
    }

    @Test
    fun `prompt send-time memory drags whole family with it`() {
        val sid = "s-525-b"
        DshEventMapper.notePromptAdmitted("rpc-525-b", 500L)
        val all = mutableListOf<SseEvent>()
        all += eventsOf(sid, "turn/start", 8, 1000L, """{"turn":2}""")
        all += eventsOf(
            sid, "system/message", 9, 2062L,
            """{"turn":2,"step":1,"message":{"role":"system","id":"sys-2","content":[{"type":"text","text":"agent"}],"source":{"kind":"agent-instructions"}}}""",
        )
        all += eventsOf(
            sid, "user/message", 10, 2062L,
            """{"content":[{"type":"text","text":"hi"}],"source":{"kind":"user","rpcId":"rpc-525-b"},"role":"user","id":"u-3"}""",
        )
        val final = foldCreated(all)
        // 提问回拨到 500 → 注入族随之钉到 499（#524 与 #525 组合语义）
        assertEquals(500L, final.getValue("seq-s-525-b-10"))
        assertEquals(499L, final.getValue("dsh-sys-sys-2"))
    }

    @Test
    fun `turn boundary clears ledger and lone injection keeps envelope`() {
        val sid = "s-525-c"
        val all = mutableListOf<SseEvent>()
        all += eventsOf(sid, "turn/start", 8, 1000L, """{"turn":1}""")
        all += eventsOf(sid, "turn/end", 9, 2000L, """{"turn":1}""")
        // 新轮：无提问的孤立注入 → 无 repack，保持信封时刻
        all += eventsOf(
            sid, "user/message", 12, 3000L,
            """{"content":[{"type":"text","text":"snapshot"}],"source":{"kind":"runtime-context"},"role":"user","id":"u-9"}""",
        )
        val final = foldCreated(all)
        assertEquals(3000L, final.getValue("seq-s-525-c-12"))
    }
}
