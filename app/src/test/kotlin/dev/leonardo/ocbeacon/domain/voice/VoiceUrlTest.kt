package dev.leonardo.ocbeacon.domain.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceUrlTest {

    @Test fun `default survives normalization unchanged`() {
        assertEquals(
            VoiceUrl.DEFAULT_OMO_PULSE_URL,
            VoiceUrl.normalize(VoiceUrl.DEFAULT_OMO_PULSE_URL),
        )
    }

    @Test fun `adds https scheme when missing`() {
        assertEquals("https://pulse.example:4300", VoiceUrl.normalize("pulse.example:4300"))
    }

    @Test fun `trims whitespace and strips trailing slash`() {
        assertEquals("https://pulse.example", VoiceUrl.normalize("  https://pulse.example/  "))
    }

    @Test fun `keeps explicit http and port`() {
        assertEquals("http://10.0.0.2:4300", VoiceUrl.normalize("http://10.0.0.2:4300"))
    }

    @Test fun `keeps path and query`() {
        assertEquals("https://h.example/pulse?x=1", VoiceUrl.normalize("https://h.example/pulse?x=1"))
    }

    @Test fun `rejects blank garbage and non-http schemes`() {
        assertNull(VoiceUrl.normalize(null))
        assertNull(VoiceUrl.normalize(""))
        assertNull(VoiceUrl.normalize("   "))
        assertNull(VoiceUrl.normalize("ftp://pulse.example"))
        assertNull(VoiceUrl.normalize("https://"))
    }
}
