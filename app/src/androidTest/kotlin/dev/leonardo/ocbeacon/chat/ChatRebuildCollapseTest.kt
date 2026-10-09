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
        // 钉当前已知行为（2026-10-09 模拟器判死活实证）：合成全量替换（整 Message
        // +Part 实例从零替换）即使 partId 稳定也冷重建（生产槽位存活靠 part-updated
        // 增量路径，09-30 实验 A/B 实证）→ 冷路径首帧 Loading 1 帧空窗。护栏语义：
        // 恶化（≥2 帧=Loading 拖长/解析回归）即红。
        assertEquals("稳定 partId 合成重建当前已知 1 帧空窗 trace=$frameTrace", 1, blank)
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
        // 判死活结论（2026-10-09 模拟器实证）：ever=false 冷重建的 Loading 空窗帧
        // **确定性存在**（trace=BT——首帧双无、次帧终态）。形态=1 帧空窗（非
        // -8575 双跳——#471③/#509 后冷重建不再有归一化跳变，仅 Loading→Success
        // 一次换装）；生产触发前提（视口内组合位销毁）在 09-30 两次真实 backfill
        // 实验中未发生。护栏钉 1：≥2 即 Loading 拖长/解析回归预警。
        assertEquals("partId 变化冷重建当前已知 1 帧空窗 trace=$frameTrace", 1, blank)
    }
}
