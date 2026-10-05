package dev.leonardo.ocbeacon.ui.screens.chat.components

import dev.leonardo.ocbeacon.domain.model.Message
import dev.leonardo.ocbeacon.domain.model.Part
import dev.leonardo.ocbeacon.domain.model.TimeInfo
import dev.leonardo.ocbeacon.ui.screens.chat.ChatMessage
import dev.leonardo.ocbeacon.ui.screens.chat.markdown.PublishedShards
import dev.leonardo.ocbeacon.ui.screens.chat.markdown.ShardDoc
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #442 R2 分片唤醒（A2）——流式 shard 发射契约（#246 同源）：
 * 逆文档序（尾块 Turn 先入列、头块 g0 最末）+ 尾块保原键（锚/帽物主零迁移）
 * + displayEntryStart 钉头块 + shard 命中抑制其他分片路径（键族互斥）。
 */
class StreamShardEntryTest {

    private fun assistant(id: String, partId: String, text: String) = ChatMessage(
        message = Message.Assistant(id = id, sessionId = "s1", time = TimeInfo(2, 2), parentId = "p0"),
        parts = listOf(Part.Text(id = partId, sessionId = "s1", messageId = id, text = text)),
    )

    private fun user(id: String, text: String) = ChatMessage(
        message = Message.User(id = id, sessionId = "s1", time = TimeInfo(1, 1)),
        parts = listOf(Part.Text(id = id + "_p", sessionId = "s1", messageId = id, text = text)),
    )

    private fun published(
        partId: String,
        chunks: List<Pair<Int, Int>>,
        texts: List<String>,
        tailFrom: Int,
    ) = PublishedShards(
        turnKey = "t_m_a",
        partId = partId,
        shards = chunks.zip(texts).mapIndexed { i, (range, text) ->
            ShardDoc(i, range.first, range.second, text)
        },
        tailFrom = tailFrom,
        generation = 1,
    )

    private fun motive(msg: String) = println("[MOTIVE] " + msg)

        @Test
    fun `shard 命中发射逆文档序且尾块保原键`() {
        motive("A2 基线契约：reverseLayout 逆文档序 + 尾块原键（锚/帽物主/跳转零迁移）——泛化不得破坏")
        val doc = "块零\n\n块一\n\n块二\n\n尾块内容"
        val a = assistant("m_a", "p_a", doc)
        val u = user("m_u", "问")
        val displayItems = listOf(0 to a, 1 to u)
        val shards = published(
            "p_a",
            chunks = listOf(0 to 7, 7 to 14),
            texts = listOf("块零\n\n", "块一\n\n"),
            tailFrom = 14,
        )
        val chat = buildChatEntries(
            displayItems = displayItems,
            turnGroups = mapOf(0 to listOf(a)),
            streamingMsgId = "m_a",
            chunkPlans = emptyMap(),
            recentStreamedTurnKeys = emptySet(),
            streamShards = mapOf("p_a" to shards),
        )
        // 逆文档序：尾块 Turn（原键）先入列，g1（新冻结）次之，g0（头块）最末
        assertEquals(listOf("t_m_a", "t_m_a#g1", "t_m_a#g0"), chat.entries.take(3).map { it.key })
        val sc = chat.entries[1] as ChatEntry.StreamChunk
        assertEquals("块一\n\n", sc.text)
        assertEquals(1, sc.chunkIndex)
        assertEquals(2, sc.chunkCount)
        // displayEntryStart 钉头块 g0（跳转落点=turn 首块语义）
        assertEquals(2, chat.displayEntryStart[0])
        // user turn 不受影响
        assertEquals(3, chat.displayEntryStart[1])
    }

    @Test
    fun `shard 命中抑制同 turn 其他分片路径`() {
        motive("键族互斥：shard 与历史 MdChunkPlan 并存的异常态防御——双计划=双渲染")
        // 同 turn 若既有旧 MdChunkPlan 又有 shard 发布（异常态防御）：shard 优先
        val doc = "# 标\n\n" + "内容。\n\n".repeat(20)
        val a = assistant("m_a", "p_a", doc)
        val shards = published("p_a", listOf(0 to 10), listOf("# 标\n\n"), tailFrom = 10)
        val chat = buildChatEntries(
            displayItems = listOf(0 to a),
            turnGroups = mapOf(0 to listOf(a)),
            streamingMsgId = null,
            chunkPlans = mapOf("p_a" to computeChunkPlan("p_a", parseState(doc), minChars = 50, targetChars = 60)!!),
            recentStreamedTurnKeys = emptySet(),
            streamShards = mapOf("p_a" to shards),
        )
        assertTrue(chat.entries.any { it is ChatEntry.StreamChunk })
        assertTrue(chat.entries.none { it is ChatEntry.Chunk })
        // 完结 turn（isStreamingTurn=false）尾块 Turn 保持原键
        assertEquals("t_m_a", chat.entries.first().key)
    }

    @Test
    fun `空发布表行为不变`() {
        motive("无分片零改动——旗标关/未毕业路径逐字节等价的锚点")
        val a = assistant("m_a", "p_a", "短回复")
        val chat = buildChatEntries(
            displayItems = listOf(0 to a),
            turnGroups = mapOf(0 to listOf(a)),
            streamingMsgId = "m_a",
            chunkPlans = emptyMap(),
            recentStreamedTurnKeys = emptySet(),
            streamShards = emptyMap(),
        )
        assertEquals(1, chat.entries.size)
        assertEquals("t_m_a", chat.entries[0].key)
    }

    // ===== #442 A2.5 资格泛化：推理先行轮的 renderItem 级拆分 =====

    private fun reasoningFirst(partId: String, reasoning: String, text: String) = ChatMessage(
        message = Message.Assistant(id = "m_a", sessionId = "s1", time = TimeInfo(2, 2), parentId = "p0"),
        parts = listOf(
            Part.Reasoning(id = partId + "_r", sessionId = "s1", messageId = "m_a", text = reasoning),
            Part.Text(id = partId, sessionId = "s1", messageId = "m_a", text = text),
        ),
    )

    @Test
    fun `推理先行 k 大于 0 发射 StreamPrefix 且钉跳转落点`() {
        motive("A2.5 主场景：推理先行轮 renderItem 级拆分——prefix 最末发射（视觉 turn 顶）+ displayEntryStart 钉 prefix（跳转落点）")
        val a = reasoningFirst("p_a", "思考内容", "块零\n\n块一\n\n尾块内容")
        val shards = published(
            "p_a",
            chunks = listOf(0 to 7),
            texts = listOf("块零\n\n"),
            tailFrom = 7,
        )
        val chat = buildChatEntries(
            displayItems = listOf(0 to a),
            turnGroups = mapOf(0 to listOf(a)),
            streamingMsgId = "m_a",
            chunkPlans = emptyMap(),
            recentStreamedTurnKeys = emptySet(),
            streamShards = mapOf("p_a" to shards),
            shardPartIdx = mapOf("t_m_a" to 1),
        )
        // 逆文档序：尾块 Turn（原键）→ 冻结块 → StreamPrefix（最末=视觉 turn 顶部）
        assertEquals(
            listOf("t_m_a", "t_m_a#g0", "t_m_a#p"),
            chat.entries.map { it.key },
        )
        val prefix = chat.entries.last() as ChatEntry.StreamPrefix
        assertEquals(1, prefix.partIdx)
        assertEquals("t_m_a", prefix.turnKey)
        // displayEntryStart 钉 prefix（turn 头=跳转落点）
        assertEquals(2, chat.displayEntryStart[0])
    }

    @Test
    fun `text-leading k 等于 0 不发射 prefix`() {
        motive("k=0 边界：text-leading 维持 A2 原发射（无空前缀条目——零高条目吃间距的 #456 族坑）")
        val a = assistant("m_a", "p_a", "块零\n\n尾块内容")
        val shards = published("p_a", listOf(0 to 7), listOf("块零\n\n"), tailFrom = 7)
        val chat = buildChatEntries(
            displayItems = listOf(0 to a),
            turnGroups = mapOf(0 to listOf(a)),
            streamingMsgId = "m_a",
            chunkPlans = emptyMap(),
            recentStreamedTurnKeys = emptySet(),
            streamShards = mapOf("p_a" to shards),
            shardPartIdx = mapOf("t_m_a" to 0),
        )
        // k=0：无前缀条目（A2 原发射——displayEntryStart 钉头块）
        assertEquals(listOf("t_m_a", "t_m_a#g0"), chat.entries.map { it.key })
        assertEquals(1, chat.displayEntryStart[0])
    }

    @Test
    fun `无 shardPartIdx 条目的推理先行轮维持原发射`() {
        motive("派生滞后防御：映射缺失时不拆前缀——尾块整 turn 渲染（内容永不因派生缺失而丢失）")
        // 映射缺失（派生滞后/防御）：不拆前缀——尾块整 turn 渲染（今日行为）
        val a = reasoningFirst("p_a", "思考内容", "块零\n\n尾块内容")
        val shards = published("p_a", listOf(0 to 7), listOf("块零\n\n"), tailFrom = 7)
        val chat = buildChatEntries(
            displayItems = listOf(0 to a),
            turnGroups = mapOf(0 to listOf(a)),
            streamingMsgId = "m_a",
            chunkPlans = emptyMap(),
            recentStreamedTurnKeys = emptySet(),
            streamShards = mapOf("p_a" to shards),
        )
        assertEquals(listOf("t_m_a", "t_m_a#g0"), chat.entries.map { it.key })
        assertTrue(chat.entries.none { it is ChatEntry.StreamPrefix })
    }

    private fun parseState(text: String): com.mikepenz.markdown.model.State.Success =
        kotlinx.coroutines.runBlocking {
            val normalized = dev.leonardo.ocbeacon.ui.screens.chat.markdown.normalizeForRender(text, isUser = false)
            kotlinx.coroutines.withTimeout(10_000) {
                com.mikepenz.markdown.model.parseMarkdownFlow(normalized)
                    .first { it is com.mikepenz.markdown.model.State.Success }
            } as com.mikepenz.markdown.model.State.Success
        }
}
