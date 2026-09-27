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
import dev.leonardo.ocbeacon.logging.AppLogger
import dev.leonardo.ocbeacon.util.PathUtils
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Supervisor 后台轮询的本地通知投递（专用渠道 [NotificationChannels.SUPERVISOR]，
 * 实际 ID 由 [SupervisorChannelManager] 版本化派生）。
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
    private val channelManager: SupervisorChannelManager,
) {
    private val manager: NotificationManager by lazy {
        appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    /** 新开放事项推送：仅 APPROVAL/DECISION（过滤见 [SupervisorPushSpec]），三层内容全部来自已发布字段。 */
    suspend fun notifyAttentionItem(serverId: String, item: SupervisorAttentionItem) {
        if (!SupervisorPushSpec.shouldNotify(item)) return
        channelManager.ensureChannel()
        val project = projectLabel(item.project)
        val reason = item.reasonText.ifBlank { item.question }
        val title = if (item.escalationKind == "APPROVAL") {
            appContext.getString(R.string.supervisor_push_title_approval, project)
        } else {
            appContext.getString(R.string.supervisor_push_title_decision, project)
        }
        val text = appContext.getString(
            R.string.supervisor_push_body,
            item.sessionLabel.ifBlank { project },
            age(item.createdAt),
            reason.take(PUSH_BODY_REASON_CHARS),
        )
        val bigText = buildString {
            append(appContext.getString(R.string.supervisor_detail_section_what_happened)).append('\n')
            append(project)
            if (item.sessionLabel.isNotBlank()) append(" · ").append(item.sessionLabel)
            append(" · ").append(age(item.createdAt))
            append("\n\n")
            append(appContext.getString(R.string.supervisor_detail_section_judgment)).append('\n')
            append(reason)
        }
        val notification = NotificationCompat.Builder(appContext, channelManager.currentChannelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(
                supervisorPendingIntent(
                    SupervisorPushSpec.attentionTarget(serverId, item.id),
                    SupervisorPushSpec.attentionRequestCode(serverId, item.id),
                ),
            )
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        manager.notify(SupervisorPushSpec.attentionNotificationId(serverId, item.id), notification)
        AppLogger.i("SupervisorNotif", "path=supervisor-poll serverId=$serverId itemId=${item.id} kind=attention")
    }

    /**
     * root 健康通知：新进入 failing 的 root 与/或错误峰值上升。
     * 固定 id——同一服务器的健康通知只保留最新一条。点击落到带健康上下文标注的 Open Items。
     */
    suspend fun notifyRootHealth(
        serverId: String,
        failingRoots: List<String>,
        errorsPeak: Int,
        peakIncreased: Boolean,
    ) {
        channelManager.ensureChannel()
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
        val notification = NotificationCompat.Builder(appContext, channelManager.currentChannelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(
                supervisorPendingIntent(
                    SupervisorPushSpec.rootHealthTarget(serverId),
                    SupervisorPushSpec.rootHealthRequestCode(serverId),
                ),
            )
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        manager.notify(SupervisorPushSpec.rootHealthNotificationId(serverId), notification)
        AppLogger.i("SupervisorNotif", "path=supervisor-poll serverId=$serverId failingRoots=${failingRoots.size} errorsPeak=$errorsPeak kind=root-health")
    }

    private fun supervisorPendingIntent(target: SupervisorOpenTarget, requestCode: Int): PendingIntent {
        val intent = Intent(appContext, MainActivity::class.java).apply {
            action = ACTION_OPEN_SUPERVISOR
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_SERVER_ID, target.serverId)
            if (target.itemId != null) putExtra(EXTRA_ITEM_ID, target.itemId)
            if (target.healthContext) putExtra(EXTRA_HEALTH_CONTEXT, true)
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


    companion object {
        /** 通知点击动作：打开 Supervisor 摘要页（MainActivity 消费）。 */
        const val ACTION_OPEN_SUPERVISOR = "dev.leonardo.ocbeacon.OPEN_SUPERVISOR"

        /** 通知点击携带的服务器 id extra。 */
        const val EXTRA_SERVER_ID = "supervisor_server_id"
        /** 通知点击可携带的注意事项 id extra（→ 该事项 Detail）。 */
        const val EXTRA_ITEM_ID = "supervisor_item_id"

        /** root 健康通知携带的 extra（→ Open Items 标注健康上下文）。 */
        const val EXTRA_HEALTH_CONTEXT = "supervisor_health_context"


        private const val PUSH_BODY_REASON_CHARS = 100
    }
}
