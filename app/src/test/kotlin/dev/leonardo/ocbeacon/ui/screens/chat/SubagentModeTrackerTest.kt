package dev.leonardo.ocbeacon.ui.screens.chat

import dev.leonardo.ocbeacon.domain.model.Session
import dev.leonardo.ocbeacon.domain.model.SubagentCatalog
import dev.leonardo.ocbeacon.domain.model.SubagentCatalogEntry
import dev.leonardo.ocbeacon.domain.repository.ChatRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #310① 子会话 mode 追踪器（纯流逻辑）：
 * - 主会话/非 DSH → 恒 null 且零 RPC；
 * - DSH 子会话 → 先 null（加载中保守隐藏 composer——防 one-shot 误发）再目录 mode；
 * - 失败/目录无本行 → [SubagentComposerGate.MODE_UNRESOLVED] 终态（#497 D4：
 *   与加载中区分——0.2.x subagents/list 404 防骨架占位永久化）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SubagentModeTrackerTest {

    private fun session(id: String, parentId: String?) = Session(
        id = id,
        parentId = parentId,
        time = Session.Time(created = 0, updated = 0),
    )

    private fun entry(id: String, mode: String?, kind: String = "child") =
        SubagentCatalogEntry(kind = kind, id = id, mode = mode)

    @Test
    fun `dsh continuable child emits null then continuable mode`() = runTest {
        val repo = mockk<ChatRepository>()
        coEvery { repo.subagentCatalog("srv-1", "parent-1") } returns Result.success(
            SubagentCatalog(
                entries = listOf(entry("child-1", "continuable"), entry("other", "one-shot")),
                parentAvailable = true,
            ),
        )
        val emissions = mutableListOf<String?>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            SubagentModeTracker(repo, "srv-1").modeFlow(
                flowOf("child-1"),
                flowOf(listOf(session("child-1", "parent-1"))),
                flowOf(true),
            ).collect { emissions.add(it) }
        }
        advanceUntilIdle()
        job.cancel()
        assertEquals(listOf(null, "continuable"), emissions)
        coVerify(exactly = 1) { repo.subagentCatalog("srv-1", "parent-1") }
    }

    @Test
    fun `main session emits null only without rpc`() = runTest {
        val repo = mockk<ChatRepository>()
        val emissions = mutableListOf<String?>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            SubagentModeTracker(repo, "srv-1").modeFlow(
                flowOf("s-main"),
                flowOf(listOf(session("s-main", null))),
                flowOf(true),
            ).collect { emissions.add(it) }
        }
        advanceUntilIdle()
        job.cancel()
        assertEquals(listOf<String?>(null), emissions)
        coVerify(exactly = 0) { repo.subagentCatalog(any(), any()) }
    }

    @Test
    fun `non dsh server emits null only without rpc`() = runTest {
        val repo = mockk<ChatRepository>()
        val emissions = mutableListOf<String?>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            SubagentModeTracker(repo, "srv-1").modeFlow(
                flowOf("child-1"),
                flowOf(listOf(session("child-1", "parent-1"))),
                flowOf(false),
            ).collect { emissions.add(it) }
        }
        advanceUntilIdle()
        job.cancel()
        assertEquals(listOf<String?>(null), emissions)
        coVerify(exactly = 0) { repo.subagentCatalog(any(), any()) }
    }

    @Test
    fun `catalog failure degrades to unresolved sentinel without throwing`() = runTest {
        val repo = mockk<ChatRepository>()
        coEvery { repo.subagentCatalog("srv-1", "parent-1") } returns
            Result.failure(IllegalStateException("parent offline"))
        val emissions = mutableListOf<String?>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            SubagentModeTracker(repo, "srv-1").modeFlow(
                flowOf("child-1"),
                flowOf(listOf(session("child-1", "parent-1"))),
                flowOf(true),
            ).collect { emissions.add(it) }
        }
        advanceUntilIdle()
        job.cancel()
        // #497 D4：失败=终态哨兵（非 null）——门控据此落只读提示行而非骨架
        assertEquals(listOf(null, SubagentComposerGate.MODE_UNRESOLVED), emissions)
    }

    @Test
    fun `child missing from catalog or non null result emits unresolved`() = runTest {
        val repo = mockk<ChatRepository>()
        // 非 DSH 后端经仓储层返回 null（端点缺席降级）——目录无本行同语义
        coEvery { repo.subagentCatalog("srv-1", "parent-1") } returns Result.success(null)
        val emissions = mutableListOf<String?>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            SubagentModeTracker(repo, "srv-1").modeFlow(
                flowOf("child-1"),
                flowOf(listOf(session("child-1", "parent-1"))),
                flowOf(true),
            ).collect { emissions.add(it) }
        }
        advanceUntilIdle()
        job.cancel()
        // #497 D4：目录无本行=续聊无法建立 → 终态哨兵
        assertEquals(listOf(null, SubagentComposerGate.MODE_UNRESOLVED), emissions)
    }
}
