package dev.leonardo.ocbeacon.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

class VersionedSoundChannel(
    private val context: Context,
    private val baseId: String,
    private val name: Int,
    private val description: Int,
    val soundUriFlow: Flow<String?>,
    private val versionFlow: Flow<Int>,
    private val save: suspend (Int, String?) -> Unit,
) {
    private val manager: NotificationManager by lazy {
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }
    private val mutex = Mutex()
    @Volatile var currentChannelId: String = baseId
        private set
    private var initialized = false

    suspend fun ensureChannel() = mutex.withLock {
        if (initialized) return@withLock
        val spec = ChannelVersioning.specFor(baseId, versionFlow.first(), soundUriFlow.first())
        createChannel(spec, null)
        currentChannelId = spec.id
        initialized = true
    }

    suspend fun applySound(sound: ChannelSound) = mutex.withLock {
        val currentVersion = versionFlow.first()
        val oldId = ChannelVersioning.channelId(baseId, currentVersion)
        val old = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) manager.getNotificationChannel(oldId) else null
        val next = ChannelVersioning.nextVersion(currentVersion)
        val encoded = ChannelVersioning.encodeSound(sound)
        val spec = ChannelVersioning.specFor(baseId, next, encoded)
        createChannel(spec, old)
        save(next, encoded)
        currentChannelId = spec.id
        initialized = true
        ChannelVersioning.obsoleteChannelId(baseId, currentVersion, next)?.let {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) manager.deleteNotificationChannel(it)
        }
    }

    private fun createChannel(spec: ChannelSpec, old: NotificationChannel?) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val defaults = ChannelProperties(
            NotificationManager.IMPORTANCE_HIGH, true, null, android.app.Notification.VISIBILITY_PRIVATE,
            context.getString(description), true, true, 0,
        )
        val properties = preservedChannelProperties(old?.let {
            ChannelProperties(it.importance, it.shouldVibrate(), it.vibrationPattern,
                it.lockscreenVisibility, it.description, it.canShowBadge(), it.shouldShowLights(), it.lightColor)
        }, defaults)
        val channel = NotificationChannel(spec.id, context.getString(name), properties.importance).apply {
            this.description = properties.description
            lockscreenVisibility = properties.lockscreenVisibility
            setShowBadge(properties.showBadge)
            enableVibration(properties.vibrate)
            properties.vibrationPattern?.let { vibrationPattern = it }
            enableLights(properties.lights)
            lightColor = properties.lightColor
            val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
            when (val sound = spec.sound) {
                ChannelSound.Default -> setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION), attributes)
                ChannelSound.Silent -> setSound(null, null)
                is ChannelSound.Custom -> setSound(Uri.parse(sound.uri), attributes)
            }
        }
        manager.createNotificationChannel(channel)
    }
}

@Singleton
class SupervisorChannelManager @Inject constructor(
    @ApplicationContext context: Context,
    settings: SettingsRepository,
) {
    private val channel = VersionedSoundChannel(context, NotificationChannels.SUPERVISOR,
        R.string.notification_channel_supervisor, R.string.notification_channel_supervisor_desc,
        settings.supervisorSoundUri(), settings.supervisorChannelVersion()) { version, uri ->
        settings.setSupervisorChannelSound(version, uri)
    }
    val currentChannelId get() = channel.currentChannelId
    val soundUriFlow get() = channel.soundUriFlow
    suspend fun ensureChannel() = channel.ensureChannel()
    suspend fun applySound(sound: ChannelSound) = channel.applySound(sound)
}

@Singleton
class TurnChannelManager @Inject constructor(
    @ApplicationContext context: Context,
    settings: SettingsRepository,
) {
    private val channel = VersionedSoundChannel(context, NotificationChannels.TASKS,
        R.string.notification_channel_tasks, R.string.notification_channel_tasks_desc,
        settings.turnSoundUri(), settings.turnChannelVersion()) { version, uri ->
        settings.setTurnChannelSound(version, uri)
    }
    val currentChannelId get() = channel.currentChannelId
    val soundUriFlow get() = channel.soundUriFlow
    suspend fun ensureChannel() = channel.ensureChannel()
    suspend fun applySound(sound: ChannelSound) = channel.applySound(sound)
}
