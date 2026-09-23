package dev.leonardo.ocbeacon.service

import dev.leonardo.ocbeacon.data.repository.EventDispatcher
import dev.leonardo.ocbeacon.domain.model.Message
import dev.leonardo.ocbeacon.domain.model.Part
import dev.leonardo.ocbeacon.domain.model.Session
import dev.leonardo.ocbeacon.domain.model.TimeInfo
import dev.leonardo.ocbeacon.domain.repository.SettingsRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * 2026-09-16 回归（用户实报）：服务器把 "Background task completed/failed: …"
 * synthetic 消息写进父会话 transcript，旧 computeNewAssistantMessageId 视为
 * 「最新 assistant 新输出」→ 每个后台子任务完成都推送一次（用户不需要）。
 * 修复：synthetic 任务通知不计为有新输出；真实回复仍照常通知。
 */
class AppNotificationSyntheticTaskTest {

    private lateinit var eventDispatcher: EventDispatcher
    private lateinit var manager: AppNotificationManager

    private val messagesFlow = MutableStateFlow<Map<String, List<Message>>>(emptyMap())
    private val partsFlow = MutableStateFlow<Map<String, List<Part>>>(emptyMap())

    @Before
    fun setup() {
        eventDispatcher = mockk()
        every { eventDispatcher.messages } returns messagesFlow
        every { eventDispatcher.parts } returns partsFlow
        every { eventDispatcher.sessions } returns MutableStateFlow<List<Session>>(emptyList())
        manager = AppNotificationManager(
            eventDispatcher,
            mockk<SettingsRepository>(relaxed = true),
            SessionFocusHolder(),
            mockk(relaxed = true),
            CoroutineScope(SupervisorJob() + Dispatchers.Default),
            mockk<android.content.Context>(relaxed = true),
            mockk(relaxed = true),
            mockk<TurnChannelManager>(relaxed = true),
        )
    }

    private fun assistant(id: String, sessionId: String = "sess1") = Message.Assistant(
        id = id, sessionId = sessionId, time = TimeInfo(created = 0), parentId = "p1",
    )

    private fun user(id: String, sessionId: String = "sess1") = Message.User(
        id = id, sessionId = sessionId, time = TimeInfo(created = 0),
    )

    private fun textPart(id: String, text: String, messageId: String = "msg1") = Part.Text(
        id = id, sessionId = "sess1", messageId = messageId, text = text,
    )

    @Test
    fun `synthetic background task completed is not new output`() {
        messagesFlow.value = mapOf("sess1" to listOf(assistant("msg1")))
        partsFlow.value = mapOf(
            "msg1" to listOf(
                textPart("p1", "Background task completed: run-benchmarks\n<task_result>all green</task_result>"),
            )
        )
        assertNull(manager.computeNewAssistantMessageId("sess1"))
    }

    @Test
    fun `synthetic background task failed is not new output`() {
        messagesFlow.value = mapOf("sess1" to listOf(assistant("msg1")))
        partsFlow.value = mapOf(
            "msg1" to listOf(
                textPart("p1", "Background task failed: run-benchmarks\n<task_error>boom</task_error>"),
            )
        )
        assertNull(manager.computeNewAssistantMessageId("sess1"))
    }

    @Test
    fun `real assistant reply still notifies`() {
        messagesFlow.value = mapOf("sess1" to listOf(assistant("msg1")))
        partsFlow.value = mapOf("msg1" to listOf(textPart("p1", "Done — here is the summary.")))
        assertEquals("msg1", manager.computeNewAssistantMessageId("sess1"))
    }

    @Test
    fun `real reply after synthetic task notification still notifies`() {
        messagesFlow.value = mapOf("sess1" to listOf(assistant("msg0"), assistant("msg1")))
        partsFlow.value = mapOf(
            "msg0" to listOf(textPart("p0", "Background task completed: run-benchmarks", messageId = "msg0")),
            "msg1" to listOf(textPart("p1", "All done.", messageId = "msg1")),
        )
        // 最新 assistant 是 msg1（真实回复）→ 通知
        assertEquals("msg1", manager.computeNewAssistantMessageId("sess1"))
    }

    @Test
    fun `reminder-injected task completed trigger suppresses push`() {
        // 2026-09-18：服务器以用户消息注入 `<system-reminder>\n[BACKGROUND TASK COMPLETED]…`
        // （part 无 synthetic 旗标），注入唤醒父 agent 产生真实回复 → 旧过滤不匹配仍推送。
        messagesFlow.value = mapOf("sess1" to listOf(user("u0"), assistant("msg1")))
        partsFlow.value = mapOf(
            "u0" to listOf(
                textPart("p0", "<system-reminder>\n[BACKGROUND TASK COMPLETED]\n**ID:** `bg_87cad0d1`\n**Description:** explore</system-reminder>", messageId = "u0"),
            ),
            "msg1" to listOf(textPart("p1", "Acknowledged, continuing.", messageId = "msg1")),
        )
        assertNull(manager.computeNewAssistantMessageId("sess1"))
    }

    @Test
    fun `reminder-injected task failed trigger suppresses push`() {
        messagesFlow.value = mapOf("sess1" to listOf(user("u0"), assistant("msg1")))
        partsFlow.value = mapOf(
            "u0" to listOf(
                textPart("p0", "<system-reminder>\n[BACKGROUND TASK FAILED]\n**ID:** `bg_x`</system-reminder>", messageId = "u0"),
            ),
            "msg1" to listOf(textPart("p1", "Task failed, investigating.", messageId = "msg1")),
        )
        assertNull(manager.computeNewAssistantMessageId("sess1"))
    }

    @Test
    fun `synthetic user message trigger suppresses push`() {
        messagesFlow.value = mapOf("sess1" to listOf(user("u0"), assistant("msg1")))
        partsFlow.value = mapOf(
            "u0" to listOf(
                textPart("p0", "Background task completed: run-benchmarks", messageId = "u0")
                    .copy(synthetic = true),
            ),
            "msg1" to listOf(textPart("p1", "Continuing.", messageId = "msg1")),
        )
        assertNull(manager.computeNewAssistantMessageId("sess1"))
    }

    @Test
    fun `real user turn still notifies after reminder history`() {
        // 背景 task 通告在更早轮次；本轮由真实用户消息触发 → 照常通知。
        messagesFlow.value = mapOf(
            "sess1" to listOf(user("u0"), assistant("msg0"), user("u1"), assistant("msg1")),
        )
        partsFlow.value = mapOf(
            "u0" to listOf(
                textPart("p0", "<system-reminder>\n[BACKGROUND TASK COMPLETED]\n**ID:** `bg_old`</system-reminder>", messageId = "u0"),
            ),
            "msg0" to listOf(textPart("p01", "Acknowledged.", messageId = "msg0")),
            "u1" to listOf(textPart("p1", "Please run the full suite now.", messageId = "u1")),
            "msg1" to listOf(textPart("p11", "All green.", messageId = "msg1")),
        )
        assertEquals("msg1", manager.computeNewAssistantMessageId("sess1"))
    }

    @Test
    fun `boulder system directive trigger suppresses push`() {
        // 2026-09-18 用户实报：OMO boulder continuation 以 `[SYSTEM DIRECTIVE: …` 用户消息
        // 注入唤醒 agent —— 真实回复触发推送。机器指令注入的轮次不推送。
        messagesFlow.value = mapOf("sess1" to listOf(user("u0"), assistant("msg1")))
        partsFlow.value = mapOf(
            "u0" to listOf(
                textPart("p0", "[SYSTEM DIRECTIVE: OH-MY-OPENCODE - BOULDER CONTINUATION]\n\nYou have an active work plan with incomplete tasks. Continue working.", messageId = "u0"),
            ),
            "msg1" to listOf(textPart("p1", "Continuing with task 3.", messageId = "msg1")),
        )
        assertNull(manager.computeNewAssistantMessageId("sess1"))
    }

    @Test
    fun `reminder-injected task result ready trigger suppresses push`() {
        // 2026-09-19（Oracle 诊断，用户实报仍推送）：真实注入头部是
        // `[BACKGROUND TASK RESULT READY]`，旧正则只匹配 COMPLETED|FAILED → 绕过。
        messagesFlow.value = mapOf("sess1" to listOf(user("u0"), assistant("msg1")))
        partsFlow.value = mapOf(
            "u0" to listOf(
                textPart("p0", "<system-reminder>\n[BACKGROUND TASK RESULT READY]\n**Description:** dig prior sessions</system-reminder>", messageId = "u0"),
            ),
            "msg1" to listOf(textPart("p1", "Dig complete, reporting.", messageId = "msg1")),
        )
        assertNull(manager.computeNewAssistantMessageId("sess1"))
    }

    @Test
    fun `reminder-injected task retrying trigger suppresses push`() {
        messagesFlow.value = mapOf("sess1" to listOf(user("u0"), assistant("msg1")))
        partsFlow.value = mapOf(
            "u0" to listOf(
                textPart("p0", "<system-reminder>\n[BACKGROUND TASK RETRYING]\n**ID:** `bg_x`</system-reminder>", messageId = "u0"),
            ),
            "msg1" to listOf(textPart("p1", "Noted, waiting for retry.", messageId = "msg1")),
        )
        assertNull(manager.computeNewAssistantMessageId("sess1"))
    }

    @Test
    fun `synthetic part with arbitrary text suppresses push`() {
        // 任何 synthetic 旗标的触发 part 都是机器注入（重启续跑提示等），与文本无关。
        messagesFlow.value = mapOf("sess1" to listOf(user("u0"), assistant("msg1")))
        partsFlow.value = mapOf(
            "u0" to listOf(
                textPart("p0", "Continuing after server restart — your session was snapshotted.", messageId = "u0")
                    .copy(synthetic = true),
            ),
            "msg1" to listOf(textPart("p1", "Resuming work.", messageId = "msg1")),
        )
        assertNull(manager.computeNewAssistantMessageId("sess1"))
    }

    @Test
    fun `leading whitespace system directive trigger suppresses push`() {
        // 注入文本可能带前置空白/换行 —— 旧正则锚定字节 0 会绕过。
        messagesFlow.value = mapOf("sess1" to listOf(user("u0"), assistant("msg1")))
        partsFlow.value = mapOf(
            "u0" to listOf(
                textPart("p0", "\n\n[SYSTEM DIRECTIVE: OH-MY-OPENCODE - RALPH LOOP]\nContinue the loop.", messageId = "u0"),
            ),
            "msg1" to listOf(textPart("p1", "Looping.", messageId = "msg1")),
        )
        assertNull(manager.computeNewAssistantMessageId("sess1"))
    }

    @Test
    fun `user message mentioning directive mid-text still notifies`() {
        // 正文中间提及（非开头）不构成注入 → 照常通知。
        messagesFlow.value = mapOf("sess1" to listOf(user("u0"), assistant("msg1")))
        partsFlow.value = mapOf(
            "u0" to listOf(
                textPart("p0", "Why do I keep seeing [SYSTEM DIRECTIVE: ...] messages?", messageId = "u0"),
            ),
            "msg1" to listOf(textPart("p1", "That marker comes from OMO continuation.", messageId = "msg1")),
        )
        assertEquals("msg1", manager.computeNewAssistantMessageId("sess1"))
    }
}
