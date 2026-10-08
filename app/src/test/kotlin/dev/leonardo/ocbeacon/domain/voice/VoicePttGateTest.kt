package dev.leonardo.ocbeacon.domain.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class VoicePttGateTest {

    @Test fun `missing permission always requests first`() {
        for (state in VoiceSessionConnection.entries) {
            assertEquals(VoicePttAction.REQUEST_PERMISSION, VoicePttGate.resolve(false))
        }
    }

    @Test fun `granted permission presses in every connection state`() {
        for (state in VoiceSessionConnection.entries) {
            assertEquals(VoicePttAction.PRESS, VoicePttGate.resolve(true))
        }
    }
}
