package dev.leonardo.ocbeacon.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Supervisor 渠道版本化契约锁。
 *
 * Android 渠道声音不可变——改声音必须换 ID 重建。本测试钉死：
 * 版本 → ID 派生、版本递增、旧渠道删除目标、声音编解码。
 */
class SupervisorChannelVersioningTest {

    @Test
    fun `version 1 maps to base channel id`() {
        assertEquals("opencode_supervisor", SupervisorChannelVersioning.channelId(1))
        assertEquals(NotificationChannels.SUPERVISOR, SupervisorChannelVersioning.channelId(1))
    }

    @Test
    fun `version above 1 maps to versioned channel id`() {
        assertEquals("opencode_supervisor_v2", SupervisorChannelVersioning.channelId(2))
        assertEquals("opencode_supervisor_v3", SupervisorChannelVersioning.channelId(3))
    }

    @Test
    fun `next version always bumps by one`() {
        assertEquals(2, SupervisorChannelVersioning.nextVersion(1))
        assertEquals(3, SupervisorChannelVersioning.nextVersion(2))
    }

    @Test
    fun `obsolete channel is the previous version id`() {
        assertEquals(
            "opencode_supervisor",
            SupervisorChannelVersioning.obsoleteChannelId(currentVersion = 1, targetVersion = 2),
        )
        assertEquals(
            "opencode_supervisor_v2",
            SupervisorChannelVersioning.obsoleteChannelId(currentVersion = 2, targetVersion = 3),
        )
    }

    @Test
    fun `no obsolete channel when there is no previous version`() {
        assertNull(SupervisorChannelVersioning.obsoleteChannelId(currentVersion = 0, targetVersion = 1))
    }

    @Test
    fun `spec carries versioned id and decoded sound`() {
        val spec = SupervisorChannelVersioning.specFor(2, "content://media/ringtone/42")
        assertEquals("opencode_supervisor_v2", spec.id)
        assertEquals(2, spec.version)
        assertEquals(SupervisorSound.Custom("content://media/ringtone/42"), spec.sound)
    }

    @Test
    fun `sound encoding round trips`() {
        assertEquals(SupervisorSound.Default, SupervisorChannelVersioning.decodeSound(null))
        assertEquals(SupervisorSound.Silent, SupervisorChannelVersioning.decodeSound(""))
        assertEquals(
            SupervisorSound.Custom("content://x"),
            SupervisorChannelVersioning.decodeSound("content://x"),
        )

        assertNull(SupervisorChannelVersioning.encodeSound(SupervisorSound.Default))
        assertEquals("", SupervisorChannelVersioning.encodeSound(SupervisorSound.Silent))
        assertEquals(
            "content://x",
            SupervisorChannelVersioning.encodeSound(SupervisorSound.Custom("content://x")),
        )
    }
}
