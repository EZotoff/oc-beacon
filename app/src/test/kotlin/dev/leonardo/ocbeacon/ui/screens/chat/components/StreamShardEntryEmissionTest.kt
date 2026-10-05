package dev.leonardo.ocbeacon.ui.screens.chat.components

import dev.leonardo.ocbeacon.domain.model.Message
import dev.leonardo.ocbeacon.domain.model.Part
import dev.leonardo.ocbeacon.domain.model.TimeInfo
import dev.leonardo.ocbeacon.ui.screens.chat.ChatMessage
import dev.leonardo.ocbeacon.ui.screens.chat.markdown.PublishedShards
import dev.leonardo.ocbeacon.ui.screens.chat.markdown.ShardDoc
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #507（2026-10-03 真机定罪）：流式毕业内容消失——turnGroups 是**结构缓存**
 * （id 生命周期签名，ChatMessageList:324），流式宿主的 ChatMessage 捕获于消息
 * 创建时刻（parts 尚空——part 出生在后续 delta 批，签名不变缓存永不刷新），
 * `cm.parts` 恒空 → 冻结块查找恒 miss → #g 条目零发射 → 毕业的 2000+ 字
 * 无处渲染（用户所见「所有内容突然没掉」；尾块照常流式=「又突然正常输出」）。
 *
 * 契约（修复）：发布查找以 turnKey 直查（PublishedShards.turnKey），不依赖
 * turnGroups 的 parts 引用新鲜度——绕开整类结构缓存陈旧性。
 */
class StreamShardEntryEmissionTest {

    private fun motive(msg: String) = println("[MOTIVE] $msg")

    @Test
    fun `结构缓存的空 parts 不阻断冻结块条目发射 - #507`() {
        motive("#507 主断言：turnGroups 缓存持有出生时刻的空 parts 宿主（真机 vg2 探针实证 groupParts 恒空），" +
            "发布查找若走组 parts 恒 miss——必须经 turnKey 直查（或消息新引用）命中，#g 条目才发射")
        val freshHost = ChatMessage(
            message = Message.Assistant(id = "dsh-t1s1", sessionId = "s1", time = TimeInfo(2, 2), parentId = ""),
            parts = listOf(Part.Text(id = "dsh-t1s1_text_ord_1", sessionId = "s1", messageId = "dsh-t1s1", text = "流式正文")),
        )
        val staleHost = freshHost.copy(parts = emptyList())
        val displayItems = listOf(
            0 to freshHost,
            1 to ChatMessage(Message.User(id = "m_u", sessionId = "s1", time = TimeInfo(1, 1)), emptyList()),
        )
        val published = PublishedShards(
            turnKey = "t_dsh-t1s1",
            partId = "dsh-t1s1_text_ord_1",
            shards = listOf(ShardDoc(index = 0, from = 0, to = 10, text = "冻结文本")),
            tailFrom = 10,
            generation = 1,
        )
        val chat = buildChatEntries(
            displayItems = displayItems,
            turnGroups = mapOf(0 to listOf(staleHost)),
            streamingMsgId = "dsh-t1s1",
            chunkPlans = emptyMap(),
            recentStreamedTurnKeys = emptySet(),
            streamShards = mapOf("dsh-t1s1_text_ord_1" to published),
        )
        val chunk = chat.entries.filterIsInstance<ChatEntry.StreamChunk>()
        assertEquals(1, chunk.size)
        assertEquals("t_dsh-t1s1#g0", chunk[0].key)
        assertEquals("冻结文本", chunk[0].text)
        // 尾块 Turn 原键保留（锚/帽物主零迁移）
        assertTrue(chat.entries.any { it is ChatEntry.Turn && it.key == "t_dsh-t1s1" })
    }

    private fun assertTrue(b: Boolean) = org.junit.Assert.assertTrue(b)
}
