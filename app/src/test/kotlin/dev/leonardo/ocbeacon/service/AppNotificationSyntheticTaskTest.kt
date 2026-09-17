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
        )
    }

    private fun assistant(id: String, sessionId: String = "sess1") = Message.Assistant(
        id = id, sessionId = sessionId, time = TimeInfo(created = 0), parentId = "p1",
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
}
