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

/**
 * Supervisor 通知渠道生命周期（版本化重建）。
 *
 * Android 约束：渠道声音创建后不可变。用户改声音时本类把版本号 +1，
 * 用新 ID 建渠道（携带新声音）、删除旧渠道、持久化版本与声音编码。
 * 通知投递方读取 [currentChannelId] 保证发到当前渠道。
 *
 * 版本/声音编码语义见 [SupervisorChannelVersioning]。
 */
@Singleton
class SupervisorChannelManager @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val settingsRepository: SettingsRepository,
) {
    private val manager: NotificationManager by lazy {
        appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }
    private val mutex = Mutex()

    /** 当前生效的渠道 ID（进程内缓存，[ensureChannel]/[applySound] 更新）。 */
    @Volatile
    var currentChannelId: String = SupervisorChannelVersioning.BASE_CHANNEL_ID
        private set

    private var initialized = false

    /** 当前持久化的声音编码（null=默认，""=静音，其余=URI）。 */
    val soundUriFlow: Flow<String?> get() = settingsRepository.supervisorSoundUri()

    /** 启动/首次通知前确保渠道存在（幂等，进程内缓存）。 */
    suspend fun ensureChannel() {
        mutex.withLock {
            if (initialized) return
            val version = settingsRepository.supervisorChannelVersion().first()
            val stored = settingsRepository.supervisorSoundUri().first()
            val spec = SupervisorChannelVersioning.specFor(version, stored)
            createChannel(spec)
            currentChannelId = spec.id
            initialized = true
        }
    }

    /** 用户选择新声音：版本 +1 → 建新渠道 → 删旧渠道 → 持久化。 */
    suspend fun applySound(sound: SupervisorSound) {
        mutex.withLock {
            val currentVersion = settingsRepository.supervisorChannelVersion().first()
            val targetVersion = SupervisorChannelVersioning.nextVersion(currentVersion)
            val encoded = SupervisorChannelVersioning.encodeSound(sound)
            val spec = SupervisorChannelVersioning.specFor(targetVersion, encoded)
            createChannel(spec)
            SupervisorChannelVersioning.obsoleteChannelId(currentVersion, targetVersion)
                ?.let { manager.deleteNotificationChannel(it) }
            settingsRepository.setSupervisorChannelVersion(targetVersion)
            settingsRepository.setSupervisorSoundUri(encoded)
            currentChannelId = spec.id
            initialized = true
        }
    }

    private fun createChannel(spec: SupervisorChannelSpec) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            spec.id,
            appContext.getString(R.string.notification_channel_supervisor),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = appContext.getString(R.string.notification_channel_supervisor_desc)
            setShowBadge(true)
            enableVibration(true)
            enableLights(true)
            when (val sound = spec.sound) {
                SupervisorSound.Default -> setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                    notificationAudioAttributes(),
                )
                SupervisorSound.Silent -> setSound(null, null)
                is SupervisorSound.Custom -> setSound(Uri.parse(sound.uri), notificationAudioAttributes())
            }
        }
        manager.createNotificationChannel(channel)
    }

    private fun notificationAudioAttributes(): AudioAttributes =
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
}
