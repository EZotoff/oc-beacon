package dev.leonardo.ocbeacon.ui.screens.chat.components

import dev.leonardo.ocbeacon.domain.model.Part
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #442 B案（spec 2026-10-02 §2.1）——StreamingDeltaBus 语义测试：
 * 发布/清除/回退契约（`live ?: part.text` 消费端依赖的不变量）。
 */
class StreamingDeltaBusTest {

    @org.junit.Before
    fun assumeFlagOn() {
        // 双臂纪律：本类断言旗标开语义——旗标关臂（-POCBEACON_STREAM_FLAGS_OFF=true
        // 的 test 任务）显式跳过而非假红
        org.junit.Assume.assumeTrue(StreamingDeltaBus.enabled)
    }

    @After
    fun tearDown() {
        StreamingDeltaBus.clearAll()
    }

    private fun text(id: String, t: String, end: Long? = null) =
        Part.Text(id = id, sessionId = "s1", messageId = "m1", text = t,
            time = Part.Text.Time(start = 1L, end = end))

    private fun motive(msg: String) = println("[MOTIVE] " + msg)

        @Test
    fun `publishParts accumulates by landed part id and flags kind`() {
        motive("键=落位 part.id（#87b 内容匹配合并族下 delta 派生 id 可异于落位 id）——UI 查询命中的前提")
        StreamingDeltaBus.publishParts(listOf(text("p1", "Hel")))
        StreamingDeltaBus.publishParts(listOf(text("p1", "Hello")))

        assertEquals("Hello", StreamingDeltaBus.live.value["p1"]?.text)
        assertFalse(StreamingDeltaBus.live.value["p1"]!!.reasoning)

        StreamingDeltaBus.publishParts(listOf(
            Part.Reasoning(id = "p2", sessionId = "s1", messageId = "m1", text = "think"),
        ))
        assertTrue(StreamingDeltaBus.live.value["p2"]!!.reasoning)
    }

    @Test
    fun `empty text removes coverage`() {
        motive("空文本撤销覆盖——part 清空（重生成清场族）不留幽灵")
        StreamingDeltaBus.publishParts(listOf(text("p1", "Hel")))
        assertTrue(StreamingDeltaBus.live.value.containsKey("p1"))

        StreamingDeltaBus.publishParts(listOf(text("p1", "")))

        assertNull(StreamingDeltaBus.live.value["p1"])
    }

    @Test
    fun `clearPart and clearParts drop coverage`() {
        motive("终态/批量撤销通道——完结与 REST 合并族的让位机制")
        StreamingDeltaBus.publishParts(listOf(text("p1", "a"), text("p2", "b")))
        StreamingDeltaBus.clearPart("p1")
        assertNull(StreamingDeltaBus.live.value["p1"])
        assertEquals("b", StreamingDeltaBus.live.value["p2"]?.text)

        StreamingDeltaBus.clearParts(listOf("p2"))
        assertTrue(StreamingDeltaBus.live.value.isEmpty())
    }

    @Test
    fun `liveFor emits null fallback then published growth`() = runTest {
        motive("消费端契约 live ?: part.text 的通道语义：初值 null 回退 + 增长逐帧（distinct 防同值重启）")
        val emissions = mutableListOf<String?>()
        val job = launch {
            StreamingDeltaBus.liveFor("p1").collect { emissions.add(it) }
        }
        // 初值 null（消费端回退 part.text）
        withTimeout(1000) { while (emissions.isEmpty()) kotlinx.coroutines.delay(1) }
        assertNull(emissions.first())

        // 逐发布等收集器追平（StateFlow 合并语义：慢消费者跳过中间帧——
        // 单测锚逐帧序，合并行为由 distinct 断言钉底）
        StreamingDeltaBus.publishParts(listOf(text("p1", "Hel")))
        withTimeout(1000) { while (emissions.lastOrNull() != "Hel") kotlinx.coroutines.delay(1) }
        StreamingDeltaBus.publishParts(listOf(text("p1", "Hello")))
        withTimeout(1000) { while (emissions.lastOrNull() != "Hello") kotlinx.coroutines.delay(1) }

        assertEquals(listOf(null, "Hel", "Hello"), emissions)
        job.cancel()
    }

    @Test
    fun `liveFor clears back to null on clearPart`() = runTest {
        motive("完结后回退通道：清覆盖→null→参数路径接管（换装无缝）")
        StreamingDeltaBus.publishParts(listOf(text("p1", "Hel")))
        assertEquals("Hel", StreamingDeltaBus.liveFor("p1").first())

        StreamingDeltaBus.clearPart("p1")

        assertNull(StreamingDeltaBus.liveFor("p1").first())
    }
}
