package dev.leonardo.ocbeacon.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** 纯内存 DataStore——避免 Windows 文件系统 rename 限制（与 UnreadStateStoreTest 相同模式）。 */
private class InMemorySupervisorStore : DataStore<Preferences> {
    private val state = MutableStateFlow<Preferences>(emptyPreferences())
    override val data: Flow<Preferences> = state
    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
        val updated = transform(state.value)
        state.value = updated
        return updated
    }
}

class SupervisorSeenStoreTest {

    private fun newStore(): SupervisorSeenStore = SupervisorSeenStore(InMemorySupervisorStore())

    @Test
    fun `seen ids empty by default`() = runTest {
        assertEquals(emptySet<String>(), newStore().seenItemIds("s1").first())
    }

    @Test
    fun `seen ids round-trip and are server scoped`() = runTest {
        val store = newStore()
        store.saveSeenItemIds("s1", setOf("a", "b"))
        store.saveSeenItemIds("s2", setOf("c"))

        assertEquals(setOf("a", "b"), store.seenItemIds("s1").first())
        assertEquals(setOf("c"), store.seenItemIds("s2").first())
    }

    @Test
    fun `health snapshot empty by default`() = runTest {
        assertEquals(SupervisorHealthSnapshot(), newStore().healthSnapshot("s1").first())
    }

    @Test
    fun `health snapshot round-trip`() = runTest {
        val store = newStore()
        store.saveHealthSnapshot("s1", SupervisorHealthSnapshot(setOf("/work/a"), 7))

        assertEquals(SupervisorHealthSnapshot(setOf("/work/a"), 7), store.healthSnapshot("s1").first())
    }
}
