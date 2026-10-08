package dev.leonardo.ocbeacon.ui.screens.portable

import dev.leonardo.ocbeacon.data.api.voice.*
import dev.leonardo.ocbeacon.domain.model.Session
import dev.leonardo.ocbeacon.domain.model.SessionStatus
import dev.leonardo.ocbeacon.domain.model.SupervisorSnapshot
import dev.leonardo.ocbeacon.util.PathUtils

data class PortableSession(val id: String, val title: String, val status: SessionStatus?, val updated: Long)
data class PortableProject(val serverId: String, val root: String, val label: String, val sessions: List<PortableSession>) {
    val key: String get() = "$serverId\u0000$root"
}

data class PortableSelectionState(
    val projectKey: String? = null,
    val sessionIds: Map<String, String> = emptyMap(),
    val previous: Pair<String, String?>? = null,
) {
    fun project(projects: List<PortableProject>): PortableProject? =
        projects.firstOrNull { it.key == projectKey } ?: projects.firstOrNull()

    fun session(projects: List<PortableProject>): PortableSession? = project(projects)?.let { p ->
        p.sessions.firstOrNull { it.id == sessionIds[p.key] } ?: p.sessions.firstOrNull()
    }

    fun select(projects: List<PortableProject>, key: String, sessionId: String? = null): PortableSelectionState {
        val target = projects.firstOrNull { it.key == key } ?: return this
        val current = project(projects)
        return copy(
            projectKey = target.key,
            sessionIds = if (sessionId == null) sessionIds else sessionIds + (target.key to sessionId),
            previous = current?.let { it.key to session(projects)?.id },
        )
    }

    fun pageProject(projects: List<PortableProject>, delta: Int): PortableSelectionState {
        val current = project(projects) ?: return this
        val next = projects[Math.floorMod(projects.indexOf(current) + delta, projects.size)]
        return select(projects, next.key)
    }

    fun pageSession(projects: List<PortableProject>, delta: Int): PortableSelectionState {
        val p = project(projects) ?: return this
        val s = session(projects) ?: return this
        val next = p.sessions[Math.floorMod(p.sessions.indexOf(s) + delta, p.sessions.size)]
        return select(projects, p.key, next.id)
    }

    fun returnToPrevious(projects: List<PortableProject>): PortableSelectionState =
        previous?.let { select(projects, it.first, it.second) } ?: this

    fun context(projects: List<PortableProject>, view: ViewContextView = ViewContextView.SUPERVISOR,
                selection: VoiceSelection? = null): ClientControlFrame.ViewContext {
        val p = project(projects)
        val s = session(projects)
        return ClientControlFrame.ViewContext(
            view = view,
            // Empty objects explicitly prevent repository fallback to an unrelated attention item.
            project = VoiceProject(p?.root.orEmpty(), p?.label.orEmpty()),
            session = VoiceSession(s?.id.orEmpty(), s?.title.orEmpty(), when (s?.status) {
                SessionStatus.Busy, is SessionStatus.Retry -> VoiceSessionState.RUNNING
                else -> VoiceSessionState.WAITING
            }),
            selection = selection,
        )
    }
}

object PortableCatalog {
    fun build(serverId: String, sessions: List<Session>, statuses: Map<String, SessionStatus>,
              snapshot: SupervisorSnapshot?): List<PortableProject> {
        val roots = snapshot?.attentionItems.orEmpty().filter { it.root.isNotBlank() }
            .associate { normalize(it.root) to it.project }
        val groups = sessions.filter { it.time.archived == null }.groupBy { session ->
            val directory = normalize(session.directory)
            roots.keys.filter { directory == it || directory.startsWith("$it/") }
                .maxByOrNull { it.length } ?: directory
        }
        return (groups.keys + roots.keys).distinct().map { root ->
            PortableProject(serverId, root, roots[root] ?: PathUtils.fileName(root).ifBlank { root },
                groups[root].orEmpty().sortedByDescending { it.time.updated }.map {
                    PortableSession(it.id, it.title.orEmpty().ifBlank { it.id }, statuses[it.id], it.time.updated)
                })
        }.sortedByDescending { it.sessions.firstOrNull()?.updated ?: 0L }
    }

    private fun normalize(path: String): String = path.replace('\\', '/').trimEnd('/')
}
