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
    fun `errorsPeakIncreased only on significant rise (hysteresis plus 10 or 50 percent)`() {
        // 风暴爬升期的小步递增不再逐档推送
        assertFalse(SupervisorDiff.errorsPeakIncreased(3, 4))
        assertFalse(SupervisorDiff.errorsPeakIncreased(4, 4))
        assertFalse(SupervisorDiff.errorsPeakIncreased(5, 4))
        // +10 绝对阈值：从 0 起 10 即显著（与 supervisor 阈值 10 对齐）
        assertFalse(SupervisorDiff.errorsPeakIncreased(0, 9))
        assertTrue(SupervisorDiff.errorsPeakIncreased(0, 10))
        // 50% 相对阈值：20+10=30 边界；100+50=150
        assertTrue(SupervisorDiff.errorsPeakIncreased(20, 30))
        assertFalse(SupervisorDiff.errorsPeakIncreased(20, 29))
        assertTrue(SupervisorDiff.errorsPeakIncreased(100, 150))
        assertFalse(SupervisorDiff.errorsPeakIncreased(100, 140))
    }
}
