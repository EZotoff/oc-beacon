package dev.leonardo.ocbeacon.service

import dev.leonardo.ocbeacon.domain.model.SupervisorAttentionItem

/**
 * 通知点击落点（纯数据）：item 详情页或 root 健康上下文。
 * 供 [SupervisorNotificationManager] 组装 Intent extras，也是单测的纯断言面。
 */
internal data class SupervisorOpenTarget(
    val serverId: String,
    val itemId: String?,
    val healthContext: Boolean,
)

/**
 * 纯推送决策层（无 Android 依赖）：
 * - 过滤：仅 APPROVAL/DECISION 推送（缺省/INFORMATION/未知值一律不推——fail-safe 静默）；
 * - 身份：通知 id 与 pending-intent requestCode 都按 (serverId, itemId) 稳定 hash 派生，
 *   跨轮询替换而非堆积；
 * - root 健康：固定 key 派生 id + 按 serverId 派生的 requestCode（同服务器只保留最新一条）。
 */
internal object SupervisorPushSpec {

    fun shouldNotify(item: SupervisorAttentionItem): Boolean =
        item.escalationKind == "APPROVAL" || item.escalationKind == "DECISION"

    fun attentionTarget(serverId: String, itemId: String) =
        SupervisorOpenTarget(serverId, itemId, healthContext = false)

    fun rootHealthTarget(serverId: String) =
        SupervisorOpenTarget(serverId, itemId = null, healthContext = true)

    fun attentionNotificationId(serverId: String, itemId: String): Int =
        stableHash("supervisor-item:", serverId, itemId)

    fun attentionRequestCode(serverId: String, itemId: String): Int =
        stableHash("supervisor-item:", serverId, itemId)

    fun rootHealthNotificationId(serverId: String): Int =
        stableHash("supervisor-health:", serverId, ROOT_HEALTH_KEY)

    /** Per-server requestCode：extras 不区分 PendingIntent，requestCode 必须按 serverId 派生。 */
    fun rootHealthRequestCode(serverId: String): Int =
        stableHash("supervisor-health:", serverId, ROOT_HEALTH_KEY)

    /** FNV-1a 32 位稳定 hash（与 AppNotificationManager 同款，跨进程一致）。 */
    fun stableHash(vararg parts: String): Int {
        var hash = 0x811c9dc5.toInt()
        for (part in parts) {
            for (i in part.indices) {
                hash = (hash xor part[i].code) * 0x01000193
            }
        }
        return hash
    }

    const val ROOT_HEALTH_KEY = "root-health"
}
