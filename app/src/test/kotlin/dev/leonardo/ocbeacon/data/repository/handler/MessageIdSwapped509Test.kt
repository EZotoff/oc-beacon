package dev.leonardo.ocbeacon.data.repository.handler

import dev.leonardo.ocbeacon.domain.model.Message
import dev.leonardo.ocbeacon.domain.model.MessageWithParts
import dev.leonardo.ocbeacon.domain.model.Part
import dev.leonardo.ocbeacon.domain.model.SseEvent
import dev.leonardo.ocbeacon.domain.model.TimeInfo
import dev.leonardo.ocbeacon.domain.repository.MessageCacheRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #509 毕业换装原地换名（MessageIdSwapped）——数据层身份稳定化。
 *
 * 旧路径 MessageRemoved(合成)+MessageUpdated(权威) 两次独立 StateFlow 更新之间
 * 存在「权威行在场、parts 未到」中间态——P5-3 过滤把它整轮过滤 → turn 组瞬空 →
 * t_ 条目销毁重建 → 槽位记忆归零 → asyncTerminal Loading≈0px 空白 350-700ms
 * （表格轮真机定罪 13:41:39.605-40.276）。换名路径在单同步块内原子完成：
 * 行不离开列表、part.id 不变。
 */
class MessageIdSwapped509Test {

    private val sid = "s1"
    private val hostId = "dsh-t73s1"
    private val authId = "seq-s1-673"

    private fun hostRow() = Message.Assistant(
        id = hostId, sessionId = sid, time = TimeInfo(created = 100L), parentId = "",
    )

    private fun streamedPart(text: String = "表格前半") = Part.Text(
        id = "$hostId" + "_text_ord_1", sessionId = sid, messageId = hostId, text = text,
        time = Part.Text.Time(start = 100L),
    )

    private fun swap(from: String = hostId, to: String = authId) =
        SseEvent.MessageIdSwapped(sessionId = sid, fromId = from, toId = to)

    @Test
    fun `rename in place - row stays in list, part id untouched`() = runTest {
        val handler = MessageEventHandler(null)
        handler.handleMessageUpdated(SseEvent.MessageUpdated(hostRow()))
        handler.handleMessagePartUpdated(SseEvent.MessagePartUpdated(streamedPart()))
        val before = handler.messages.value[sid]!!.toList()

        handler.handleMessageIdSwapped(swap())

        val rows = handler.messages.value[sid]!!
        // 行原位换名（同一下标、无 remove+add 空窗）
        assertEquals(1, rows.size)
        assertEquals(authId, rows[0].id)
        assertEquals("换名保位（index 不变）", before.indexOfFirst { it.id == hostId }, rows.indexOfFirst { it.id == authId })
        // parts 键换名 + messageId 改写；**part.id 不动**（id 键控缓存跨毕业连续）
        val parts = handler.parts.value[authId]
        assertEquals(1, parts!!.size)
        assertEquals(streamedPart().id, parts[0].id)
        assertEquals(authId, parts[0].messageId)
        assertNull("旧键不得残留（孤儿 part）", handler.parts.value[hostId])
    }

    @Test
    fun `authoritative part after swap merges by identical id`() = runTest {
        // 端到端身份连续：mapper 毕业序列 swap → MessageUpdated(seq) → PartUpdated
        //（宿主前缀 part id + 终态全文）——权威 part 与换名后的流式 part **同 id**
        // 原位合并（mergePart isTerminal 覆盖），不新建第二条 part。
        val handler = MessageEventHandler(null)
        handler.handleMessageUpdated(SseEvent.MessageUpdated(hostRow()))
        handler.handleMessagePartUpdated(SseEvent.MessagePartUpdated(streamedPart("表格全文")))
        handler.handleMessageIdSwapped(swap())
        handler.handleMessageUpdated(SseEvent.MessageUpdated(
            hostRow().copy(id = authId, time = TimeInfo(created = 100L, completed = 200L)),
        ))
        handler.handleMessagePartUpdated(SseEvent.MessagePartUpdated(
            Part.Text(
                id = "$hostId" + "_text_ord_1", sessionId = sid, messageId = authId,
                text = "表格全文", time = Part.Text.Time(start = 100L, end = 200L),
            ),
        ))

        val parts = handler.parts.value[authId]!!
        assertEquals(1, parts.size)
        assertEquals("表格全文", (parts[0] as Part.Text).text)
        assertEquals(200L, (parts[0] as Part.Text).time!!.end)
    }

    @Test
    fun `idempotent no-op when from row absent`() = runTest {
        // 历史 fold 无 chunk 播种 / 重入已权威 / 重复事件
        val handler = MessageEventHandler(null)
        handler.handleMessageIdSwapped(swap())
        assertTrue(handler.messages.value[sid].orEmpty().isEmpty())
        assertNull(handler.parts.value[authId])
    }

    @Test
    fun `resync double source - from row folded into existing to row`() = runTest {
        // Room 已按权威 id 播种（toId 行在场），实况重放又建宿主行 → 并入语义：
        // toId 行保留、fromId 行撤下、parts 归并——等价旧 remove+add 终态，无空窗。
        val handler = MessageEventHandler(null)
        handler.handleMessageUpdated(SseEvent.MessageUpdated(hostRow()))
        handler.handleMessagePartUpdated(SseEvent.MessagePartUpdated(streamedPart("流式累积")))
        handler.handleMessageUpdated(SseEvent.MessageUpdated(
            hostRow().copy(id = authId, time = TimeInfo(created = 100L, completed = 200L)),
        ))
        handler.handleMessagePartUpdated(SseEvent.MessagePartUpdated(
            Part.Text(
                id = "$hostId" + "_text_ord_1", sessionId = sid, messageId = authId,
                text = "流式累积", time = Part.Text.Time(start = 100L, end = 200L),
            ),
        ))

        handler.handleMessageIdSwapped(swap())

        val rows = handler.messages.value[sid]!!
        assertEquals(1, rows.size)
        assertEquals(authId, rows[0].id)
        val parts = handler.parts.value[authId]!!
        assertEquals("同 id 归并（不重复）", 1, parts.size)
    }

    @Test
    fun `pending echo swap records ledger - late seed dropped`() = runTest {
        // #490 竞态败者落序经 swap：换名先到（行缺席 no-op 但登记台账）→ 迟到
        // 播种命中台账被丢——终态恰一条（durable）。
        val handler = MessageEventHandler(null)
        handler.handleMessageUpdated(SseEvent.MessageUpdated(
            Message.User(id = "seq-s1-672", sessionId = sid, time = TimeInfo(created = 100L)),
        ))
        handler.handleMessageIdSwapped(swap(from = "pending-r9", to = "seq-s1-672"))
        handler.handleMessageUpdated(SseEvent.MessageUpdated(
            Message.User(id = "pending-r9", sessionId = sid, time = TimeInfo(created = 101L)),
        ))

        val rows = handler.messages.value[sid]!!
        assertEquals("终态恰一条（durable）", 1, rows.size)
        assertEquals("seq-s1-672", rows[0].id)
    }

    @Test
    fun `pending echo swap normal order renames in place`() = runTest {
        // #490 正常落序经 swap：播种先入，换名随持久帧到达——行原位换名。
        val handler = MessageEventHandler(null)
        handler.handleMessageUpdated(SseEvent.MessageUpdated(
            Message.User(id = "pending-r9", sessionId = sid, time = TimeInfo(created = 100L)),
        ))
        handler.handleMessageIdSwapped(swap(from = "pending-r9", to = "seq-s1-672"))

        val rows = handler.messages.value[sid]!!
        assertEquals(1, rows.size)
        assertEquals("seq-s1-672", rows[0].id)
    }

    @Test
    fun `room write ordering - from row deleted, renamed row persisted`() = runTest {
        // 写序（同 handleMessageRemoved）：合并缓冲撤下 fromId 未写快照 + 待删队列
        // 删除流式期已落盘行；换名后行以 toId 落盘——不产幽灵行。
        val store = mockk<MessageCacheRepository>(relaxed = true)
        val upserts = mutableListOf<List<MessageWithParts>>()
        coEvery { store.upsertMessages(any(), any(), any()) } answers {
            synchronized(upserts) { upserts.add(secondArg()) }
        }
        val handler = MessageEventHandler(store)
        handler.handleMessageUpdated(SseEvent.MessageUpdated(hostRow()))
        handler.handleMessagePartUpdated(SseEvent.MessagePartUpdated(streamedPart()))
        handler.handleMessageIdSwapped(swap())
        handler.flushPendingUpserts()
        handler.flushPendingDeletes()

        coVerify(exactly = 1) { store.deleteMessage(sid, hostId) }
        val persisted = synchronized(upserts) { upserts.flatten() }
        assertTrue("换名后行以 toId 落盘", persisted.any { it.info.id == authId })
        assertFalse("fromId 行不得再落盘", persisted.any { it.info.id == hostId })
    }

    @Test
    fun `stale pending deltas for from id dropped`() = runTest {
        // 48ms 批窗内滞留的 fromId delta 不得在旧键下重建孤儿 part
        val handler = MessageEventHandler(null)
        handler.handleMessageUpdated(SseEvent.MessageUpdated(hostRow()))
        handler.handleMessagePartUpdated(SseEvent.MessagePartUpdated(streamedPart("前")))
        handler.handleMessagePartDelta(
            SseEvent.MessagePartDelta(
                sessionId = sid, messageId = hostId,
                partId = "$hostId" + "_text_ord_1", field = "text", delta = "尾",
            ),
        )
        handler.handleMessageIdSwapped(swap())
        handler.forceFlushDeltas()

        assertNull("旧键不得被滞留 delta 复活", handler.parts.value[hostId])
        assertEquals(1, handler.parts.value[authId]!!.size)
    }
}
