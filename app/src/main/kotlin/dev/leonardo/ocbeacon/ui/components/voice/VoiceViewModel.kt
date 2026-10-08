package dev.leonardo.ocbeacon.ui.components.voice

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.leonardo.ocbeacon.data.api.voice.ShowFrame
import dev.leonardo.ocbeacon.data.api.voice.ViewContextView
import dev.leonardo.ocbeacon.data.repository.SupervisorSnapshotCache
import dev.leonardo.ocbeacon.domain.repository.SettingsRepository
import dev.leonardo.ocbeacon.domain.voice.VoiceSessionConnection
import dev.leonardo.ocbeacon.domain.voice.VoiceSessionRepository
import dev.leonardo.ocbeacon.domain.voice.VoiceUrl
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * UI seam for the voice walking-test widget: bridges the singleton
 * [VoiceSessionRepository] into Compose state and resolves the omo-pulse
 * base URL from settings at connect time.
 */
@HiltViewModel
class VoiceViewModel @Inject constructor(
    private val repository: VoiceSessionRepository,
    private val settingsRepository: SettingsRepository,
    snapshotCache: SupervisorSnapshotCache,
) : ViewModel() {

    val connection: StateFlow<VoiceSessionConnection> = repository.state

    val showFrame: StateFlow<ShowFrame?> = repository.showFrame

    /** Non-null message = audio engine failure (recorder/playback). */
    val audioFailure: StateFlow<String?> = repository.audioFailure

    /** True while audio is suspended after a transient audio-focus loss → overlay. */
    val focusLost: StateFlow<Boolean> = repository.focusLost

    /** Widget renders only while a supervisor snapshot exists. */
    val hasSnapshot: StateFlow<Boolean> = snapshotCache.snapshots
        .map { it.isNotEmpty() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** PTT down: reconnects from Disconnected/Moved using the stored URL, then holds. */
    fun pressPtt() {
        viewModelScope.launch {
            when (connection.value) {
                VoiceSessionConnection.Disconnected, VoiceSessionConnection.MovedToAnotherSurface -> {
                    val url = VoiceUrl.normalize(
                        settingsRepository.getSettingsFlow().first().omoPulseUrl,
                    ) ?: return@launch
                    repository.connect(url)
                }
                VoiceSessionConnection.Connecting, VoiceSessionConnection.Live -> Unit
            }
            repository.pressPtt()
        }
    }

    fun releasePtt() {
        repository.releasePtt()
    }

    fun sendSelection(contextTag: String, index: Int) {
        repository.sendSelection(contextTag, index)
    }

    /** Navigation seam: null = unmapped screen, keep the last emitted context. */
    fun emitViewContext(view: ViewContextView?) {
        if (view != null) repository.emitViewContext(view)
    }

}
