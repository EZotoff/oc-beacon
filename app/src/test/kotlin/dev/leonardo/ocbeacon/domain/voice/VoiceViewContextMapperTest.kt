package dev.leonardo.ocbeacon.domain.voice

import dev.leonardo.ocbeacon.data.api.voice.ViewContextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceViewContextMapperTest {

    @Test fun `maps top level route to home`() {
        assertEquals(ViewContextView.HOME, VoiceViewContextMapper.viewForRoute("home"))
    }

    @Test fun `maps parameterized route prefixes`() {
        assertEquals(ViewContextView.SESSIONS, VoiceViewContextMapper.viewForRoute("sessions/server-1"))
        assertEquals(ViewContextView.WORKSPACE, VoiceViewContextMapper.viewForRoute("workspace/s1/s2?directory=x"))
        assertEquals(ViewContextView.CHAT, VoiceViewContextMapper.viewForRoute("chat/s1/ses_abc"))
        assertEquals(ViewContextView.SUPERVISOR, VoiceViewContextMapper.viewForRoute("supervisor/open-items?s=1&healthContext=false"))
        assertEquals(ViewContextView.SUPERVISOR, VoiceViewContextMapper.viewForRoute("supervisor/item/42?s=1"))
    }

    @Test fun `unmapped screens return null`() {
        assertNull(VoiceViewContextMapper.viewForRoute("settings"))
        assertNull(VoiceViewContextMapper.viewForRoute("about"))
        assertNull(VoiceViewContextMapper.viewForRoute("server_settings/s1"))
        assertNull(VoiceViewContextMapper.viewForRoute("webview/s1/path"))
        assertNull(VoiceViewContextMapper.viewForRoute("file_viewer/s1"))
    }

    @Test fun `null and blank routes return null`() {
        assertNull(VoiceViewContextMapper.viewForRoute(null))
        assertNull(VoiceViewContextMapper.viewForRoute(""))
    }
}

