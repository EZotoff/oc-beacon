package dev.leonardo.ocbeacon

import dev.leonardo.ocbeacon.data.repository.SessionStateService
import dev.leonardo.ocbeacon.data.repository.StubCollaborator
import dev.leonardo.ocbeacon.domain.model.SessionStatus
import dev.leonardo.ocbeacon.domain.repository.SessionRepository
import dev.leonardo.ocbeacon.domain.usecase.PaginationCursorPolicyFactory
import io.mockk.mockk
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.*
import org.junit.Test
import javax.inject.Provider

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class OpenCodeAppTrimStepSafeTest {
    @Test fun `throwing trim step does not escape or prevent later cleanup`() {
        val app = OpenCodeApp()
        app.runTrimStepSafely("throwing fixture") { throw IllegalStateException("cleanup failed") }
        var ran = false
        app.runTrimStepSafely("following step") { ran = true }
        assertTrue(ran)
    }

    @Test fun `history trim preserves FSM status and activity`() {
        val scope = TestScope(UnconfinedTestDispatcher())
        try {
            val repo = mockk<SessionRepository>(relaxed = true)
            val service = SessionStateService(scope, Provider { repo }, StubCollaborator(),
                PaginationCursorPolicyFactory(Provider { repo }))
            service.onClientSendParts("s")
            assertFalse(service.historyFlow.value.isEmpty())
            val statuses = service.statusFlow.value
            val activity = service.activityFlow.value
            service.trimHistories()
            assertTrue(service.historyFlow.value.isEmpty())
            assertEquals(statuses, service.statusFlow.value)
            assertEquals(activity, service.activityFlow.value)
            assertEquals(SessionStatus.Busy, service.statusFlow.value["s"])
        } finally { scope.cancel() }
    }
}
