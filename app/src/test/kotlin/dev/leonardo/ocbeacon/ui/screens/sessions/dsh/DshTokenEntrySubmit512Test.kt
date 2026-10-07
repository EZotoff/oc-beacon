package dev.leonardo.ocbeacon.ui.screens.sessions.dsh

import androidx.lifecycle.SavedStateHandle
import dev.leonardo.ocbeacon.data.api.dsh.DshConnectionRegistry
import dev.leonardo.ocbeacon.domain.model.ServerConfig
import dev.leonardo.ocbeacon.domain.model.ServerType
import dev.leonardo.ocbeacon.domain.repository.ServerRepository
import dev.leonardo.ocbeacon.service.SseConnectionManager
import io.mockk.CapturingSlot
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * #512 验收补口：对话框提交链——插件密码铸票**优先**（成功即存
 * ServerConfig.password），失败回落 legacy 令牌交换（无插件服务器），
 * 两路皆败=Rejected。干跑定罪（2026-10-07）此前 submit 只走 legacy。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DshTokenEntrySubmit512Test {

    private val dispatcher = UnconfinedTestDispatcher()

    private lateinit var registry: DshConnectionRegistry
    private lateinit var serverRepo: ServerRepository
    private lateinit var vm: DshTokenEntryViewModel

    private fun server(password: String? = null) = ServerConfig(
        id = "s-1", url = "http://127.0.0.1:8181", username = "demo",
        password = password, name = "DSH", serverType = ServerType.Dsh,
    )

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
        registry = mockk(relaxed = true)
        serverRepo = mockk(relaxed = true)
        val sse = mockk<SseConnectionManager> {
            every { dshTokenNeededServers } returns MutableStateFlow(emptySet())
        }
        coEvery { serverRepo.getServer("s-1") } answers { server() }
        val saved = SavedStateHandle(mapOf("serverId" to "s-1"))
        vm = DshTokenEntryViewModel(saved, sse, registry, serverRepo)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `plugin password mint wins first and password is persisted`() = runTest(dispatcher) {
        coEvery { registry.mintSessionWithPassword(any(), any()) } returns true

        vm.submit("devpass")

        coVerify(exactly = 1) { registry.mintSessionWithPassword(any(), "devpass") }
        coVerify(exactly = 0) { registry.exchangeToken(any(), any()) }
        val slot = CapturingSlot<ServerConfig>()
        coVerify { serverRepo.updateServer(capture(slot)) }
        assertEquals("devpass", slot.captured.password)
        assertEquals(DshTokenEntryViewModel.ExchangeState.Idle, vm.exchange.value)
    }

    @Test
    fun `plugin absent falls back to legacy token exchange`() = runTest(dispatcher) {
        coEvery { registry.mintSessionWithPassword(any(), any()) } returns false
        coEvery { registry.exchangeToken(any(), any()) } returns true

        vm.submit("dsh_abcdef0123456789abcdef0123")

        coVerify(exactly = 1) { registry.exchangeToken(any(), any()) }
        assertEquals(DshTokenEntryViewModel.ExchangeState.Idle, vm.exchange.value)
        coVerify(exactly = 0) { serverRepo.updateServer(any()) }
    }

    @Test
    fun `both paths rejected yields Rejected`() = runTest(dispatcher) {
        coEvery { registry.mintSessionWithPassword(any(), any()) } returns false
        coEvery { registry.exchangeToken(any(), any()) } returns false

        vm.submit("wrongpw")

        assertEquals(DshTokenEntryViewModel.ExchangeState.Rejected, vm.exchange.value)
    }

    @Test
    fun `plugin miss with non token input rejects without legacy call`() = runTest(dispatcher) {
        coEvery { registry.mintSessionWithPassword(any(), any()) } returns false

        vm.submit("not-a-token!!")

        coVerify(exactly = 0) { registry.exchangeToken(any(), any()) }
        assertEquals(DshTokenEntryViewModel.ExchangeState.Rejected, vm.exchange.value)
    }

    @Test
    fun `same stored password skips redundant update`() = runTest(dispatcher) {
        coEvery { serverRepo.getServer("s-1") } answers { server(password = "devpass") }
        coEvery { registry.mintSessionWithPassword(any(), any()) } returns true

        vm.submit("devpass")

        coVerify(exactly = 0) { serverRepo.updateServer(any()) }
        assertTrue(vm.exchange.value == DshTokenEntryViewModel.ExchangeState.Idle)
    }
}
