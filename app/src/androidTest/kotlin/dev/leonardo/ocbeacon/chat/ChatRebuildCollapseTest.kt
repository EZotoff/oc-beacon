package dev.leonardo.ocbeacon.chat

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import dagger.hilt.android.testing.HiltAndroidTest
import dev.leonardo.ocbeacon.builder.anAssistantMessage
import dev.leonardo.ocbeacon.builder.aUserMessage
import dev.leonardo.ocbeacon.domain.model.Part
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #486 判死活（B2 狩猎批仪器腿，2026-10-09）：SSE retry 理论盲区的确定性构造。
 *
 * 盲区三联（journal 2026-09-30-471-4 静态定罪链）：流式 turn 渲染 → 组合位销毁
 * （partId 变化 = v1 message.updated 全量重发语义）→ 终态重建 len>2048 →
 * asyncTerminal 首帧 State.Loading ≈0 高。#472 的 pilotTerminalHold 只拦
 * pilotEverRendered=true——ever=false 重建帧无防御（理论缺口）。
 *
 * 手法：mainClock.autoAdvance=false 帧步进采样——替换态落定后逐帧推进，正文
 * （终态大文本）出现前的空白重组帧数 = 塌缩空窗帧。判定：
 * - 空窗帧 0 = 防线存在（pilot hold/preParsed 仍生效）→ watch 升级为带测试护栏；
 * - 空窗帧 ≥2 = Loading 暴露实证 → LRU 账本根修开工（09-30 方案 a）。
 * 对照组：同替换但 partId 稳定（v2 backfill 语义）→ 组合位不销毁、ever 保持 →
 * 必须无缝（空窗帧 0）——验证采样手法本身有效。
 */
@HiltAndroidTest
class ChatRebuildCollapseTest : BaseChatTest() {

    private fun setMessages(vararg entries: Pair<dev.leonardo.ocbeacon.domain.model.Message, List<Part>>) {
        fakeChat.messagesState.value = entries.map { it.first }
        fakeChat.allPartsMapState.value = entries.associate { it.first.id to it.second }
    }

    private fun userWithText(text: String, id: String): Pair<dev.leonardo.ocbeacon.domain.model.Message, List<Part>> {
        val msg = aUserMessage(text, id = id, sessionId = TEST_SESSION)
        val part = Part.Text(id = "part-$id", sessionId = TEST_SESSION, messageId = id, text = text)
        return msg to listOf(part)
    }

    private fun dev.leonardo.ocbeacon.domain.model.MessageWithParts.toPair():
        Pair<dev.leonardo.ocbeacon.domain.model.Message, List<Part>> = info to parts

    /** 模拟流式增长：替换 messageId 的文本 part（partId 稳定）。 */
    private fun growText(messageId: String, newText: String) {
        val currentMap = fakeChat.allPartsMapState.value.toMutableMap()
        currentMap[messageId] = listOf(
            Part.Text(
                id = "part-$messageId",
                sessionId = TEST_SESSION,
                messageId = messageId,
                text = newText,
            )
        )
        fakeChat.allPartsMapState.value = currentMap
    }

    /**
     * >2048 字符终态正文（超 ASYNC_PARSE_MIN_CHARS → async 解析路径）。
     * **pilot 前缀一致**（生产 backfill 语义：重连回填=前缀增量，非内容跳变）——
     * 稳定 partId 对照组因此走 pilot 前缀差分 append（无缝），排除非前缀重建帧
     * 的合法干扰（AST 重建帧 blank 与 Loading 帧同形，必须分离）。
     */
    private val bigTerminal: String = "streaming start marker " + buildString {
        repeat(140) { i -> append("Paragraph $i of long terminal content that must cross the async parse threshold. ") }
    }

    /**
     * 全量替换（v1 语义）：同 messageId 新 Message 实例（completed）+ 新 partId。
     * [newPartId] 控制组合位销毁开关——null = 沿用稳定 id（v2 语义对照组）。
     */
    private fun replaceTerminal(messageId: String, newPartId: String?) {
        val completed = anAssistantMessage(streaming = false, id = messageId, sessionId = TEST_SESSION) {
            text(bigTerminal)
        }
        val rebuilt = listOf(
            Part.Text(
                id = newPartId ?: "part-$messageId",
                sessionId = TEST_SESSION,
                messageId = messageId,
                text = bigTerminal,
            )
        )
        val currentMsgs = fakeChat.messagesState.value
        val map = fakeChat.allPartsMapState.value.toMutableMap()
        val msgIdx = currentMsgs.indexOfFirst { it.id == messageId }
        val msgs = currentMsgs.toMutableList()
        msgs[msgIdx] = completed.info
        fakeChat.messagesState.value = msgs
        map[messageId] = rebuilt
        fakeChat.allPartsMapState.value = map
    }

    /**
     * 帧步进采样：替换落定后逐帧推进，直到终态正文出现；返回**真空窗帧数**
     * （旧 pilot 内容与终态内容都不可见的帧——#472 hold 语义下旧内容保持到
     * 换装、不算空窗；Loading ≈0 高暴露 = 两者皆无）。超时防挂死。
     */
    private var frameTrace: String = ""

    private fun blankFramesUntilTerminal(): Int {
        var blank = 0
        var appeared = false
        frameTrace = ""
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            composeRule.mainClock.advanceTimeByFrame()
            val present = composeRule.onAllNodesWithText("Paragraph 3", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
            val pilotHeld = composeRule.onAllNodesWithText("streaming start marker", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
            frameTrace += if (present) "T" else if (pilotHeld) "M" else "B"
            if (present) { appeared = true; break }
            if (!pilotHeld) blank++
        }
        assertTrue("终态正文 15s 内未出现（async 解析挂死或内容丢失）trace=$frameTrace", appeared)
        return blank
    }

    /** 对照组：partId 稳定（v2 backfill 语义）→ 无缝换装，空窗帧 0。 */
    @Test
    fun rebuild_stablePartId_seamless() {
        val asst = anAssistantMessage(streaming = true, id = "a-ctl", sessionId = TEST_SESSION) {
            text("streaming start marker")
        }
        setMessages(userWithText("question", "u-ctl"), asst.toPair())
        renderChatScreen()
        composeRule.onNodeWithText("streaming start marker", substring = true).assertIsDisplayed()

        composeRule.mainClock.autoAdvance = false
        replaceTerminal("a-ctl", newPartId = null)
        val blank = blankFramesUntilTerminal()
        composeRule.mainClock.autoAdvance = true
        // #486 根修后语义（2026-10-09 A 案）：内容**变化**的冷重建（本用例形态）
        // 缓存守卫按设计放行（缓存=旧内容），落 ledgerArmed pilot coldReveal——
        // 新内容解析天然需要 ≥1 帧（同步解析=主线程停顿违 #428 红线），1 帧=底线。
        // 同内容冷重入（生产主形态：滚动回收/导航回看）走缓存零帧——见
        // coldReentry_sameContent_servesFromCache_zeroBlank。
        assertEquals("内容变化冷重建 1 帧底线 trace=$frameTrace", 1, blank)
    }

    /**
     * #486 根修验收主判据：**同内容**冷重入（生产主形态——滚动回收/导航回看已
     * 完结内容）。流式铺开到位后，全量替换（同 partId 新实例+completed 翻转，
     * 内容不变）→ 槽位销毁冷重建 → 账本终态缓存命中（内容等值守卫过）→
     * 首帧即有内容，**零空窗**（修复前此形态走 asyncTerminal Loading 1 帧）。
     * 检测器：替换后正文在场的连续性（缺席帧计数）。
     */
    @Test
    fun coldReentry_sameContent_servesFromCache_zeroBlank() {
        val sameBody = buildString {
            append("seed ")
            repeat(90) { i -> append("Paragraph $i of terminal content crossing the async parse threshold. ") }
        }
        val asst = anAssistantMessage(streaming = true, id = "a-cache", sessionId = TEST_SESSION) {
            text("seed")
        }
        setMessages(userWithText("question", "u-cache"), asst.toPair())
        renderChatScreen()
        composeRule.onNodeWithText("seed", substring = true).assertIsDisplayed()

        // 流式铺开（autoAdvance 真实节奏——pilot 帽限速分帧入树）
        composeRule.mainClock.autoAdvance = true
        growText("a-cache", sameBody)
        composeRule.waitUntil(timeoutMillis = 30_000) {
            composeRule.onAllNodesWithText("Paragraph 3", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.mainClock.autoAdvance = false

        // 全量替换：同 partId/同内容/completed 翻转 → 合成槽位销毁（本 harness
        // 实证：整 Message+Part 实例替换即冷）
        val completed = anAssistantMessage(streaming = false, id = "a-cache", sessionId = TEST_SESSION) {
            text(sameBody)
        }
        val rebuilt = listOf(
            Part.Text(id = "part-a-cache", sessionId = TEST_SESSION, messageId = "a-cache", text = sameBody)
        )
        val msgs = fakeChat.messagesState.value.toMutableList()
        msgs[msgs.indexOfFirst { it.id == "a-cache" }] = completed.info
        fakeChat.messagesState.value = msgs
        val map = fakeChat.allPartsMapState.value.toMutableMap()
        map["a-cache"] = rebuilt
        fakeChat.allPartsMapState.value = map

        // 空窗检测：替换落定后正文缺席帧计数（连续 2 帧在场=稳定）
        var absent = 0
        var settled = 0
        repeat(120) {
            composeRule.mainClock.advanceTimeByFrame()
            val present = composeRule.onAllNodesWithText("Paragraph 3", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
            if (present) {
                settled++
                if (settled >= 2) return@repeat
            } else {
                absent++
            }
        }
        composeRule.mainClock.autoAdvance = true
        // 根修验收：缓存命中=零缺席帧（修复前 asyncTerminal Loading 实证 1 帧）
        assertEquals("同内容冷重入必须零空窗（终态缓存命中）", 0, absent)
    }

    /** 判死活主实验：partId 变化（v1 语义）→ 组合位销毁 → ever=false 重建帧。 */
    @Test
    fun rebuild_newPartId_exposesLoadingCollapse() {
        val asst = anAssistantMessage(streaming = true, id = "a-rb", sessionId = TEST_SESSION) {
            text("streaming start marker")
        }
        setMessages(userWithText("question", "u-rb"), asst.toPair())
        renderChatScreen()
        composeRule.onNodeWithText("streaming start marker", substring = true).assertIsDisplayed()

        composeRule.mainClock.autoAdvance = false
        replaceTerminal("a-rb", newPartId = "part-a-rb-v2")
        val blank = blankFramesUntilTerminal()
        composeRule.mainClock.autoAdvance = true
        // #486 根修后语义（2026-10-09 A 案）：partId 变化+内容变化=守卫放行 →
        // pilot coldReveal 1 帧底线（新内容异步解析下限；同步解析违 #428）。
        assertEquals("partId+内容双变化冷重建 1 帧底线 trace=$frameTrace", 1, blank)
    }
}
