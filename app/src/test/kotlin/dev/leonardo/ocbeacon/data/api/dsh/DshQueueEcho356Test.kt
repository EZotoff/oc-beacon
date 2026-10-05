package dev.leonardo.ocbeacon.data.api.dsh

import dev.leonardo.ocbeacon.domain.model.SseEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #356 echo→持久原子换装——mapUserMessage 的 rpcId 对账分支。
 *
 * 契约（dsh-api-session-controller MessageSourceMap）：RPC 提交的持久回显
 * source={kind:'user', rpcId}（'user-rpc' 面）；本地 echo 播种 id=pending-<rpcId>
 *（DshApiClient.promptAsync V012 admission）。持久到达 → 同批补发
 * **MessageIdSwapped(pending-<rpcId> → 持久 id)**（#509 原地换名——行不离开
 * 列表，替代旧 MessageRemoved 拆除；消费端幂等：echo 不在为 no-op，历史/重放
 * 路径安全）。web 对位：observedRpcIds 命中即隐藏 echo。
 */
class DshQueueEcho356Test {

    private val json = Json

    private fun envelope(data: String, seq: Long = 5, time: Long = 11): JsonObject =
        json.parseToJsonElement(
            """{"type":"user/message","seq":$seq,"time":$time,"data":$data}"""
        ).jsonObject

    @Test
    fun `user message with rpc source emits pending echo swap`() {
        val mapped = DshEventMapper.mapSessionEvent(
            "s1",
            envelope("""{"content":[{"type":"text","text":"hi"}],"source":{"kind":"user","rpcId":"r-1"}}"""),
        )
        val events = mapped.filterIsInstance<DshMappedEvent.Sse>().map { it.event }
        // 持久消息本体照常上屏
        assertTrue(events.any { it is SseEvent.MessageUpdated })
        // 同批原地换名 pending echo（权威 id = seq-{sid}-{seq}；无重复无空窗）
        val swaps = events.filterIsInstance<SseEvent.MessageIdSwapped>()
        assertEquals(1, swaps.size)
        assertEquals("pending-r-1", swaps.single().fromId)
        assertEquals("seq-s1-5", swaps.single().toId)
        assertEquals("s1", swaps.single().sessionId)
        // 旧拆除事件不再发出（swap 取代 Removed——防双路径并存）
        assertTrue(events.none { it is SseEvent.MessageRemoved })
    }

    @Test
    fun `user message without rpc source has no echo swap`() {
        // 普通（非 RPC）来源——source 缺席 / kind=user 无 rpcId：不产生换名（回归护栏）
        val withoutSource = DshEventMapper.mapSessionEvent(
            "s1", envelope("""{"content":[{"type":"text","text":"hi"}]}"""),
        )
        assertTrue(
            withoutSource.filterIsInstance<DshMappedEvent.Sse>()
                .map { it.event }.none { it is SseEvent.MessageIdSwapped }
        )

        val plainSource = DshEventMapper.mapSessionEvent(
            "s1",
            envelope("""{"content":[{"type":"text","text":"hi"}],"source":{"kind":"user"}}"""),
        )
        assertTrue(
            plainSource.filterIsInstance<DshMappedEvent.Sse>()
                .map { it.event }.none { it is SseEvent.MessageIdSwapped }
        )
    }

    @Test
    fun `blank rpcId is ignored defensively`() {
        val mapped = DshEventMapper.mapSessionEvent(
            "s1",
            envelope("""{"content":[{"type":"text","text":"hi"}],"source":{"kind":"user","rpcId":""}}"""),
        )
        assertTrue(
            mapped.filterIsInstance<DshMappedEvent.Sse>()
                .map { it.event }.none { it is SseEvent.MessageIdSwapped }
        )
    }
}
