package dev.leonardo.ocbeacon.data.repository

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Seam 4 回复状态的跨 ViewModel 单一副本（glance→dive→answer 一条漏斗）。
 *
 * 卡片列表（SupervisorViewModel 的「Replied」芯片）与 Detail 页
 * （SupervisorDetailViewModel 的 Answer 节）此前各自持有回复状态，互相不可见——
 * Detail 里发出的回复不会反映到列表芯片。本 store 与 [SupervisorSeenStore] 同为
 * @Singleton 共享存储：两个 ViewModel 都从这里读，写方（发起 sendReply 的那个）
 * 在调用 repository 前后把状态写穿到这里。纯内存——回复状态不跨进程持久化。
 */
@Singleton
class SupervisorReplyStateStore @Inject constructor() {

    /** 回复阶段：发送中 / 已送达 / 已失败。 */
    enum class Phase { IN_FLIGHT, SENT, FAILED }

    private val _phases = MutableStateFlow<Map<String, Phase>>(emptyMap())

    /** 按 "serverId\u0000itemId" 键控的当前回复阶段快照。 */
    val phases: StateFlow<Map<String, Phase>> = _phases.asStateFlow()

    fun phase(serverId: String, itemId: String): Phase? = _phases.value[key(serverId, itemId)]

    fun markInFlight(serverId: String, itemId: String) = set(serverId, itemId, Phase.IN_FLIGHT)

    fun markSent(serverId: String, itemId: String) = set(serverId, itemId, Phase.SENT)

    fun markFailed(serverId: String, itemId: String) = set(serverId, itemId, Phase.FAILED)

    private fun set(serverId: String, itemId: String, phase: Phase) {
        _phases.update { it + (key(serverId, itemId) to phase) }
    }

    private fun key(serverId: String, itemId: String): String = "$serverId\u0000$itemId"
}
