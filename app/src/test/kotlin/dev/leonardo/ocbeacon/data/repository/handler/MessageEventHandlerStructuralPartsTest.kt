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
        motive("根修主断言：流式批只走热视图+bus，structuralParts 零发射——CML-tick 归零前提的数据层侧证明")
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
        motive("R5 完结换装：text.ended 权威经 structural 过桥 + bus 撤销覆盖——消费端回退参数即终态")
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
        motive("R6 REST 直调入口：合并后发布结构视图 + 撤销触及 part 覆盖（服务端真相优先于陈旧累积）")
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
        motive("全清族三视图一致归零——防跨会话陈旧状态残留")
        seedMessage("m1", Part.Text(id = "m1_text_ord_0", sessionId = "s1", messageId = "m1", text = "Hel"))
        delta("lo")
        handler.forceFlushDeltas()
        assertTrue(StreamingDeltaBus.live.value.isNotEmpty())

        handler.clearAll()

        assertTrue(handler.parts.value.isEmpty())
        assertTrue(StreamingDeltaBus.live.value.isEmpty())
    }

    @Test
    fun `delta events through handle do not bridge flushed state into structural`() {
        motive("B6 真机定罪回归锚：flush 后首个 delta 事件不得把累积态过桥——否则结构性静默被击穿（曾致 CML-tick 6/s）")
        // B6 真机定罪回归锚：flush 改热视图后，后续 MessagePartDelta 事件经
        // handle() 分发不得把「已含本批累积」的热视图新值过桥进结构性视图
        //（否则每 flush 后首个 delta 击穿静默 → combine 恢复每批滴答）
        seedMessage("m1", Part.Text(id = "m1_text_ord_0", sessionId = "s1", messageId = "m1", text = "Hel"))
        delta("lo")
        handler.forceFlushDeltas()
        assertEquals("Hello", StreamingDeltaBus.live.value["m1_text_ord_0"]?.text)
        val baseline = structuralEmissions.get()

        handler.handle(
            SseEvent.MessagePartDelta(
                sessionId = "s1", messageId = "m1", partId = "m1_text_ord_0",
                field = "text", delta = "!",
            ),
            "srv",
        )

        assertEquals(baseline, structuralEmissions.get())
        // 热视图不受影响（缓冲照旧）
        assertEquals(
            "Hello",
            handler.parts.value["m1"]?.firstOrNull { it.id == "m1_text_ord_0" }?.let { (it as Part.Text).text },
        )
    }

    @Test
    fun `reasoning deltas publish bus with reasoning flag`() {
        motive("R1 推理必须进范围：reasoning delta 经 bus 带推理标记发布——glm 系 Waiting 期根修的前提通道")
        seedMessage("m1", Part.Reasoning(id = "m1_reasoning_ord_0", sessionId = "s1", messageId = "m1", text = "th"))
        delta("ink", partId = "m1_reasoning_ord_0", field = "reasoning")
        handler.forceFlushDeltas()

        assertEquals("think", StreamingDeltaBus.live.value["m1_reasoning_ord_0"]?.text)
        assertTrue(StreamingDeltaBus.live.value["m1_reasoning_ord_0"]!!.reasoning)
    }

    // ===== 分支补全（2026-10-02 全面覆盖批）：每测首行 motive 输出动机 =====

    private fun motive(msg: String) = println("[MOTIVE] $msg")

    @Test
    fun `PartRemoved 撤销 bus 覆盖并发布结构性视图`() {
        motive("R6 移除路径：part 拆除后 live 覆盖必须撤销，否则陈旧累积遮蔽删除语义（幽灵文本）")
        seedMessage("m1", Part.Text(id = "m1_text_ord_0", sessionId = "s1", messageId = "m1", text = "Hel"))
        delta("lo")
        handler.forceFlushDeltas()
        assertTrue(StreamingDeltaBus.live.value.containsKey("m1_text_ord_0"))
        val baseline = structuralEmissions.get()

        handler.handle(
            SseEvent.MessagePartRemoved(sessionId = "s1", messageId = "m1", partId = "m1_text_ord_0"),
            "srv",
        )

        assertNull(StreamingDeltaBus.live.value["m1_text_ord_0"])
        assertTrue(structuralEmissions.get() > baseline)
    }

    @Test
    fun `TimePatch 终态化撤销 bus 覆盖`() {
        motive("DSH block-end 路径（#453）：无 kind 终态化信号同样要撤销覆盖——服务端族多样性不能漏")
        // 派生 id 契约：TimePatch 按 _ord_{ordinal} 后缀定位
        seedMessage("m1", Part.Text(id = "m1_text_ord_0", sessionId = "s1", messageId = "m1", text = "Hel"))
        delta("lo")
        handler.forceFlushDeltas()
        assertTrue(StreamingDeltaBus.live.value.containsKey("m1_text_ord_0"))
        val baseline = structuralEmissions.get()

        handler.handle(
            SseEvent.MessagePartTimePatch(sessionId = "s1", messageId = "m1", ordinal = 0L, endMs = 99L),
            "srv",
        )

        assertNull(StreamingDeltaBus.live.value["m1_text_ord_0"])
        assertTrue(structuralEmissions.get() > baseline)
    }

    @Test
    fun `非终态 PartUpdated 保留 bus 覆盖（流式连续性）`() {
        motive("流式中 part.updated（元数据补齐）不得误清覆盖——否则 bus 通道间歇失活回退参数路径，根修退化")
        seedMessage("m1", Part.Text(id = "m1_text_ord_0", sessionId = "s1", messageId = "m1", text = "Hel"))
        delta("lo")
        handler.forceFlushDeltas()
        assertTrue(StreamingDeltaBus.live.value.containsKey("m1_text_ord_0"))

        // 非终态（end=null）的权威替换——time.start 补齐族
        handler.handle(
            SseEvent.MessagePartUpdated(
                Part.Text(id = "m1_text_ord_0", sessionId = "s1", messageId = "m1", text = "Hello",
                    time = Part.Text.Time(start = 5L)),
            ),
            "srv",
        )

        // 覆盖保留：流式仍在飞，live 通道不得中断
        assertEquals("Hello", StreamingDeltaBus.live.value["m1_text_ord_0"]?.text)
    }

    @Test
    fun `REST 合并后下一 flush 以合并基线重发布（连续性闭环）`() {
        motive("R5/R6 连续性闭环：upsert 清覆盖后流仍在飞时，下一 flush 以合并后基线重建 bus——防永久失活")
        seedMessage("m1", Part.Text(id = "m1_text_ord_0", sessionId = "s1", messageId = "m1", text = "Hel"))
        delta("lo")
        handler.forceFlushDeltas()
        // REST 刷新落库合并（服务端权威文本=Hello!）
        seedMessage("m1", Part.Text(id = "m1_text_ord_0", sessionId = "s1", messageId = "m1", text = "Hello!"))
        assertNull(StreamingDeltaBus.live.value["m1_text_ord_0"])

        // 流仍在飞：后续 delta 在合并基线上续涨 → bus 重建
        delta(" World")
        handler.forceFlushDeltas()

        assertEquals("Hello! World", StreamingDeltaBus.live.value["m1_text_ord_0"]?.text)
    }

    @Test
    fun `clearForSession 清 bus 覆盖并发布结构性视图`() {
        motive("R6 会话清理：切数据/删会话后残留覆盖会跨会话泄漏陈旧文本——先于热视图移除捕获 part ids")
        seedMessage("m1", Part.Text(id = "m1_text_ord_0", sessionId = "s1", messageId = "m1", text = "Hel"))
        delta("lo")
        handler.forceFlushDeltas()
        assertTrue(StreamingDeltaBus.live.value.isNotEmpty())
        val baseline = structuralEmissions.get()

        handler.clearForSession("s1")

        assertTrue(StreamingDeltaBus.live.value.isEmpty())
        assertTrue(structuralEmissions.get() > baseline)
    }

    @Test
    fun `patchToolChildSession 变更发布结构性且无变更补丁零发射`() {
        motive("直调入口族（dispatch 外写点，#216）：子会话补丁真实变更必须过桥——漏发布=UI 永不追平；无匹配 part 的 no-op 补丁经值相等去重零发射（防无意义重组，同 publishStructural 幂等语义）")
        seedMessage("m1", Part.Tool(
            id = "tp", sessionId = "s1", messageId = "m1", callId = "call_x", tool = "task",
            state = dev.leonardo.ocbeacon.domain.model.ToolState.Running(),
        ))
        val baseline = structuralEmissions.get()

        // no-op（callId 无匹配）→ 热视图未变 → 值相等去重零发射
        handler.patchToolChildSession("s1", "ghost", "ses_c")
        assertEquals(baseline, structuralEmissions.get())

        // 真实变更（Running 工具卡补子会话元数据）→ 结构性发布
        handler.patchToolChildSession("s1", "call_x", "ses_child")
        assertTrue(structuralEmissions.get() > baseline)
    }

    @Test
    fun `pruneRevertedMessages 发布结构性视图`() {
        motive("撤销裁剪（EventDispatcher.clearRevert 路径）：裁剪后 UI 必须立即收缩——否则已撤销消息短暂重现")
        seedMessage("m1", Part.Text(id = "m1_text_ord_0", sessionId = "s1", messageId = "m1", text = "Hel"))
        val baseline = structuralEmissions.get()

        handler.pruneRevertedMessages("s1", revertMessageId = "m0")

        assertTrue(structuralEmissions.get() > baseline)
    }
}
