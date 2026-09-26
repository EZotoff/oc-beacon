package dev.leonardo.ocbeacon.ui.screens.supervisor

import androidx.lifecycle.SavedStateHandle
import dev.leonardo.ocbeacon.data.repository.SupervisorSnapshotCache
import dev.leonardo.ocbeacon.domain.model.SupervisorSnapshot
import dev.leonardo.ocbeacon.domain.model.SupervisorAttentionItem
import dev.leonardo.ocbeacon.domain.model.SupervisorDecision
import dev.leonardo.ocbeacon.domain.repository.SupervisorRepository
import dev.leonardo.ocbeacon.ui.navigation.routes.ServerRouteParams
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SupervisorViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val repository: SupervisorRepository = mockk()
    private val cache = SupervisorSnapshotCache()
    private val snapshot = SupervisorSnapshot(2, 1, 4, emptyList(), emptyList())

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial load exposes supervisor snapshot`() = runTest {
        coEvery { repository.load("server-1") } returns Result.success(snapshot)

        val viewModel = SupervisorViewModel(savedStateHandle(), repository, cache)

        assertEquals(snapshot, viewModel.uiState.value.snapshot)
        assertFalse(viewModel.uiState.value.isLoading)
        assertFalse(viewModel.uiState.value.loadFailed)
    }

    @Test
    fun `pull refresh reloads current server`() = runTest {
        coEvery { repository.load("server-1") } returns Result.success(snapshot)
        val viewModel = SupervisorViewModel(savedStateHandle(), repository, cache)

        viewModel.refresh()

        coVerify(exactly = 2) { repository.load("server-1") }
        assertTrue(viewModel.uiState.value.snapshot === snapshot)
    }

    @Test
    fun `ui state exposes destination-specific collections`() {
        val item = SupervisorAttentionItem("att-1", "Choose", "app", "2026-09-22T09:00:00Z", 1)
        val decision = SupervisorDecision("CONTINUE", "app", "Safe", "2026-09-22T09:05:00Z")
        val state = SupervisorUiState(
            snapshot = SupervisorSnapshot(1, 0, 0, listOf(item), listOf(decision)),
            isLoading = false,
        )

        assertEquals(listOf(item), state.openItems)
        assertEquals(listOf(decision), state.decisions)
    }

    @Test
    fun `sendReply success clears inflight flag`() = runTest {
        val item = SupervisorAttentionItem("att-1", "Choose", "app", "2026-09-22T09:00:00Z", 1, root = "/work/app")
        val snapshotWithItem = SupervisorSnapshot(1, 0, 0, listOf(item), emptyList())
        coEvery { repository.load("server-1") } returns Result.success(snapshotWithItem)
        coEvery { repository.sendReply("server-1", "/work/app", any()) } returns Result.success(Unit)
        val viewModel = SupervisorViewModel(savedStateHandle(), repository, cache)

        viewModel.sendReply(item, "use Qdrant")

        coVerify {
            repository.sendReply("server-1", "/work/app", match { it.explicitItemID == "att-1" && it.text == "use Qdrant" })
        }
        assertTrue(viewModel.uiState.value.replyInFlight.isEmpty())
        assertTrue(viewModel.uiState.value.replyFailed.isEmpty())
    }

    @Test
    fun `sendReply failure marks item failed`() = runTest {
        val item = SupervisorAttentionItem("att-1", "Choose", "app", "2026-09-22T09:00:00Z", 1, root = "/work/app")
        val snapshotWithItem = SupervisorSnapshot(1, 0, 0, listOf(item), emptyList())
        coEvery { repository.load("server-1") } returns Result.success(snapshotWithItem)
        coEvery { repository.sendReply("server-1", "/work/app", any()) } returns Result.failure(java.io.IOException("offline"))
        val viewModel = SupervisorViewModel(savedStateHandle(), repository, cache)

        viewModel.sendReply(item, "use Qdrant")

        assertTrue(viewModel.uiState.value.replyInFlight.isEmpty())
        assertEquals(setOf("att-1"), viewModel.uiState.value.replyFailed)
    }

    @Test
    fun `sendReply ignores blank text or missing root`() = runTest {
        val item = SupervisorAttentionItem("att-1", "Choose", "app", "2026-09-22T09:00:00Z", 1)
        coEvery { repository.load("server-1") } returns Result.success(snapshot)
        val viewModel = SupervisorViewModel(savedStateHandle(), repository, cache)

        viewModel.sendReply(item, "   ")

        coVerify(exactly = 0) { repository.sendReply(any(), any(), any()) }
    }

    private fun savedStateHandle() = SavedStateHandle(
        mapOf(ServerRouteParams.PARAM_SERVER_ID to "server-1"),
    )
}
