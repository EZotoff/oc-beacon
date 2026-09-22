package dev.leonardo.ocbeacon.ui.screens.supervisor

import androidx.lifecycle.SavedStateHandle
import dev.leonardo.ocbeacon.domain.model.SupervisorSnapshot
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

        val viewModel = SupervisorViewModel(savedStateHandle(), repository)

        assertEquals(snapshot, viewModel.uiState.value.snapshot)
        assertFalse(viewModel.uiState.value.isLoading)
        assertFalse(viewModel.uiState.value.loadFailed)
    }

    @Test
    fun `pull refresh reloads current server`() = runTest {
        coEvery { repository.load("server-1") } returns Result.success(snapshot)
        val viewModel = SupervisorViewModel(savedStateHandle(), repository)

        viewModel.refresh()

        coVerify(exactly = 2) { repository.load("server-1") }
        assertTrue(viewModel.uiState.value.snapshot === snapshot)
    }

    private fun savedStateHandle() = SavedStateHandle(
        mapOf(ServerRouteParams.PARAM_SERVER_ID to "server-1"),
    )
}
