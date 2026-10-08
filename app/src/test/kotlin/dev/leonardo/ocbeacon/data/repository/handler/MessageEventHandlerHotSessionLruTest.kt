package dev.leonardo.ocbeacon.data.repository.handler

import dev.leonardo.ocbeacon.domain.model.*
import dev.leonardo.ocbeacon.ui.screens.chat.components.StreamingDeltaBus
import org.junit.Assert.*
import org.junit.Test

class MessageEventHandlerHotSessionLruTest {
    private fun write(handler: MessageEventHandler, sid: String) {
        handler.handle(SseEvent.MessageUpdated(Message.Assistant(
            id = "m-$sid", sessionId = sid, time = TimeInfo(created = 1), parentId = ""
        )), "server")
        handler.handle(SseEvent.MessagePartUpdated(Part.Text(
            id = "p-$sid", sessionId = sid, messageId = "m-$sid", text = "body"
        )), "server")
    }

    @Test fun `nine sessions retain eight and cascade oldest parts`() {
        val handler = MessageEventHandler()
        repeat(9) { write(handler, "s$it") }
        assertEquals(8, handler.messages.value.size)
        assertEquals(8, handler.parts.value.size)
        assertNull(handler.messages.value["s0"])
        assertNull(handler.parts.value["m-s0"])
        assertNull(handler.structuralParts.value["m-s0"])
    }

    @Test fun `pinned oldest survives and unpin allows eviction`() {
        val handler = MessageEventHandler()
        handler.pinSessionHotView("s0")
        repeat(9) { write(handler, "s$it") }
        assertNotNull(handler.messages.value["s0"])
        assertNull(handler.messages.value["s1"])
        handler.unpinSessionHotView("s0")
        write(handler, "s9")
        assertNull(handler.messages.value["s0"])
    }

    @Test fun `evicted session is recreated by SSE and skeleton index is cleaned`() {
        val handler = MessageEventHandler()
        repeat(9) { write(handler, "s$it") }
        write(handler, "s0")
        assertNotNull(handler.messages.value["s0"])
        assertEquals(8, handler.messages.value.size)
    }

    @Test fun `retouch changes LRU order`() {
        val handler = MessageEventHandler()
        repeat(8) { write(handler, "s$it") }
        write(handler, "s0")
        write(handler, "s8")
        assertNotNull(handler.messages.value["s0"])
        assertNull(handler.messages.value["s1"])
    }

    @Test fun `per session cap remains one thousand`() {
        val handler = MessageEventHandler()
        handler.upsertMessages("s", (0..1004).map { i ->
            MessageWithParts(Message.User(id = "m$i", sessionId = "s", time = TimeInfo(created = i.toLong())),
                listOf(Part.Text(id = "p$i", sessionId = "s", messageId = "m$i", text = "body")))
        }, MergeStrategy.APPEND_ONLY)
        assertEquals(1000, handler.messages.value["s"]!!.size)
        assertNull(handler.parts.value["m0"])
        assertEquals("m5", handler.messages.value["s"]!!.first().id)
    }

    @Test fun `eviction racing a delta flush does not retain orphan parts`() {
        val handler = MessageEventHandler()
        handler.pinSessionHotView("pinned")
        write(handler, "pinned")
        repeat(8) { write(handler, "s$it") }
        val writer = Thread {
            repeat(20) {
                handler.handle(SseEvent.MessagePartDelta(sessionId = "s1", messageId = "m-s1",
                    partId = "p-s1", field = "text", delta = "x"), "server")
                handler.forceFlushDeltas()
            }
        }
        writer.start()
        repeat(20) { write(handler, "other$it") }
        writer.join()
        handler.evictLeastRecentlyUsedSessions(emptySet())
        assertNotNull(handler.messages.value["pinned"])
        assertEquals(8, handler.messages.value.size)
        val retained = handler.messages.value.values.flatten().map { it.id }.toSet()
        assertTrue(handler.parts.value.keys.all { it in retained })
        StreamingDeltaBus.clearAll()
    }
}
