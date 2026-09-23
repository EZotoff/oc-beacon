package dev.leonardo.ocbeacon.service

object ChannelVersioning {
    fun channelId(baseId: String, version: Int): String =
        if (version <= 1) baseId else "${baseId}_v$version"

    fun nextVersion(current: Int): Int = current + 1

    fun obsoleteChannelId(baseId: String, currentVersion: Int, targetVersion: Int): String? =
        if (currentVersion <= 0) null else channelId(baseId, currentVersion)
            .takeIf { it != channelId(baseId, targetVersion) }

    fun specFor(baseId: String, version: Int, storedSound: String?): ChannelSpec =
        ChannelSpec(channelId(baseId, version), version, decodeSound(storedSound))

    fun decodeSound(stored: String?): ChannelSound = when {
        stored == null -> ChannelSound.Default
        stored.isEmpty() -> ChannelSound.Silent
        else -> ChannelSound.Custom(stored)
    }

    fun encodeSound(sound: ChannelSound): String? = when (sound) {
        ChannelSound.Default -> null
        ChannelSound.Silent -> ""
        is ChannelSound.Custom -> sound.uri
    }
}

sealed interface ChannelSound {
    data object Default : ChannelSound
    data object Silent : ChannelSound
    data class Custom(val uri: String) : ChannelSound
}

data class ChannelSpec(val id: String, val version: Int, val sound: ChannelSound)

data class ChannelProperties(
    val importance: Int,
    val vibrate: Boolean,
    val vibrationPattern: LongArray?,
    val lockscreenVisibility: Int,
    val description: String?,
    val showBadge: Boolean,
    val lights: Boolean,
    val lightColor: Int,
)

internal fun preservedChannelProperties(old: ChannelProperties?, defaults: ChannelProperties): ChannelProperties =
    old ?: defaults
