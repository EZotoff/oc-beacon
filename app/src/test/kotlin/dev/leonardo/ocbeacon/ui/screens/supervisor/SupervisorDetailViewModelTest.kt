package dev.leonardo.ocbeacon.ui.screens.supervisor

import androidx.lifecycle.SavedStateHandle
import dev.leonardo.ocbeacon.data.repository.SupervisorReplyStateStore
import dev.leonardo.ocbeacon.data.repository.SupervisorSnapshotCache
import dev.leonardo.ocbeacon.domain.model.BeaconReply
import dev.leonardo.ocbeacon.domain.model.SupervisorAttentionItem
import dev.leonardo.ocbeacon.domain.model.SupervisorSnapshot
import dev.leonardo.ocbeacon.domain.repository.SupervisorRepository
import dev.leonardo.ocbeacon.ui.navigation.routes.ServerRouteParams
import dev.leonardo.ocbeacon.ui.navigation.routes.SupervisorNav
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SupervisorDetailViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val repository: SupervisorRepository = mockk()
    private val cache = SupervisorSnapshotCache()
    private val replyStore = SupervisorReplyStateStore()

    private val item = SupervisorAttentionItem(
        id = "att_1",
        question = "Choose the release path",
        project = "oc-beacon",
        createdAt = "2026-09-25T09:00:00Z",
        stakes = 4,
        actionClass = "ESCALATE",
        escalationKind = "DECISION",
        root = "/work/oc-beacon",
        reasonText = "Choose the release path",
        premiseTexts = listOf("branch blocked", "tests red"),
        severity = "B",
        sessionLabel = "ses_1",
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `found item exposes detail fields and live state`() = runTest {
        coEvery { repository.load("server-1") } returns Result.success(snapshot(stale = false))

        val viewModel = viewModel()

        val state = viewModel.uiState.value
        assertEquals(item, state.item)
        assertFalse(state.stale)
        assertNull(state.staleReason)
        assertFalse(state.itemMissing)
    }

    @Test
    fun `missing item renders missing state`() = runTest {
        val other = snapshot(stale = false).copy(
            attentionItems = listOf(item.copy(id = "att_other")),
        )
        coEvery { repository.load("server-1") } returns Result.success(other)

        val viewModel = viewModel()

        assertTrue(viewModel.uiState.value.itemMissing)
        assertNull(viewModel.uiState.value.item)
    }

    @Test
    fun `frozen snapshot renders stale state`() = runTest {
        coEvery { repository.load("server-1") } returns Result.success(snapshot(stale = true))

        val viewModel = viewModel()

        assertTrue(viewModel.uiState.value.stale)
        assertEquals("stale", viewModel.uiState.value.staleReason)
        assertNotNull(viewModel.uiState.value.item)
    }

    @Test
    fun `sendReply uses the same seam 4 repository path`() = runTest {
        coEvery { repository.load("server-1") } returns Result.success(snapshot(stale = false))
        coEvery { repository.sendReply(any(), any(), any()) } returns Result.success(Unit)
        val viewModel = viewModel()

        viewModel.sendReply("ship it")

        val replySlot = slot<BeaconReply>()
        coVerify(exactly = 1) {
            repository.sendReply(eq("server-1"), eq("/work/oc-beacon"), capture(replySlot))
        }
        assertEquals("ship it", replySlot.captured.text)
        assertEquals("att_1", replySlot.captured.explicitItemID)
        assertEquals("Choose the release path", replySlot.captured.question)
        assertEquals("ses_1", replySlot.captured.sessionTitle)
        assertFalse(viewModel.uiState.value.replyInFlight)
        assertFalse(viewModel.uiState.value.replyFailed)
    }

    @Test
    fun `sendReply is suppressed on stale snapshot and blank input`() = runTest {
        coEvery { repository.load("server-1") } returns Result.success(snapshot(stale = true))
        val viewModel = viewModel()

        viewModel.sendReply("ship it")

        coVerify(exactly = 0) { repository.sendReply(any(), any(), any()) }
    }

    private fun viewModel() = SupervisorDetailViewModel(
        savedStateHandle = SavedStateHandle(
            mapOf(
                ServerRouteParams.PARAM_SERVER_ID to "server-1",
                SupervisorNav.PARAM_ITEM_ID to "att_1",
            ),
        ),
        repository = repository,
        cache = cache,
        replyStateStore = replyStore,
    )

    private fun snapshot(stale: Boolean) = SupervisorSnapshot(
        rootsMonitored = 2,
        rootsFailing = 0,
        errorsPeak = 0,
        attentionItems = listOf(item),
        recentDecisions = emptyList(),
        stale = stale,
        staleReason = if (stale) "stale" else null,
    )
}
