package dev.leonardo.ocbeacon.ui.screens.supervisor

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.leonardo.ocbeacon.domain.model.SupervisorSnapshot
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
)

@HiltViewModel
class SupervisorViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: SupervisorRepository,
) : ViewModel() {
    private val serverId: String = checkNotNull(savedStateHandle[ServerRouteParams.PARAM_SERVER_ID])
    private val _uiState = MutableStateFlow(SupervisorUiState())
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
}
