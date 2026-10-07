package dev.leonardo.ocbeacon.ui.screens.chat.components

import dev.leonardo.ocbeacon.domain.model.Part
import dev.leonardo.ocbeacon.domain.model.SseEvent
import dev.leonardo.ocbeacon.domain.model.ToolRef
import dev.leonardo.ocbeacon.ui.screens.chat.tools.PartGroup
import dev.leonardo.ocbeacon.ui.screens.chat.tools.RenderItem
import dev.leonardo.ocbeacon.ui.screens.chat.tools.RenderableTurn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #442 A2.5 纯函数族分支测试（spec 2026-10-02 §2.3）：
 * 注册资格 shardRegistrationPartId + 提问锚定 questionAnchorPartIdFor/
 * questionAnchorInPrefix 的全分支覆盖。
 *
 * 每测首行 [motive] 输出动机埋点——测试报告 stdout 可追溯「为何测此路径」
 * （与生产 [A2.5] 探针对应：日志埋点传达测试动机）。
 */
class ShardEligibilityAndAnchorTest {

    private fun motive(msg: String) = println("[MOTIVE] $msg")

    private fun single(part: Part) = RenderItem.GroupedParts(PartGroup.Single(part))

    private fun text(id: String, end: Long? = null) =
        Part.Text(id = id, sessionId = "s1", messageId = "m1", text = "x",
            time = Part.Text.Time(start = 1L, end = end))

    private fun reasoning(id: String) =
        Part.Reasoning(id = id, sessionId = "s1", messageId = "m1", text = "r")

    private fun turn(vararg items: RenderItem) = RenderableTurn(
        renderItems = items.toList(),
        isEmpty = items.isEmpty(),
        errorText = null, agentName = null, modelId = null,
        durationMs = null, turnStartMs = null, completedTimeMs = null, stepFinishes = emptyList(),
        taskAgentName = null, copyText = null,
    )

    private fun question(callId: String? = null) = SseEvent.QuestionAsked(
        id = "q1", sessionId = "s1",
        questions = listOf(SseEvent.QuestionAsked.Question(
            header = "h", question = "q",
            options = listOf(SseEvent.QuestionAsked.Option("y", "d")),
        )),
        tool = callId?.let { ToolRef(messageId = "m1", callId = it) },
    )

    // ===== shardRegistrationPartId 分支 =====

    @Test
    fun `资格 - text-leading 首位 text`() {
        motive("A2 平权回归：text-leading（index 0）仍取首个 text——A2.5 放宽不得破坏 A2 原资格")
        assertEquals("t0", shardRegistrationPartId(listOf(single(text("t0"))), hasLiveQuestion = false))
    }

    @Test
    fun `资格 - 推理先行任意位置 text`() {
        motive("A2.5 主场景：推理先行轮（glm/LongCat 常态）首个 text 在 k>0 也注册——37K 零分片缺口关闭")
        val items = listOf(single(reasoning("r0")), single(text("t1")))
        assertEquals("t1", shardRegistrationPartId(items, hasLiveQuestion = false))
    }

    @Test
    fun `资格 - 已完结在前未完结在后取增长源`() {
        motive("多 text part turn：优先未完结（增长源=Fire 前提）；已完结 part 无 append 无 Fire，注册无用")
        val items = listOf(single(text("t0", end = 9L)), single(text("t1")))
        assertEquals("t1", shardRegistrationPartId(items, hasLiveQuestion = false))
    }

    @Test
    fun `资格 - 全完结回退首个 text`() {
        motive("A2 平权回退：无流式 text 时取首个（无增长无 Fire，注册无害——完结持续性兜底）")
        val items = listOf(single(text("t0", end = 9L)))
        assertEquals("t0", shardRegistrationPartId(items, hasLiveQuestion = false))
    }

    @Test
    fun `资格 - 无 text 返回 null`() {
        motive("纯推理/工具 turn 无可分片体——null=不注册原路径（防御分支）")
        assertNull(shardRegistrationPartId(listOf(single(reasoning("r0"))), hasLiveQuestion = false))
        assertNull(shardRegistrationPartId(null, hasLiveQuestion = false))
    }

    @Test
    fun `资格 - 活提问暂缓`() {
        motive("提问卡锚定语义依赖整 turn 渲染（ChunkAssistantItems 前缀分工仅覆盖已发布态）——活提问在场暂缓注册，Q 解决后下一批恢复")
        assertNull(shardRegistrationPartId(listOf(single(text("t0"))), hasLiveQuestion = true))
    }

    @Test
    fun `资格 - 旗标关或非流式返回 null`() {
        motive("pilotEnabled 参数化旗标关/非流式 turn——零改造原路径的回退分支（B6 基线构建依赖）")
        assertNull(shardRegistrationPartId(listOf(single(text("t0"))), hasLiveQuestion = false, pilotEnabled = false))
    }

    // ===== questionAnchorPartIdFor 分支 =====

    @Test
    fun `锚定 - question 为 null`() {
        motive("无提问即无锚（空档分支——effectiveAnchorId=null 槽位不渲染，#456 间距根修语义）")
        assertNull(questionAnchorPartIdFor(null, turn(single(text("t0")))))
    }

    private fun tool(id: String, callId: String) = Part.Tool(
        id = id, sessionId = "s1", messageId = "m1", callId = callId,
        tool = "read", state = dev.leonardo.ocbeacon.domain.model.ToolState.Pending(),
    )

    @Test
    fun `锚定 - tool callId 精确匹配优先于位置回退`() {
        motive("callId 匹配只认 Tool part 且优先于「最后 R/T」回退（多工具轮问卡挂对工具——原语义提纯后必须等价）")
        val r0 = reasoning("r0"); val tool1 = tool("tool1", "c_a"); val tool2 = tool("tool2", "c_b")
        val turn = turn(single(r0), single(tool1), single(tool2))
        assertEquals("tool1", questionAnchorPartIdFor(question(callId = "c_a"), turn))
    }

    @Test
    fun `锚定 - callId 指向非 Tool part 不命中走回退`() {
        motive("防御边界：callId 只匹配 Tool part——指向 text/reasoning id 不命中（id 巧合同名族），按最后 R/T 回退")
        val r0 = reasoning("r0"); val t1 = text("t1"); val r2 = reasoning("r2")
        val turn = turn(single(r0), single(t1), single(r2))
        assertEquals("r2", questionAnchorPartIdFor(question(callId = "t1"), turn))
    }

    @Test
    fun `锚定 - callId 未命中回退最后 Reasoning 或 Tool`() {
        motive("waterfall 无 tool 锚的回退：最后 R/T Single part=『思考流末尾』原语义")
        val r0 = reasoning("r0"); val t1 = text("t1"); val r2 = reasoning("r2")
        val turn = turn(single(r0), single(t1), single(r2))
        assertEquals("r2", questionAnchorPartIdFor(question(callId = "nope"), turn))
    }

    @Test
    fun `锚定 - 无 R 或 T 返回 null`() {
        motive("纯 text turn 无锚（text-leading 分片轮提问走 unembedded 保底路径的历史现状）")
        assertNull(questionAnchorPartIdFor(question(), turn(single(text("t0")))))
    }

    // ===== questionAnchorInPrefix 分支 =====

    @Test
    fun `分工 - 锚在前缀区间为真`() {
        motive("A2.5 提问分工主路径：锚（推理区）落在 [0,k) → 前缀条目渲染问卡、尾块切片不含锚防丢卡")
        val r0 = reasoning("r0"); val t1 = text("t1")
        val turn = turn(single(r0), single(t1))
        assertTrue(questionAnchorInPrefix(question(), turn, partIdx = 1))
    }

    @Test
    fun `分工 - 锚等于分片位为假`() {
        motive("边界：锚==k（text 自身不可能是 R/T 锚，防御）——尾块职责")
        val r0 = reasoning("r0"); val t1 = text("t1")
        val turn = turn(single(r0), single(t1))
        assertFalse(questionAnchorInPrefix(question(), turn, partIdx = 0))
    }

    @Test
    fun `分工 - 锚在尾块区间为假`() {
        motive("锚在 [k, size)（尾块 text 后的 tool）→ 尾块 MessageCardAssistant 切片内自渲染")
        val r0 = reasoning("r0"); val t1 = text("t1"); val tool2 = reasoning("r2")
        val turn = turn(single(r0), single(t1), single(tool2))
        assertFalse(questionAnchorInPrefix(question(), turn, partIdx = 1))
    }

    @Test
    fun `分工 - 前缀深位时锚在首个前缀项仍为真`() {
        motive("多前缀项（推理+工具卡）时锚可在前缀任意位（含首位）——分工只看区间不看位次")
        val r0 = reasoning("r0"); val tool1 = tool("tool1", "c_x"); val t1 = text("t1")
        val turn = turn(single(r0), single(tool1), single(t1))
        assertTrue(questionAnchorInPrefix(question(), turn, partIdx = 2))
    }

    @Test
    fun `分工 - 锚定计算与定位同源恒可定位`() {
        motive("防御分支说明：indexOfFirst==-1 不可自然构造——锚 id 恒出自同一 turn 的 Single 扫描；锚不存在时 PartIdFor 先行返回 null 已短路，此处钉语义：无锚不转移职责")
        val turn = turn(single(text("t0")))
        assertFalse(questionAnchorInPrefix(question(), turn, partIdx = 1))
    }
}
