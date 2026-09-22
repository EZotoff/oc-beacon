package dev.leonardo.ocbeacon.service

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.leonardo.ocbeacon.MainActivity
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.domain.model.SupervisorAttentionItem
import dev.leonardo.ocbeacon.util.PathUtils
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Supervisor 后台轮询的本地通知投递（专用渠道 [NotificationChannels.SUPERVISOR]）。
 *
 * 两种通知形态：
 * - 每个新开放事项一条（标题=问题，正文=项目 · 年龄），点击进入该服务器的摘要页；
 * - root 健康失败/错误峰值上升一条（固定 id，重复触发互相替换，不堆积）。
 *
 * 通知 id 用 FNV-1a 稳定 hash（与 [AppNotificationManager] 同款），
 * 保证同一事项跨轮询替换而非重复堆积。不记录/不打印任何凭据。
 */
@Singleton
class SupervisorNotificationManager @Inject constructor(
    @ApplicationContext private val appContext: Context,
) {
    private val manager: NotificationManager by lazy {
        appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    /** 新开放事项通知：标题取问题文本，正文为「项目 · 年龄」。 */
    fun notifyAttentionItem(serverId: String, item: SupervisorAttentionItem) {
        val title = item.question.ifBlank { appContext.getString(R.string.supervisor_title) }
        val text = "${projectLabel(item.project)} · ${age(item.createdAt)}"
        val notification = NotificationCompat.Builder(appContext, NotificationChannels.SUPERVISOR)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(supervisorPendingIntent(serverId, item.id.hashCode()))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setGroup("supervisor_$serverId")
            .build()
        manager.notify(notificationId(serverId, item.id), notification)
    }

    /**
     * root 健康通知：新进入 failing 的 root 与/或错误峰值上升。
     * 固定 id——同一服务器的健康通知只保留最新一条。
     */
    fun notifyRootHealth(
        serverId: String,
        failingRoots: List<String>,
        errorsPeak: Int,
        peakIncreased: Boolean,
    ) {
        val title = if (failingRoots.isNotEmpty()) {
            appContext.getString(R.string.supervisor_notification_root_failing_title, failingRoots.size)
        } else {
            appContext.getString(R.string.supervisor_notification_error_peak_title)
        }
        val body = buildString {
            if (failingRoots.isNotEmpty()) {
                append(failingRoots.joinToString(", ") { projectLabel(it) })
            }
            if (peakIncreased) {
                if (isNotEmpty()) append(" · ")
                append(appContext.getString(R.string.supervisor_notification_error_peak, errorsPeak))
            }
        }
        val notification = NotificationCompat.Builder(appContext, NotificationChannels.SUPERVISOR)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(supervisorPendingIntent(serverId, ROOT_HEALTH_REQUEST_CODE))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setGroup("supervisor_$serverId")
            .build()
        manager.notify(notificationId(serverId, ROOT_HEALTH_KEY), notification)
    }

    private fun supervisorPendingIntent(serverId: String, requestCode: Int): PendingIntent {
        val intent = Intent(appContext, MainActivity::class.java).apply {
            action = ACTION_OPEN_SUPERVISOR
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_SERVER_ID, serverId)
        }
        return PendingIntent.getActivity(
            appContext,
            requestCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun projectLabel(root: String): String = PathUtils.fileName(root).ifBlank { root }

    private fun age(timestamp: String): String {
        val minutes = runCatching {
            Duration.between(Instant.parse(timestamp), Instant.now()).toMinutes().coerceAtLeast(0)
        }.getOrDefault(0)
        return when {
            minutes < 60 -> "${minutes}m"
            minutes < 1_440 -> "${minutes / 60}h"
            else -> "${minutes / 1_440}d"
        }
    }

    private fun notificationId(serverId: String, key: String): Int = stableHash(serverId, key)

    /** FNV-1a 32 位稳定 hash（与 AppNotificationManager 同款，跨进程一致）。 */
    private fun stableHash(vararg parts: String): Int {
        var hash = 0x811c9dc5.toInt()
        for (part in parts) {
            for (i in part.indices) {
                hash = (hash xor part[i].code) * 0x01000193
            }
        }
        return hash
    }

    companion object {
        /** 通知点击动作：打开 Supervisor 摘要页（MainActivity 消费）。 */
        const val ACTION_OPEN_SUPERVISOR = "dev.leonardo.ocbeacon.OPEN_SUPERVISOR"

        /** 通知点击携带的服务器 id extra。 */
        const val EXTRA_SERVER_ID = "supervisor_server_id"

        private const val ROOT_HEALTH_KEY = "root-health"
        private const val ROOT_HEALTH_REQUEST_CODE = 0x5A17
    }
}
