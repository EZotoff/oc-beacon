package dev.leonardo.ocbeacon.service

/**
 * Supervisor 通知渠道版本化（纯逻辑，JVM 可测）。
 *
 * Android 约束（API 26+）：渠道的声音是创建期属性，创建后不可变——改声音
 * 必须用新 ID 重建渠道并删除旧渠道。版本号持久化在设置中，渠道 ID 由版本派生：
 * v1 = 基础 ID（历史渠道），vN(N>1) = "${base}_v$N"。
 *
 * 本对象只做纯决策（ID 派生 / 版本递增 / 声音编解码），不触碰 Android framework，
 * 便于单测钉死重建语义。
 */
object SupervisorChannelVersioning {

    /** 基础渠道 ID（v1 与历史渠道共用）。 */
    const val BASE_CHANNEL_ID: String = NotificationChannels.SUPERVISOR

    /** 版本 → 渠道 ID。version<=1 用基础 ID（兼容历史渠道）。 */
    fun channelId(version: Int): String =
        if (version <= 1) BASE_CHANNEL_ID else "${BASE_CHANNEL_ID}_v$version"

    /** 下一次重建的版本号（单调递增，保证新 ID 与旧 ID 不同）。 */
    fun nextVersion(current: Int): Int = current + 1

    /**
     * 重建时应删除的旧渠道 ID；无旧渠道（首次创建）返回 null。
     * 旧渠道 = 当前版本对应的 ID（与目标 ID 相同则无需删除）。
     */
    fun obsoleteChannelId(currentVersion: Int, targetVersion: Int): String? {
        if (currentVersion <= 0) return null
        val old = channelId(currentVersion)
        return old.takeIf { it != channelId(targetVersion) }
    }

    /** 由持久化版本 + 声音编码构造渠道规格。 */
    fun specFor(version: Int, storedSound: String?): SupervisorChannelSpec =
        SupervisorChannelSpec(channelId(version), version, decodeSound(storedSound))

    /** 声音编码 → 领域值。null=默认，""=静音，其余=自定义 URI。 */
    fun decodeSound(stored: String?): SupervisorSound = when {
        stored == null -> SupervisorSound.Default
        stored.isEmpty() -> SupervisorSound.Silent
        else -> SupervisorSound.Custom(stored)
    }

    /** 领域值 → 声音编码（与 [decodeSound] 互逆）。 */
    fun encodeSound(sound: SupervisorSound): String? = when (sound) {
        SupervisorSound.Default -> null
        SupervisorSound.Silent -> ""
        is SupervisorSound.Custom -> sound.uri
    }
}

/** Supervisor 渠道的声音选择。 */
sealed interface SupervisorSound {
    /** 系统默认通知音。 */
    object Default : SupervisorSound

    /** 静音（无声音）。 */
    object Silent : SupervisorSound

    /** 自定义铃声 URI。 */
    data class Custom(val uri: String) : SupervisorSound
}

/** 渠道规格：ID + 版本 + 声音。 */
data class SupervisorChannelSpec(
    val id: String,
    val version: Int,
    val sound: SupervisorSound,
)
