package dev.leonardo.ocbeacon.ui.screens.portable

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.leonardo.ocbeacon.data.api.voice.*
import dev.leonardo.ocbeacon.data.repository.SupervisorSnapshotCache
import dev.leonardo.ocbeacon.domain.model.SupervisorAttentionItem
import dev.leonardo.ocbeacon.domain.repository.SessionRepository
import dev.leonardo.ocbeacon.domain.repository.SupervisorRepository
import dev.leonardo.ocbeacon.domain.voice.VoiceSessionRepository
import dev.leonardo.ocbeacon.logging.AppLogger
import dev.leonardo.ocbeacon.util.runCatchingCancellable
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PortableAttention(val serverId: String, val item: SupervisorAttentionItem, val stale: Boolean)
data class PortableUiState(
    val projects: List<PortableProject> = emptyList(),
    val attention: List<PortableAttention> = emptyList(),
    val selection: PortableSelectionState = PortableSelectionState(),
    val unavailable: Boolean = true,
) {
    val project get() = selection.project(projects)
    val session get() = selection.session(projects)
}

@HiltViewModel
class PortableModeViewModel @Inject constructor(
    private val sessions: SessionRepository,
    private val supervisor: SupervisorRepository,
    private val cache: SupervisorSnapshotCache,
    private val voice: VoiceSessionRepository,
) : ViewModel() {
    private val mutable = MutableStateFlow(PortableUiState())
    val state = mutable.asStateFlow()
    private var sources = emptySet<String>()
    private var jobs = emptyList<Job>()
    private val catalogs = mutableMapOf<String, List<PortableProject>>()
    private var visualSelection: VoiceSelection? = null

    fun observeServers(serverIds: Set<String>) {
        if (serverIds == sources) return
        jobs.forEach { it.cancel() }
        sources = serverIds
        catalogs.keys.retainAll(serverIds)
        publish()
        jobs = serverIds.flatMap { id ->
            listOf(viewModelScope.launch {
                combine(sessions.getSessionsFlow(id), sessions.getSessionStatusesFlow(id), cache.snapshots) { list, statuses, snapshots ->
                    PortableCatalog.build(id, list, statuses, snapshots[id])
                }.collect {
                    catalogs[id] = it
                    publish()
                }
            }, viewModelScope.launch {
                runCatchingCancellable { sessions.listSessions(id, limit = 50) }
                    .onSuccess { loaded ->
                        sessions.setSessions(id, (sessions.getSessionsFlow(id).first() + loaded)
                            .groupBy { it.id }.values.map { copies -> copies.maxBy { it.time.updated } })
                    }
                    .onFailure { AppLogger.w("PortableMode", "Recent sessions unavailable: ${it.message}") }
                while (isActive) {
                    supervisor.load(id).onSuccess { cache.put(id, it) }.onFailure {
                        cache.get(id)?.let { old -> cache.put(id, old.copy(stale = true)) }
                        mutable.value = mutable.value.copy(unavailable = true)
                        AppLogger.w("PortableMode", "Supervisor unavailable: ${it.message}")
                    }
                    delay(5_000)
                }
            })
        }
    }

    fun stopObserving() {
        jobs.forEach { it.cancel() }
        jobs = emptyList()
        sources = emptySet()
    }

    private fun publish() {
        val projects = catalogs.values.flatten().sortedByDescending { it.sessions.firstOrNull()?.updated ?: 0L }
        val attention = sources.flatMap { id -> cache.get(id)?.let { s ->
            s.attentionItems.filter { it.escalationKind != "INFORMATION" }.map { PortableAttention(id, it, s.stale) }
        }.orEmpty() }
        val old = state.value
        val project = projects.firstOrNull { it.key == old.project?.key } ?: projects.firstOrNull()
        val session = project?.sessions?.firstOrNull { it.id == old.selection.sessionIds[project.key] }
            ?: project?.sessions?.firstOrNull { it.id == old.session?.id } ?: project?.sessions?.firstOrNull()
        val selection = old.selection.copy(projectKey = project?.key,
            sessionIds = if (project != null && session != null) old.selection.sessionIds + (project.key to session.id) else old.selection.sessionIds)
        mutable.value = mutable.value.copy(projects = projects, attention = attention, selection = selection,
            unavailable = sources.isEmpty() || sources.any { cache.get(it)?.stale != false })
    }

    fun pageProject(delta: Int) = change { it.pageProject(state.value.projects, delta) }
    fun pageSession(delta: Int) = change { it.pageSession(state.value.projects, delta) }
    fun previous() = change { it.returnToPrevious(state.value.projects) }

    private fun change(transform: (PortableSelectionState) -> PortableSelectionState) {
        visualSelection = null
        mutable.value = mutable.value.copy(selection = transform(state.value.selection))
        emitContext()
    }

    fun emitContext(view: ViewContextView = ViewContextView.SUPERVISOR) {
        val context = state.value.selection.context(state.value.projects, view, visualSelection)
        voice.emitViewContext(context.view, context.project, context.session, context.selection)
    }

    fun clearShowSelection() {
        visualSelection = null
    }

    fun selectShow(tag: String, index: Int, label: String, kind: SelectionKind, view: ViewContextView) {
        visualSelection = VoiceSelection(kind, "$tag:$index", label)
        voice.sendSelection(tag, index)
        emitContext(view)
    }

    fun selectAttention(attention: PortableAttention) {
        if (attention.stale) return
        val item = attention.item
        val p = state.value.projects.firstOrNull { it.serverId == attention.serverId && it.root == item.root }
        if (p != null) mutable.value = mutable.value.copy(selection = state.value.selection.select(state.value.projects, p.key))
        voice.emitViewContext(ViewContextView.ATTENTION, VoiceProject(item.root, item.project),
            VoiceSession("", item.sessionLabel, VoiceSessionState.WAITING),
            VoiceSelection(SelectionKind.CARD, item.id, item.question))
    }
}
