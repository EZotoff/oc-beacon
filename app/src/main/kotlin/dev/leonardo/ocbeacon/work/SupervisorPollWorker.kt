package dev.leonardo.ocbeacon.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.leonardo.ocbeacon.domain.model.ServerType
import dev.leonardo.ocbeacon.domain.repository.ServerRepository
import dev.leonardo.ocbeacon.logging.AppLogger
import dev.leonardo.ocbeacon.service.SupervisorPollCoordinator
import kotlinx.coroutines.flow.first

private const val TAG = "SupervisorPoll"

/**
 * Supervisor 后台轮询 worker（WorkManager 周期任务，15 分钟 = Android 下限）。
 *
 * 遍历所有 OpenCode 服务器，逐个委托 [SupervisorPollCoordinator] 拉取并通知。
 * 单服务器失败被隔离（runCatching），不影响其他服务器；整体恒返回
 * [Result.success]——周期任务下一轮自然重试，避免 WorkManager 退避重试放大
 * 连接噪声（服务器不可达时用户不应收到任何通知）。
 *
 * 延迟包络：周期任务受 Doze/厂商省电批处理影响，实际触发可能晚于 15 分钟。
 * 对工单式升级（异步设计）可接受；亚分钟级推送需 FCM/前台服务（backlog）。
 */
@HiltWorker
class SupervisorPollWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val serverRepository: ServerRepository,
    private val coordinator: SupervisorPollCoordinator,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val servers = runCatching { serverRepository.getServersFlow().first() }
            .getOrDefault(emptyList())
            .filter { it.serverType == ServerType.OpenCode }
        for (server in servers) {
            runCatching { coordinator.poll(server.id) }
                .onFailure { AppLogger.w(TAG, "poll failed for ${server.id}: ${it.message}") }
        }
        return Result.success()
    }
}
