package dev.leonardo.ocbeacon.data.repository

import dev.leonardo.ocbeacon.domain.model.SupervisorSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 后台轮询最近一次成功快照的进程级内存缓存（按 serverId）。
 *
 * 轮询 worker 写入；摘要屏 [dev.leonardo.ocbeacon.ui.screens.supervisor.SupervisorViewModel]
 * 读取作为初始值——后台同步后打开页面即为最新，且离线时仍可展示上次结果。
 * 不持久化：进程重启后由下一次轮询或前台刷新重建。
 */
@Singleton
class SupervisorSnapshotCache @Inject constructor() {
    private val _snapshots = MutableStateFlow<Map<String, SupervisorSnapshot>>(emptyMap())
    val snapshots: StateFlow<Map<String, SupervisorSnapshot>> = _snapshots.asStateFlow()

    fun put(serverId: String, snapshot: SupervisorSnapshot) {
        _snapshots.update { it + (serverId to snapshot) }
    }

    fun get(serverId: String): SupervisorSnapshot? = _snapshots.value[serverId]
}
