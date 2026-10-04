package dev.leonardo.ocbeacon.ui.screens.supervisor

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.leonardo.ocbeacon.data.repository.SupervisorReplyStateStore
import dev.leonardo.ocbeacon.data.repository.SupervisorSnapshotCache
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

data class SupervisorDetailUiState(
    val item: SupervisorAttentionItem? = null,
    val stale: Boolean = false,
    val staleReason: String? = null,
    val isLoading: Boolean = true,
    /** Answer 节（task 9 卡片回复迁入此处）：按 itemId 线程化回复状态（镜像自共享 [SupervisorReplyStateStore]）。 */
    val replyInFlight: Boolean = false,
    val replyFailed: Boolean = false,
) {
    val itemMissing: Boolean get() = !isLoading && item == null
}

/**
 * 事项 Detail 的 ViewModel：按 itemId 从缓存/仓库快照定位单一事项，
 * 渲染五节上下文 + Answer 节。回复走与卡片此前完全相同的 Seam 4
 * [SupervisorRepository.sendReply] 路径——仅 UI 调用点迁移，无逻辑改动；
 * 回复状态写穿共享 [SupervisorReplyStateStore]，卡片列表的「Replied」芯片
 * 因此能看到 Detail 发起的回复。
 */
@HiltViewModel
class SupervisorDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: SupervisorRepository,
    private val cache: SupervisorSnapshotCache,
    private val replyStateStore: SupervisorReplyStateStore,
) : ViewModel() {
    private val serverId: String = checkNotNull(savedStateHandle[ServerRouteParams.PARAM_SERVER_ID])
    private val itemId: String = checkNotNull(savedStateHandle[SupervisorNav.PARAM_ITEM_ID])

    private val _uiState = MutableStateFlow(seedFromCache())
    val uiState: StateFlow<SupervisorDetailUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            replyStateStore.phases.collect { phases ->
                val phase = phases["$serverId\u0000$itemId"]
                _uiState.update { state ->
                    state.copy(
                        replyInFlight = phase == SupervisorReplyStateStore.Phase.IN_FLIGHT,
                        replyFailed = phase == SupervisorReplyStateStore.Phase.FAILED,
                    )
                }
            }
        }
        refresh()
    }

    private fun seedFromCache(): SupervisorDetailUiState {
        val snapshot = cache.get(serverId) ?: return SupervisorDetailUiState()
        return stateFromSnapshot(snapshot, isLoading = false)
    }

    fun refresh() {
        viewModelScope.launch {
            repository.load(serverId)
                .onSuccess { snapshot ->
                    _uiState.update { state ->
                        stateFromSnapshot(snapshot, isLoading = false).copy(
                            replyInFlight = state.replyInFlight,
                            replyFailed = state.replyFailed,
                        )
                    }
                }
                .onFailure {
                    _uiState.update { state ->
                        if (state.isLoading) state.copy(isLoading = false) else state
                    }
                }
        }
    }

    private fun stateFromSnapshot(
        snapshot: dev.leonardo.ocbeacon.domain.model.SupervisorSnapshot,
        isLoading: Boolean,
    ): SupervisorDetailUiState {
        val item = snapshot.attentionItems.firstOrNull { it.id == itemId }
        return SupervisorDetailUiState(
            item = item,
            stale = snapshot.stale,
            staleReason = snapshot.staleReason,
            isLoading = isLoading,
        )
    }

    /** Seam 4 beacon reply ingress：与卡片回复同一条发送路径（仅调用点迁移；状态写穿共享 store）。 */
    fun sendReply(text: String) {
        val item = _uiState.value.item ?: return
        val trimmed = text.trim()
        if (trimmed.isEmpty() || item.root.isBlank() || _uiState.value.stale) return
        if (replyStateStore.phase(serverId, item.id) == SupervisorReplyStateStore.Phase.IN_FLIGHT) return
        replyStateStore.markInFlight(serverId, item.id)
        viewModelScope.launch {
            repository.sendReply(
                serverId,
                item.root,
                BeaconReply.text(trimmed, explicitItemID = item.id, question = item.question, sessionTitle = item.sessionLabel),
            )
                .onSuccess { replyStateStore.markSent(serverId, item.id) }
                .onFailure { replyStateStore.markFailed(serverId, item.id) }
        }
    }
}
