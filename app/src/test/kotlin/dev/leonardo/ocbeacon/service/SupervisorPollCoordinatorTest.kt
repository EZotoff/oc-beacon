package dev.leonardo.ocbeacon.service

import dev.leonardo.ocbeacon.data.repository.SupervisorHealthSnapshot
import dev.leonardo.ocbeacon.data.repository.SupervisorSeenStore
import dev.leonardo.ocbeacon.data.repository.SupervisorSnapshotCache
import dev.leonardo.ocbeacon.domain.model.SupervisorAttentionItem
import dev.leonardo.ocbeacon.domain.model.SupervisorSnapshot
import dev.leonardo.ocbeacon.domain.repository.SupervisorRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test

class SupervisorPollCoordinatorTest {

    private val repository: SupervisorRepository = mockk()
    private val seenStore: SupervisorSeenStore = mockk(relaxed = true)
    private val cache: SupervisorSnapshotCache = mockk(relaxed = true)
    private val notifications: SupervisorNotificationManager = mockk(relaxed = true)

    private fun coordinator() = SupervisorPollCoordinator(repository, seenStore, cache, notifications)

    private fun item(id: String, actionClass: String = "ESCALATE", escalationKind: String = "") =
        SupervisorAttentionItem(id, "q-$id", "proj", "2026-09-22T09:00:00Z", 1, actionClass, escalationKind)

    @Test
    fun `notifies only new open items and persists seen ids`() = runTest {
        val snapshot = SupervisorSnapshot(
            rootsMonitored = 1,
            rootsFailing = 0,
            errorsPeak = 0,
            attentionItems = listOf(item("a"), item("b")),
            recentDecisions = emptyList(),
        )
        coEvery { repository.load("s1") } returns Result.success(snapshot)
        every { seenStore.seenItemIds("s1") } returns flowOf(setOf("a"))
        every { seenStore.healthSnapshot("s1") } returns flowOf(SupervisorHealthSnapshot())

        coordinator().poll("s1")

        coVerify(exactly = 1) { notifications.notifyAttentionItem("s1", match { it.id == "b" }) }
        coVerify(exactly = 0) { notifications.notifyAttentionItem("s1", match { it.id == "a" }) }
        coVerify { seenStore.saveSeenItemIds("s1", setOf("a", "b")) }
        verify { cache.put("s1", snapshot) }
    }

    @Test
    fun `notifies root health on newly failing root`() = runTest {
        val snapshot = SupervisorSnapshot(
            rootsMonitored = 2,
            rootsFailing = 1,
            errorsPeak = 0,
            attentionItems = emptyList(),
            recentDecisions = emptyList(),
            failingRoots = listOf("/work/b"),
        )
        coEvery { repository.load("s1") } returns Result.success(snapshot)
        every { seenStore.seenItemIds("s1") } returns flowOf(emptySet())
        every { seenStore.healthSnapshot("s1") } returns flowOf(SupervisorHealthSnapshot(setOf("/work/a"), 0))

        coordinator().poll("s1")

        coVerify { notifications.notifyRootHealth("s1", listOf("/work/b"), 0, false) }
    }

    @Test
    fun `notifies root health on error peak rise`() = runTest {
        val snapshot = SupervisorSnapshot(
            rootsMonitored = 1,
            rootsFailing = 0,
            errorsPeak = 9,
            attentionItems = emptyList(),
            recentDecisions = emptyList(),
        )
        coEvery { repository.load("s1") } returns Result.success(snapshot)
        every { seenStore.seenItemIds("s1") } returns flowOf(emptySet())
        every { seenStore.healthSnapshot("s1") } returns flowOf(SupervisorHealthSnapshot(emptySet(), 4))

        coordinator().poll("s1")

        coVerify { notifications.notifyRootHealth("s1", emptyList(), 9, true) }
    }

    @Test
    fun `does not notify when nothing changed`() = runTest {
        val snapshot = SupervisorSnapshot(
            rootsMonitored = 1,
            rootsFailing = 1,
            errorsPeak = 4,
            attentionItems = listOf(item("a")),
            recentDecisions = emptyList(),
            failingRoots = listOf("/work/a"),
        )
        coEvery { repository.load("s1") } returns Result.success(snapshot)
        every { seenStore.seenItemIds("s1") } returns flowOf(setOf("a"))
        every { seenStore.healthSnapshot("s1") } returns flowOf(SupervisorHealthSnapshot(setOf("/work/a"), 4))

        coordinator().poll("s1")

        coVerify(exactly = 0) { notifications.notifyAttentionItem(any(), any()) }
        coVerify(exactly = 0) { notifications.notifyRootHealth(any(), any(), any(), any()) }
    }

    @Test
    fun `stale snapshot caches frozen cards but never notifies or updates seen state`() = runTest {
        val snapshot = SupervisorSnapshot(
            rootsMonitored = 1,
            rootsFailing = 0,
            errorsPeak = 0,
            attentionItems = listOf(item("a")),
            recentDecisions = emptyList(),
            stale = true,
            staleReason = "stale",
        )
        coEvery { repository.load("s1") } returns Result.success(snapshot)
        every { seenStore.seenItemIds("s1") } returns flowOf(emptySet())
        every { seenStore.healthSnapshot("s1") } returns flowOf(SupervisorHealthSnapshot())

        coordinator().poll("s1")

        verify { cache.put("s1", snapshot) }
        coVerify(exactly = 0) { notifications.notifyAttentionItem(any(), any()) }
        coVerify(exactly = 0) { notifications.notifyRootHealth(any(), any(), any(), any()) }
        coVerify(exactly = 0) { seenStore.saveSeenItemIds(any(), any()) }
        coVerify(exactly = 0) { seenStore.saveHealthSnapshot(any(), any()) }
    }

    fun `load failure is fail-safe and does not touch seen state`() = runTest {
        coEvery { repository.load("s1") } returns Result.failure(RuntimeException("server down"))

        coordinator().poll("s1")

        coVerify(exactly = 0) { notifications.notifyAttentionItem(any(), any()) }
        coVerify(exactly = 0) { notifications.notifyRootHealth(any(), any(), any(), any()) }
        coVerify(exactly = 0) { seenStore.saveSeenItemIds(any(), any()) }
        coVerify(exactly = 0) { seenStore.saveHealthSnapshot(any(), any()) }
        verify(exactly = 0) { cache.put(any(), any()) }
    }
}
