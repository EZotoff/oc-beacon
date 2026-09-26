package dev.leonardo.ocbeacon.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UuidV7Test {

    @Test
    fun `shape is canonical 8-4-4-4-12 with version 7 and variant 10`() {
        val uuid = UuidV7.generateAt(1_790_000_000_000L)
        assertTrue(uuid.matches(Regex("^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")))
    }

    @Test
    fun `time prefix encodes unix ms big-endian`() {
        val ms = 1_790_000_000_000L
        val uuid = UuidV7.generateAt(ms)
        val prefix = uuid.replace("-", "").substring(0, 12)
        assertEquals("%012x".format(ms), prefix)
    }

    @Test
    fun `generated ids are unique`() {
        val ids = (1..100).map { UuidV7.generate() }
        assertEquals(100, ids.toSet().size)
        assertNotEquals(ids[0], ids[1])
    }
}
