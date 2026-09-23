package dev.leonardo.ocbeacon.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ChannelVersioningTest {
    @Test fun `both channel IDs evolve independently`() {
        for (base in listOf(NotificationChannels.TASKS, NotificationChannels.SUPERVISOR)) {
            assertEquals(base, ChannelVersioning.channelId(base, 1))
            assertEquals("${base}_v2", ChannelVersioning.channelId(base, 2))
            assertEquals("${base}_v3", ChannelVersioning.channelId(base, ChannelVersioning.nextVersion(2)))
            assertEquals(base, ChannelVersioning.obsoleteChannelId(base, 1, 2))
            assertEquals("${base}_v2", ChannelVersioning.obsoleteChannelId(base, 2, 3))
            assertNull(ChannelVersioning.obsoleteChannelId(base, 0, 1))
        }
    }

    @Test fun `each channel encodes default silent and custom`() {
        for (base in listOf(NotificationChannels.TASKS, NotificationChannels.SUPERVISOR)) {
            for ((encoded, sound) in listOf(
                null to ChannelSound.Default,
                "" to ChannelSound.Silent,
                "content://ringtone/42" to ChannelSound.Custom("content://ringtone/42"),
            )) {
                assertEquals(sound, ChannelVersioning.decodeSound(encoded))
                assertEquals(encoded, ChannelVersioning.encodeSound(sound))
                assertEquals(sound, ChannelVersioning.specFor(base, 2, encoded).sound)
                assertEquals("${base}_v2", ChannelVersioning.specFor(base, 2, encoded).id)
            }
        }
    }

    @Test fun `recreation carries channel properties rather than fresh defaults`() {
        val defaults = ChannelProperties(4, true, null, 0, "default", true, true, 0)
        val user = ChannelProperties(2, false, longArrayOf(0, 200), -1, "custom", false, false, 0xff0000)
        assertSame(user, preservedChannelProperties(user, defaults))
        assertSame(defaults, preservedChannelProperties(null, defaults))
    }
}
