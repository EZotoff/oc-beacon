package dev.leonardo.ocbeacon.domain.voice

import dev.leonardo.ocbeacon.data.api.voice.ViewContextView

/**
 * Maps the app's navigation routes onto beacon view-context values for the
 * voice bridge. Unmapped screens (settings, about, server admin, viewers)
 * return null — the last mapped context stays in effect until a mapped
 * screen is reached again.
 */
object VoiceViewContextMapper {

    private val PREFIXES = listOf(
        "home" to ViewContextView.HOME,
        "sessions" to ViewContextView.SESSIONS,
        "workspace" to ViewContextView.WORKSPACE,
        "chat" to ViewContextView.CHAT,
        "supervisor" to ViewContextView.SUPERVISOR,
    )

    /** Route prefixes match parameterized patterns ("chat/{serverId}/..."). */
    fun viewForRoute(route: String?): ViewContextView? {
        if (route.isNullOrBlank()) return null
        return PREFIXES.firstOrNull { (prefix, _) -> route == prefix || route.startsWith("$prefix/") || route.startsWith("$prefix?") }
            ?.second
    }
}
