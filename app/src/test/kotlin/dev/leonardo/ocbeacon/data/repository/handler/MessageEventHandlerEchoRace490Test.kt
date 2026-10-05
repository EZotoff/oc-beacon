package dev.leonardo.ocbeacon.data.repository.handler

import dev.leonardo.ocbeacon.domain.model.Message
import dev.leonardo.ocbeacon.domain.model.MessageWithParts
import dev.leonardo.ocbeacon.domain.model.MergeStrategy
import dev.leonardo.ocbeacon.domain.model.SseEvent
import dev.leonardo.ocbeacon.domain.model.TimeInfo
import dev.leonardo.ocbeacon.domain.repository.MessageCacheRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #490 单发双消息根修：换装握手顺序无关化 + 存量幽灵自愈。
 *
 * 实测链路（hitl3 捕获 2026-09-30，DSH 0.2.0-rc.2 live）：服务器持久
 * user/message 广播帧（mapper 随帧补发 MessageRemoved(pending-<rpcId>) 拆除）
 * 可先于 prompt RPC 的 HTTP 响应到达——拆除时刻播种尚未发生（no-op），响应
 * 返回后的本地播种成为永不拆除的持久幽灵（22:21/23:29/23:32 三次幽灵 vs
 * 22:34/23:19 两次正常换装 = 同一竞态的两种落序）。
 */
class MessageEventHandlerEchoRace490Test {

    private fun userMsg(id: String, created: Long = System.currentTimeMillis()) = Message.User(
        id = id, sessionId = "s1", time = TimeInfo(created = created),
    )

    private fun durable(id: String = "seq-s1-45927") = userMsg(id)

    private fun seed(rpcId: String = "20b6f5df", created: Long = System.currentTimeMillis()) =
        userMsg("pending-$rpcId", created)

    /** mapper 随持久帧补发的拆除事件。 */
    private fun demolish(rpcId: String = "20b6f5df") =
        SseEvent.MessageRemoved(sessionId = "s1", messageId = "pending-$rpcId")

    private fun userRows(handler: MessageEventHandler): List<Message.User> =
        handler.messages.value["s1"].orEmpty().filterIsInstance<Message.User>()

    @Test
    fun `demolish before seed drops the late seed - no ghost`() = runTest {
        // 竞态败者落序（hitl3 22:21:31.842 拆除先到 → .864 播种后到）：
        // 拆除登记台账，迟到播种命中即丢——终态恰一条（durable）。
        val store = mockk<MessageCacheRepository>(relaxed = true)
        val upserts = mutableListOf<List<MessageWithParts>>()
        coEvery { store.upsertMessages(any(), any(), any()) } answers {
            synchronized(upserts) { upserts.add(secondArg()) }
        }
        val handler = MessageEventHandler(store)
        handler.handleMessageUpdated(SseEvent.MessageUpdated(durable()))
        handler.handleMessageRemoved(demolish())
        handler.handleMessageUpdated(SseEvent.MessageUpdated(seed())) // 迟到播种
        handler.flushPendingUpserts()
        handler.flushPendingDeletes()

        val rows = userRows(handler)
        assertEquals("终态恰一条用户消息（durable）", 1, rows.size)
        assertEquals("seq-s1-45927", rows[0].id)
        assertFalse(
            "迟到的 pending 播种不得落库",
            synchronized(upserts) { upserts.flatten() }.any { it.info.id.startsWith("pending-") },
        )
    }

    @Test
    fun `seed before demolish still swaps - normal order preserved`() = runTest {
        // 正常落序回归（22:34/23:19）：播种先入，拆除随持久帧到达换装——
        // 台账登记不影响在场行的拆除，终态恰一条（durable）。
        val handler = MessageEventHandler(null)
        handler.handleMessageUpdated(SseEvent.MessageUpdated(seed()))
        handler.handleMessageUpdated(SseEvent.MessageUpdated(durable()))
        handler.handleMessageRemoved(demolish())

        val rows = userRows(handler)
        assertEquals("终态恰一条用户消息（durable）", 1, rows.size)
        assertEquals("seq-s1-45927", rows[0].id)
    }

    @Test
    fun `non-pending removal re-arrival is unaffected by registry`() = runTest {
        // 台账只针对 pending-*：普通消息删除后的合法重放重加（#437 用例域）
        // 不得被竞态台账吞掉。
        val handler = MessageEventHandler(null)
        handler.handleMessageRemoved(SseEvent.MessageRemoved(sessionId = "s1", messageId = "m9"))
        handler.handleMessageUpdated(SseEvent.MessageUpdated(userMsg("m9")))
        assertEquals(1, userRows(handler).size)
    }

    @Test
    fun `sweep drops stale pending rows on rest refresh`() = runTest {
        // 存量幽灵自愈：REST 快照不含 pending-*，刷新时超龄（>STALE 宽限）
        // pending 行 = 拆除丢失的幽灵——复用拆除原语（内存三清 + Room 待删）。
        val store = mockk<MessageCacheRepository>(relaxed = true)
        val handler = MessageEventHandler(store)
        val staleCreated = System.currentTimeMillis() -
            (MessageEventHandler.STALE_PENDING_ECHO_MS + 60_000L)
        handler.handleMessageUpdated(SseEvent.MessageUpdated(seed(rpcId = "ghost", created = staleCreated)))
        handler.handleMessageUpdated(SseEvent.MessageUpdated(durable()))
        handler.upsertMessages(
            "s1",
            listOf(MessageWithParts(info = durable(), parts = emptyList())),
            MergeStrategy.SSE_PRIORITY,
        )
        handler.flushPendingDeletes()

        val rows = userRows(handler)
        assertEquals("超龄幽灵被清淤，durable 保留", 1, rows.size)
        assertEquals("seq-s1-45927", rows[0].id)
        coVerify(exactly = 1) { store.deleteMessage("s1", "pending-ghost") }
    }

    @Test
    fun `sweep keeps fresh pending rows - in-flight send survives refresh`() = runTest {
        // 刚播种（<宽限）的 pending 行在 REST 刷新合并时必须存活——
        // 拆除竞态窗口内的合法 echo 不得被清淤误伤。
        val handler = MessageEventHandler(null)
        handler.handleMessageUpdated(SseEvent.MessageUpdated(seed(rpcId = "fresh")))
        handler.handleMessageUpdated(SseEvent.MessageUpdated(durable()))
        handler.upsertMessages(
            "s1",
            listOf(MessageWithParts(info = durable(), parts = emptyList())),
            MergeStrategy.SSE_PRIORITY,
        )

        val ids = userRows(handler).map { it.id }
        assertTrue("新鲜 pending 行存活", "pending-fresh" in ids)
        assertTrue("durable 行在场", "seq-s1-45927" in ids)
    }
}
