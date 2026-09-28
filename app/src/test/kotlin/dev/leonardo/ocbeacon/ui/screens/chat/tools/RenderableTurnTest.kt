package dev.leonardo.ocbeacon.ui.screens.chat.tools

import dev.leonardo.ocbeacon.domain.model.Message
import dev.leonardo.ocbeacon.domain.model.Part
import dev.leonardo.ocbeacon.domain.model.TimeInfo
import dev.leonardo.ocbeacon.ui.screens.chat.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RenderableTurnTest {

    private fun assistantMsg(id: String, created: Long, completed: Long?) = ChatMessage(
        message = Message.Assistant(
            id = id,
            sessionId = "test-session",
            time = TimeInfo(created = created, completed = completed),
            parentId = "",
            modelId = "test-model"
        ),
        parts = emptyList()
    )

    private fun compute(msgs: List<ChatMessage>): RenderableTurn =
        computeRenderableTurn(msgs, msgs.last(), true) { null }

    private fun syntheticMsg(id: String, created: Long) = ChatMessage(
        message = Message.User(
            id = id,
            sessionId = "test-session",
            role = "synthetic",
            time = TimeInfo(created = created)
        ),
        parts = listOf(
            Part.Text(id = "", sessionId = "test-session", messageId = id, text = "<task id=\"ses_x\" state=\"completed\">x</task>")
        )
    )

    private fun assistantMsgWithParts(
        id: String,
        created: Long,
        completed: Long?,
        vararg parts: Part,
    ) = ChatMessage(
        message = Message.Assistant(
            id = id,
            sessionId = "test-session",
            time = TimeInfo(created = created, completed = completed),
            parentId = "",
            modelId = "test-model",
        ),
        parts = parts.toList(),
    )

    private fun textPart(id: String, messageId: String) =
        Part.Text(id = id, sessionId = "test-session", messageId = messageId, text = "content-$id")

    // ============ #463 三轮：流式中最后消息首组的分割线标记 ============
    //
    // 根因(用户复验定罪):流式中最新消息走 GroupedParts 平铺分支,无分割线信息;
    // 线要等该消息完结并入 StepGroup 且再下一个消息开始流式触发重组才后补 =
    // 晚一个 step(先出 step 文字,step2 完结才现线)。语义:线随新 step 首个
    // 内容块首帧出现,不得后补改流式中已渲染内容。
    //
    // 顺序约定:turnMessages 传入序=新→旧(reverseLayout 数据序),装配内
    // ordered=reversed()=旧→新,ordered.last=最新=最终回答(平铺消息)。
    // 下述用例一律 msgs[0]=最新。

    @Test
    fun `streaming last message first group marked for divider`() {
        // step1(旧,完结) + step2(最新,流式中):step2 首组 part 被标记(线随其首帧出现)
        val msgs = listOf(
            assistantMsgWithParts("a2", 2500L, null, textPart("t2a", "a2"), textPart("t2b", "a2")),
            assistantMsgWithParts("a1", 1000L, 2000L, textPart("t1", "a1")),
        )
        assertEquals("t2a", compute(msgs).lastStepDividerBeforePartId)
    }

    @Test
    fun `single message turn has no divider marker`() {
        // 首 step 不插(turn 开始处)——单消息(流式或完结)一律 null
        val streaming = compute(listOf(assistantMsgWithParts("a1", 1000L, null, textPart("t1", "a1"))))
        assertNull(streaming.lastStepDividerBeforePartId)
        val done = compute(listOf(assistantMsgWithParts("a1", 1000L, 2000L, textPart("t1", "a1"))))
        assertNull(done.lastStepDividerBeforePartId)
    }

    @Test
    fun `empty prior messages do not mark divider`() {
        // 旧侧消息无渲染组(过滤后空) = 无视觉边界,不插线
        val msgs = listOf(
            assistantMsgWithParts("a2", 2500L, null, textPart("t2", "a2")),
            assistantMsg("a1", 1000L, 2000L),
        )
        assertNull(compute(msgs).lastStepDividerBeforePartId)
    }

    @Test
    fun `synthetic only prior content does not mark divider`() {
        // synthetic 通知不产生 step 边界(其分隔由 TurnDivider 承担)
        val msgs = listOf(
            assistantMsgWithParts("a2", 2500L, null, textPart("t2", "a2")),
            syntheticMsg("s1", 1000L),
        )
        assertNull(compute(msgs).lastStepDividerBeforePartId)
    }

    @Test
    fun `turn messages null falls back to current message without marker`() {
        // turnMessages=null(单消息 currentMessage 路径):无前序 step,null
        val t = computeRenderableTurn(
            null,
            assistantMsgWithParts("a1", 1000L, null, textPart("t1", "a1")),
            true,
        ) { null }
        assertNull(t.lastStepDividerBeforePartId)
    }

    @Test
    fun `single completed message duration equals its own span`() {
        val t = compute(listOf(assistantMsg("a1", 1000L, 5000L)))
        assertEquals(4000L, t.durationMs)
        assertEquals(1000L, t.turnStartMs)
    }

    @Test
    fun `multi-message turn duration spans first created to last completed`() {
        val msgs = listOf(assistantMsg("a1", 1000L, 2000L), assistantMsg("a2", 2500L, 8000L))
        val t = compute(msgs)
        assertEquals(7000L, t.durationMs)      // 8000 - 1000
        assertEquals(1000L, t.turnStartMs)     // 首条 created，而非代表消息 a2 的 2500
    }

    // ============ #338：零/负跨度 = 时长未知（null）============

    @Test
    fun `zero span turn yields null duration`() {
        // DSH 整装事件 created=completed 同信封 → 单消息轮 0ms 实为未知
        val t = compute(listOf(assistantMsg("a1", 1000L, 1000L)))
        assertNull(t.durationMs)
        assertEquals(1000L, t.turnStartMs)
    }

    @Test
    fun `same-instant multi-message turn yields null duration`() {
        // 多消息同毫秒（V2 本地构造同毫秒族）→ 跨度 0 同为未知
        val msgs = listOf(assistantMsg("a1", 1000L, 1000L), assistantMsg("a2", 1000L, 1000L))
        assertNull(compute(msgs).durationMs)
    }

    @Test
    fun `negative span turn yields null duration`() {
        // 跨钟域混腿（历史实证 -207ms 族）：null 而非负值
        // 单消息 created=5000（本地钟腿）、completed=4793（服务器腿）→ -207ms
        val msgs = listOf(assistantMsg("a1", 5000L, 4793L))
        assertNull(compute(msgs).durationMs)
    }

    @Test
    fun `streaming turn has null duration but stable turnStartMs`() {
        val msgs = listOf(assistantMsg("a1", 1000L, 2000L), assistantMsg("a2", 2500L, null))
        val t = compute(msgs)
        assertNull(t.durationMs)               // a2 未完成 → 交给流式 ticker
        assertEquals(1000L, t.turnStartMs)
    }

    @Test
    fun `single streaming message has null duration`() {
        val t = compute(listOf(assistantMsg("a1", 1000L, null)))
        assertNull(t.durationMs)
        assertEquals(1000L, t.turnStartMs)
    }

    // ============ #343：完结信号与时长解耦（allStepsCompleted）============

    @Test
    fun `zero span completed turn is marked complete despite null duration`() {
        // DSH 整装单消息轮：created==completed 同信封——轮已完结、时长未知。
        // 台账/产出行门控据此渲染（时长列回落「-」），不再被 durationMs==null 吞行。
        val t = compute(listOf(assistantMsg("a1", 1000L, 1000L)))
        assertNull(t.durationMs)
        assertTrue(t.allStepsCompleted)
    }

    @Test
    fun `negative span completed turn is marked complete`() {
        // 跨钟域混腿：completed 在前也在场——完结信号不受时长可测性影响
        val t = compute(listOf(assistantMsg("a1", 5000L, 4793L)))
        assertNull(t.durationMs)
        assertTrue(t.allStepsCompleted)
    }

    @Test
    fun `streaming turn is not completed`() {
        // 任一 assistant 消息 completed 缺席 → 未完结（SSE 铁律：流式中台账不出现）
        val msgs = listOf(assistantMsg("a1", 1000L, 2000L), assistantMsg("a2", 2500L, null))
        assertFalse(compute(msgs).allStepsCompleted)
    }

    @Test
    fun `turn without assistant messages is not completed`() {
        // 空轮（仅 synthetic/用户消息）不触发台账——assistantsForMeta 为空
        val t = compute(listOf(syntheticMsg("s1", 3000L)))
        assertFalse(t.allStepsCompleted)
        assertNull(t.durationMs)
    }

    // ============ synthetic 嵌入气泡（2026-08-11）============

    @Test
    fun `synthetic message produces SyntheticNotice render item`() {
        val msgs = listOf(assistantMsg("a1", 1000L, 5000L), syntheticMsg("s1", 3000L))
        val t = compute(msgs)
        val notice = t.renderItems.filterIsInstance<RenderItem.SyntheticNotice>()
        assertEquals(1, notice.size)
        assertEquals("s1", notice[0].msgId)
        // synthetic 的 <task> 原文不应作为普通文本渲染
        val textParts = t.renderItems.filterIsInstance<RenderItem.GroupedParts>()
        assertEquals(0, textParts.size)
        // copyText 不含 synthetic 原文
        assertNull(t.copyText)
    }

    @Test
    fun `synthetic only in turn keeps assistant parts`() {
        val assistant = ChatMessage(
            message = Message.Assistant(
                id = "a1", sessionId = "test-session",
                time = TimeInfo(created = 1000L, completed = 5000L),
                parentId = "", modelId = "m"
            ),
            parts = listOf(
                Part.Text(id = "p1", sessionId = "test-session", messageId = "a1", text = "hello")
            )
        )
        val msgs = listOf(assistant, syntheticMsg("s1", 3000L))
        val t = compute(msgs)
        val notices = t.renderItems.filterIsInstance<RenderItem.SyntheticNotice>()
        assertEquals(1, notices.size)
        // assistant 文本仍渲染
        val grouped = t.renderItems.filterIsInstance<RenderItem.GroupedParts>()
        assertEquals(1, grouped.size)
        // copyText 只含 assistant 文本
        assertEquals("hello", t.copyText)
    }
}
