package dev.leonardo.ocbeacon.data.repository

import dev.leonardo.ocbeacon.domain.model.*
import dev.leonardo.ocbeacon.domain.repository.SessionRepository
import dev.leonardo.ocbeacon.domain.usecase.PaginationCursorPolicyFactory
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import javax.inject.Provider

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SessionStateServiceStaleGiveUpTest {
    private val scope = TestScope(UnconfinedTestDispatcher())
    private val repo = mockk<SessionRepository>(relaxed = true)
    private val collaborator = StubCollaborator()
    private val service = SessionStateService(scope, Provider { repo }, collaborator,
        PaginationCursorPolicyFactory(Provider { repo }))

    @After fun close() = scope.cancel()

    private fun seed(lastEventAt: Long = System.currentTimeMillis() - 30_000): Long {
        service.setServerId("server")
        service.onSseEvent(SseEvent.SessionStatus("s", SessionStatus.Busy), "s", "server")
        val field = SessionStateService::class.java.getDeclaredField("_fsmStates").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val states = field.get(service) as MutableStateFlow<Map<String, SessionFSMState>>
        states.value = states.value + ("s" to states.value.getValue("s").copy(lastEventAt = lastEventAt))
        coEvery { repo.fetchSessionStatuses(any(), any()) } returns Result.failure(IllegalStateException("offline"))
        return lastEventAt + 30_000
    }

    @Test fun `eleventh stale round forces Idle and twelfth does not validate`() {
        val now = seed()
        repeat(10) { service.checkStaleness(now) }
        assertEquals(SessionStatus.Busy, service.statusFlow.value["s"])
        coVerify(exactly = 10) { repo.fetchSessionStatuses(any(), any()) }
        service.checkStaleness(now)
        assertEquals(SessionStatus.Idle, service.statusFlow.value["s"])
        service.checkStaleness(now)
        service.requestValidation("s")
        coVerify(exactly = 10) { repo.fetchSessionStatuses(any(), any()) }
        assertEquals(11, service.l2ValidationRounds["s"])
    }

    @Test fun `incomplete assistant stays Busy and continues validation`() {
        val now = seed()
        collaborator.hasIncompleteAssistantImpl = { true }
        repeat(12) { service.checkStaleness(now) }
        assertEquals(SessionStatus.Busy, service.statusFlow.value["s"])
        coVerify(exactly = 12) { repo.fetchSessionStatuses(any(), any()) }
    }

    @Test fun `pending input and active children preserve zombie waiting protection`() {
        val now = seed()
        collaborator.hasPendingUserInputImpl = { true }
        repeat(11) { service.checkStaleness(now) }
        assertEquals(SessionStatus.Busy, service.statusFlow.value["s"])
        collaborator.hasPendingUserInputImpl = { false }
        collaborator.hasActiveChildrenImpl = { _, _ -> true }
        service.checkStaleness(now)
        assertEquals(SessionStatus.Busy, service.statusFlow.value["s"])
    }

    @Test fun `real SSE resets breaker and recent Busy is not swept`() {
        val now = seed()
        repeat(11) { service.checkStaleness(now) }
        service.onSseEvent(SseEvent.SessionStatus("s", SessionStatus.Busy), "s", "server")
        assertNull(service.l2ValidationRounds["s"])
        service.checkStaleness(System.currentTimeMillis())
        assertEquals(SessionStatus.Busy, service.statusFlow.value["s"])
        coVerify(exactly = 10) { repo.fetchSessionStatuses(any(), any()) }
    }

    @Test fun `one hour sweep clears states histories and all auxiliary maps`() {
        val now = seed(System.currentTimeMillis() - 3_600_001)
        service.onRestValidation("s", SessionStatus.Idle)
        service.l2ValidationRounds["s"] = 11
        service.waitingConfirmedAt["s"] = 1
        val maps = listOf("activePositiveStreak", "restBackfillRetries")
        maps.forEach { name ->
            val field = SessionStateService::class.java.getDeclaredField(name).apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val map = field.get(service) as MutableMap<String, Int>
            map["s"] = 1
        }
        val job = Job()
        val jobsField = SessionStateService::class.java.getDeclaredField("activeValidations").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val jobs = jobsField.get(service) as MutableMap<String, Job>
        jobs["s"] = job
        service.checkStaleness(now)
        assertNull(service.statusFlow.value["s"])
        assertNull(service.historyFlow.value["s"])
        (maps + listOf("waitingConfirmedAt", "sessionServerOwnership", "activeValidations", "l2ValidationRounds"))
            .forEach { name ->
                val field = SessionStateService::class.java.getDeclaredField(name).apply { isAccessible = true }
                assertTrue(name, (field.get(service) as Map<*, *>).isEmpty())
            }
        assertTrue(job.isCancelled)
    }
}
