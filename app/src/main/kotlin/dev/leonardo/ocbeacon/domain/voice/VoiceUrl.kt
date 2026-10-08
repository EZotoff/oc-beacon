package dev.leonardo.ocbeacon.domain.voice

import java.net.URI

/**
 * omo-pulse base URL handling for the voice walking-test surface.
 * Stored raw in settings; normalized at connect time.
 */
object VoiceUrl {
    const val DEFAULT_OMO_PULSE_URL = "https://ez-raider.tailb8fd09.ts.net:4300"

    /**
     * Normalize a user-entered omo-pulse base URL:
     * trims whitespace, defaults the scheme to https, strips a trailing
     * slash. Returns null when no usable host remains or the scheme is
     * neither http nor https.
     */
    fun normalize(raw: String?): String? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        val withScheme = if (trimmed.contains("://")) trimmed else "https://$trimmed"
        val uri = runCatching { URI(withScheme) }.getOrNull() ?: return null
        if (uri.scheme?.lowercase() !in setOf("http", "https")) return null
        val host = uri.host ?: return null
        if (host.isBlank()) return null
        val path = uri.rawPath.orEmpty().trimEnd('/')
        val query = uri.rawQuery?.let { "?$it" }.orEmpty()
        val port = uri.rawAuthority?.substringAfterLast(':', "")?.toIntOrNull()
        val authority = buildString {
            append(host)
            if (port != null && port != uri.portDefault()) append(":$port")
        }
        return "${uri.scheme.lowercase()}://$authority$path$query"
    }

    private fun URI.portDefault(): Int = if (scheme.equals("https", true)) 443 else 80
}
