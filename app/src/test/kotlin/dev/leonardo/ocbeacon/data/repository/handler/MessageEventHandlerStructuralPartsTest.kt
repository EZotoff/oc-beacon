package dev.leonardo.ocbeacon.data.repository.handler

import dev.leonardo.ocbeacon.domain.model.MergeStrategy
import dev.leonardo.ocbeacon.domain.model.Message
import dev.leonardo.ocbeacon.domain.model.MessageWithParts
import dev.leonardo.ocbeacon.domain.model.Part
import dev.leonardo.ocbeacon.domain.model.SseEvent
import dev.leonardo.ocbeacon.domain.model.TimeInfo
import dev.leonardo.ocbeacon.ui.screens.chat.components.StreamingDeltaBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * #442 B案 节奏收编（spec 2026-10-02 §2.1）——数据层发布策略表征测试：
 *
 * 1. 流式 delta 批：`_parts` 热视图照旧累积（既有读点语义零变更），
 *    structuralParts **零发射**（根因二收口），StreamingDeltaBus 发布累积全文；
 * 2. 结构性事件（PartUpdated/upsert 直调/clear 族）：structuralParts 发射 +
 *    终态 part 的 bus 覆盖撤销。
 *
 * 计数器确定性：收集器挂 Unconfined——StateFlow 赋值在测试线程上同步回调。
 */
class MessageEventHandlerStructuralPartsTest {

    private lateinit var handler: MessageEventHandler
    private val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
    private val structuralEmissions = AtomicInteger(0)
    @Volatile private var lastStructural: Map<String, List<Part>>? = null

    @Before
    fun setUp() {
        handler = MessageEventHandler()
        StreamingDeltaBus.clearAll()
        structuralEmissions.set(0)
        lastStructural = null
        scope.launch {
            handler.structuralParts.collect { map ->
                lastStructural = map
                structuralEmissions.incrementAndGet()
            }
        }
    }

    @After
    fun tearDown() {
        StreamingDeltaBus.clearAll()
        scope.cancel()
    }

    private fun seedMessage(messageId: String, part: Part) {
        handler.upsertMessages(
            "s1",
            listOf(MessageWithParts(
                Message.Assistant(
                    id = messageId,
                    sessionId = "s1",
                    parentId = "",
                    time = TimeInfo(created = 1000L),
                    modelId = "test-model",
                ),
                listOf(part),
            )),
            MergeStrategy.SSE_PRIORITY,
        )
    }

    private fun delta(delta: String, partId: String = "m1_text_ord_0", field: String = "text") {
        handler.handleMessagePartDelta(SseEvent.MessagePartDelta(
            sessionId = "s1", messageId = "m1", partId = partId, field = field, delta = delta,
        ))
    }

    @Test
    fun `delta flush keeps hot view, silent structural, publishes bus`() {
        seedMessage("m1", Part.Text(id = "m1_text_ord_0", sessionId = "s1", messageId = "m1", text = "Hel"))
        val baseline = structuralEmissions.get()

        delta("lo")
        delta("!")
        handler.forceFlushDeltas()

        // 热视图照旧（isStaleDelta/持久化读点语义零变更）
        assertEquals(
            "Hello!",
            handler.parts.value["m1"]?.firstOrNull { it.id == "m1_text_ord_0" }?.let { (it as Part.Text).text },
        )
        // 结构性视图零发射（根因二：combine 源静默）
        assertEquals(baseline, structuralEmissions.get())
        // bus 快通道携带累积全文（键=落位 part.id）
        assertEquals("Hello!", StreamingDeltaBus.live.value["m1_text_ord_0"]?.text)
        assertFalse(StreamingDeltaBus.live.value["m1_text_ord_0"]!!.reasoning)
    }

    @Test
    fun `terminal PartUpdated publishes structural and clears bus coverage`() {
        seedMessage("m1", Part.Text(id = "m1_text_ord_0", sessionId = "s1", messageId = "m1", text = "Hel"))
        delta("lo")
        handler.forceFlushDeltas()
        assertTrue(StreamingDeltaBus.live.value.containsKey("m1_text_ord_0"))
        val baseline = structuralEmissions.get()

        // text.ended 权威替换（终态：time.end ≠ 0）
        handler.handle(
            SseEvent.MessagePartUpdated(
                Part.Text(
                    id = "m1_text_ord_0", sessionId = "s1", messageId = "m1", text = "Hello!",
                    time = Part.Text.Time(start = 1L, end = 99L),
                ),
            ),
            "srv",
        )

        assertTrue(structuralEmissions.get() > baseline)
        assertNull(StreamingDeltaBus.live.value["m1_text_ord_0"])
        assertEquals(
            "Hello!",
            (lastStructural?.get("m1")?.firstOrNull { it.id == "m1_text_ord_0" } as Part.Text).text,
        )
    }

    @Test
    fun `upsertMessages direct entry publishes structural and clears bus for incoming parts`() {
        seedMessage("m1", Part.Text(id = "m1_text_ord_0", sessionId = "s1", messageId = "m1", text = "Hel"))
        delta("lo")
        handler.forceFlushDeltas()
        assertTrue(StreamingDeltaBus.live.value.containsKey("m1_text_ord_0"))
        val baseline = structuralEmissions.get()

        seedMessage("m1", Part.Text(id = "m1_text_ord_0", sessionId = "s1", messageId = "m1", text = "Hello"))

        assertTrue(structuralEmissions.get() > baseline)
        assertNull(StreamingDeltaBus.live.value["m1_text_ord_0"])
    }

    @Test
    fun `clearAll empties hot view, structural and bus`() {
        seedMessage("m1", Part.Text(id = "m1_text_ord_0", sessionId = "s1", messageId = "m1", text = "Hel"))
        delta("lo")
        handler.forceFlushDeltas()
        assertTrue(StreamingDeltaBus.live.value.isNotEmpty())

        handler.clearAll()

        assertTrue(handler.parts.value.isEmpty())
        assertTrue(StreamingDeltaBus.live.value.isEmpty())
    }

    @Test
    fun `reasoning deltas publish bus with reasoning flag`() {
        seedMessage("m1", Part.Reasoning(id = "m1_reasoning_ord_0", sessionId = "s1", messageId = "m1", text = "th"))
        delta("ink", partId = "m1_reasoning_ord_0", field = "reasoning")
        handler.forceFlushDeltas()

        assertEquals("think", StreamingDeltaBus.live.value["m1_reasoning_ord_0"]?.text)
        assertTrue(StreamingDeltaBus.live.value["m1_reasoning_ord_0"]!!.reasoning)
    }
}
