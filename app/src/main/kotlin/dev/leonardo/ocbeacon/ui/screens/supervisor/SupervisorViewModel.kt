package dev.leonardo.ocbeacon.ui.screens.supervisor

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.leonardo.ocbeacon.data.repository.SupervisorReplyStateStore
import dev.leonardo.ocbeacon.data.repository.SupervisorSnapshotCache
import dev.leonardo.ocbeacon.domain.model.SupervisorSnapshot
import dev.leonardo.ocbeacon.domain.model.BeaconReply
import dev.leonardo.ocbeacon.domain.model.SupervisorAttentionItem
import dev.leonardo.ocbeacon.domain.repository.SupervisorRepository
import dev.leonardo.ocbeacon.ui.navigation.routes.ServerRouteParams
import dev.leonardo.ocbeacon.ui.navigation.routes.SupervisorNav
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SupervisorUiState(
    val snapshot: SupervisorSnapshot? = null,
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val loadFailed: Boolean = false,
    /** Seam 4 回复发送中/已失败的卡片项 ID（发送成功即移出；镜像自共享 [SupervisorReplyStateStore]）。 */
    val replyInFlight: Set<String> = emptySet(),
    val replyFailed: Set<String> = emptySet(),
    /** 已成功回复的卡片项 ID（展示「Replied」状态芯片；Detail 页回复同样计入）。 */
    val replySent: Set<String> = emptySet(),
    /** root 健康通知落地时 true：Open Items 顶部渲染标注健康上下文。 */
    val healthContext: Boolean = false,
) {
    val openItems get() = snapshot?.attentionItems.orEmpty()
    val decisions get() = snapshot?.recentDecisions.orEmpty()
}

@HiltViewModel
class SupervisorViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: SupervisorRepository,
    private val cache: SupervisorSnapshotCache,
    private val replyStateStore: SupervisorReplyStateStore,
) : ViewModel() {
    private val serverId: String = checkNotNull(savedStateHandle[ServerRouteParams.PARAM_SERVER_ID])
    private val healthContext: Boolean = savedStateHandle[SupervisorNav.PARAM_HEALTH_CONTEXT] ?: false
    // 后台轮询最近一次成功快照作为初始值：同步后打开即为最新，离线仍可展示。
    private val _uiState = MutableStateFlow(
        cache.get(serverId)?.let { SupervisorUiState(snapshot = it, isLoading = false, healthContext = healthContext) }
            ?: SupervisorUiState(healthContext = healthContext),
    )
    val uiState: StateFlow<SupervisorUiState> = _uiState.asStateFlow()

    init {
        // 共享回复状态单一来源：Detail 页发起的回复同样驱动本列表的「Replied」芯片。
        viewModelScope.launch {
            replyStateStore.phases.collect { phases ->
                val mine = phases.filterKeys { it.startsWith("$serverId\u0000") }
                _uiState.update { state ->
                    state.copy(
                        replyInFlight = mine.filterValues { it == SupervisorReplyStateStore.Phase.IN_FLIGHT }.keys
                            .mapTo(mutableSetOf()) { it.substringAfter('\u0000') },
                        replyFailed = mine.filterValues { it == SupervisorReplyStateStore.Phase.FAILED }.keys
                            .mapTo(mutableSetOf()) { it.substringAfter('\u0000') },
                        replySent = mine.filterValues { it == SupervisorReplyStateStore.Phase.SENT }.keys
                            .mapTo(mutableSetOf()) { it.substringAfter('\u0000') },
                    )
                }
            }
        }
        refresh()
    }

    fun refresh() {
        if (_uiState.value.isRefreshing) return
        _uiState.update {
            it.copy(
                isLoading = it.snapshot == null,
                isRefreshing = it.snapshot != null,
                loadFailed = false,
            )
        }
        viewModelScope.launch {
            repository.load(serverId)
                .onSuccess { snapshot ->
                    _uiState.update { state ->
                        SupervisorUiState(
                            snapshot = snapshot,
                            isLoading = false,
                            isRefreshing = false,
                            healthContext = healthContext,
                            replyInFlight = state.replyInFlight,
                            replyFailed = state.replyFailed,
                            replySent = state.replySent,
                        )
                    }
                }
                .onFailure {
                    _uiState.update { state ->
                        state.copy(isLoading = false, isRefreshing = false, loadFailed = true)
                    }
                }
        }
    }

    /** Seam 4 beacon reply ingress：把卡片回复作为关联信封事件发送（状态写穿共享 store）。 */
    fun sendReply(item: SupervisorAttentionItem, text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || item.root.isBlank()) return
        if (replyStateStore.phase(serverId, item.id) == SupervisorReplyStateStore.Phase.IN_FLIGHT) return
        replyStateStore.markInFlight(serverId, item.id)
        viewModelScope.launch {
            repository.sendReply(serverId, item.root, BeaconReply.text(trimmed, explicitItemID = item.id))
                .onSuccess { replyStateStore.markSent(serverId, item.id) }
                .onFailure { replyStateStore.markFailed(serverId, item.id) }
        }
    }
}
