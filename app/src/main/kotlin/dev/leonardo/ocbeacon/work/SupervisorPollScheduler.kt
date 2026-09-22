package dev.leonardo.ocbeacon.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Supervisor 后台轮询调度（WorkManager 唯一周期任务）。
 *
 * 15 分钟 = Android 周期任务下限；[ExistingPeriodicWorkPolicy.KEEP] 保证
 * 应用重启不重置既有排期。约束：网络可用。失败退避：指数、30s 起。
 */
object SupervisorPollScheduler {
    private const val UNIQUE_WORK_NAME = "supervisor_poll"
    private const val INTERVAL_MINUTES = 15L

    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<SupervisorPollWorker>(
            INTERVAL_MINUTES,
            TimeUnit.MINUTES,
        )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }
}
