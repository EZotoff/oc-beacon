package dev.leonardo.ocbeacon.ui.screens.supervisor

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.leonardo.ocbeacon.data.repository.SupervisorSnapshotCache
import dev.leonardo.ocbeacon.domain.model.SupervisorSnapshot
import dev.leonardo.ocbeacon.domain.model.BeaconReply
import dev.leonardo.ocbeacon.domain.model.SupervisorAttentionItem
import dev.leonardo.ocbeacon.domain.repository.SupervisorRepository
import dev.leonardo.ocbeacon.ui.navigation.routes.ServerRouteParams
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
    /** Seam 4 回复发送中/已失败的卡片项 ID（发送成功即移出）。 */
    val replyInFlight: Set<String> = emptySet(),
    val replyFailed: Set<String> = emptySet(),
) {
    val openItems get() = snapshot?.attentionItems.orEmpty()
    val decisions get() = snapshot?.recentDecisions.orEmpty()
}

@HiltViewModel
class SupervisorViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: SupervisorRepository,
    private val cache: SupervisorSnapshotCache,
) : ViewModel() {
    private val serverId: String = checkNotNull(savedStateHandle[ServerRouteParams.PARAM_SERVER_ID])
    // 后台轮询最近一次成功快照作为初始值：同步后打开即为最新，离线仍可展示。
    private val _uiState = MutableStateFlow(
        cache.get(serverId)?.let { SupervisorUiState(snapshot = it, isLoading = false) }
            ?: SupervisorUiState(),
    )
    val uiState: StateFlow<SupervisorUiState> = _uiState.asStateFlow()

    init {
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
                    _uiState.value = SupervisorUiState(snapshot = snapshot, isLoading = false)
                }
                .onFailure {
                    _uiState.update { state ->
                        state.copy(isLoading = false, isRefreshing = false, loadFailed = true)
                    }
                }
        }
    }

    /** Seam 4 beacon reply ingress：把卡片回复作为关联信封事件发送。 */
    fun sendReply(item: SupervisorAttentionItem, text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || item.root.isBlank()) return
        if (item.id in _uiState.value.replyInFlight) return
        _uiState.update {
            it.copy(replyInFlight = it.replyInFlight + item.id, replyFailed = it.replyFailed - item.id)
        }
        viewModelScope.launch {
            repository.sendReply(serverId, item.root, BeaconReply.text(trimmed, explicitItemID = item.id))
                .onSuccess {
                    _uiState.update { state ->
                        state.copy(replyInFlight = state.replyInFlight - item.id, replyFailed = state.replyFailed - item.id)
                    }
                }
                .onFailure {
                    _uiState.update { state ->
                        state.copy(replyInFlight = state.replyInFlight - item.id, replyFailed = state.replyFailed + item.id)
                    }
                }
        }
    }
}
