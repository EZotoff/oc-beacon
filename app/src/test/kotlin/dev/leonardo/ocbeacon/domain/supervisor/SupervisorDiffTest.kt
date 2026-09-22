package dev.leonardo.ocbeacon.domain.supervisor

import dev.leonardo.ocbeacon.domain.model.SupervisorAttentionItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SupervisorDiffTest {

    private fun item(
        id: String,
        actionClass: String = "",
        escalationKind: String = "",
        stakes: Int = 1,
    ) = SupervisorAttentionItem(
        id = id,
        question = "q-$id",
        project = "proj",
        createdAt = "2026-09-22T09:00:00Z",
        stakes = stakes,
        actionClass = actionClass,
        escalationKind = escalationKind,
    )

    @Test
    fun `newOpenItems excludes seen ids`() {
        val items = listOf(item("a"), item("b"), item("c"))

        val result = SupervisorDiff.newOpenItems(setOf("a", "c"), items)

        assertEquals(listOf("b"), result.map { it.id })
    }

    @Test
    fun `newOpenItems orders escalate approval first then steer continue`() {
        val items = listOf(
            item("continue", actionClass = "CONTINUE", stakes = 9),
            item("escalate", actionClass = "ESCALATE", stakes = 1),
            item("approval", actionClass = "ESCALATE", escalationKind = "APPROVAL", stakes = 1),
            item("steer", actionClass = "STEER", stakes = 5),
        )

        val result = SupervisorDiff.newOpenItems(emptySet(), items)

        assertEquals(listOf("approval", "escalate", "steer", "continue"), result.map { it.id })
    }

    @Test
    fun `newOpenItems breaks ties by stakes descending`() {
        val items = listOf(
            item("low", actionClass = "ESCALATE", stakes = 1),
            item("high", actionClass = "ESCALATE", stakes = 5),
        )

        assertEquals(listOf("high", "low"), SupervisorDiff.newOpenItems(emptySet(), items).map { it.id })
    }

    @Test
    fun `newlyFailingRoots returns only newly failing sorted`() {
        val result = SupervisorDiff.newlyFailingRoots(
            previousFailing = setOf("/work/a"),
            currentFailing = setOf("/work/c", "/work/a", "/work/b"),
        )

        assertEquals(listOf("/work/b", "/work/c"), result)
    }

    @Test
    fun `newlyFailingRoots empty when no change`() {
        assertTrue(SupervisorDiff.newlyFailingRoots(setOf("/a"), setOf("/a")).isEmpty())
    }

    @Test
    fun `errorsPeakIncreased only on rise`() {
        assertTrue(SupervisorDiff.errorsPeakIncreased(3, 4))
        assertFalse(SupervisorDiff.errorsPeakIncreased(4, 4))
        assertFalse(SupervisorDiff.errorsPeakIncreased(5, 4))
    }
}
