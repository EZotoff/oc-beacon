package dev.leonardo.ocbeacon.service

import dev.leonardo.ocbeacon.data.repository.SupervisorHealthSnapshot
import dev.leonardo.ocbeacon.data.repository.SupervisorSeenStore
import dev.leonardo.ocbeacon.data.repository.SupervisorSnapshotCache
import dev.leonardo.ocbeacon.domain.repository.SupervisorRepository
import dev.leonardo.ocbeacon.domain.supervisor.SupervisorDiff
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 单服务器 Supervisor 轮询编排（worker 的纯逻辑核心，JVM 可测）。
 *
 * 流程：拉取快照 → 写入内存缓存（摘要屏读取）→ 与已见状态 diff →
 * 投递新事项/健康通知 → 覆盖写入已见状态。
 *
 * 失败安全：拉取失败（服务器不可达）直接返回，**不更新已见状态、不通知**——
 * 下一轮周期任务自然重试，绝不因连接噪声打扰用户。
 */
@Singleton
class SupervisorPollCoordinator @Inject constructor(
    private val supervisorRepository: SupervisorRepository,
    private val seenStore: SupervisorSeenStore,
    private val cache: SupervisorSnapshotCache,
    private val notifications: SupervisorNotificationManager,
) {
    suspend fun poll(serverId: String) {
        val snapshot = supervisorRepository.load(serverId).getOrNull() ?: return
        cache.put(serverId, snapshot)

        val seenIds = seenStore.seenItemIds(serverId).first()
        val previousHealth = seenStore.healthSnapshot(serverId).first()

        val newItems = SupervisorDiff.newOpenItems(seenIds, snapshot.attentionItems)
        val newlyFailing = SupervisorDiff.newlyFailingRoots(
            previousHealth.failingRoots,
            snapshot.failingRoots.toSet(),
        )
        val peakIncreased = SupervisorDiff.errorsPeakIncreased(
            previousHealth.errorsPeak,
            snapshot.errorsPeak,
        )

        newItems.forEach { notifications.notifyAttentionItem(serverId, it) }
        if (newlyFailing.isNotEmpty() || peakIncreased) {
            notifications.notifyRootHealth(serverId, newlyFailing, snapshot.errorsPeak, peakIncreased)
        }

        // 覆盖为当前开放 id 集合：已 resolved 的 id 自然淘汰，seen 集合不随 queue 增长。
        seenStore.saveSeenItemIds(serverId, snapshot.attentionItems.map { it.id }.toSet())
        seenStore.saveHealthSnapshot(
            serverId,
            SupervisorHealthSnapshot(snapshot.failingRoots.toSet(), snapshot.errorsPeak),
        )
    }
}
