package dev.leonardo.ocbeacon.ui.screens.sessions

import dev.leonardo.ocbeacon.domain.model.Session
import dev.leonardo.ocbeacon.domain.model.SessionStatus
import dev.leonardo.ocbeacon.domain.repository.DraftRepository
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionListSubagentStatusTest {

    private val draftRepo = mockk<DraftRepository>(relaxed = true)

    private fun session(id: String, parentId: String? = null) = Session(
        id = id,
        directory = "/proj",
        time = Session.Time(created = 1000, updated = 2000),
        parentId = parentId
    )

    private fun data(
        sessions: List<Session>,
        statuses: Map<String, SessionStatus>,
    ) = SessionListDataInputs(
        sessions = sessions,
        statuses = statuses,
        serverSessionMap = mapOf("server_1" to sessions.map { it.id }.toSet()),
        lastUserMessageTime = emptyMap(),
        tagAssignments = emptyMap(),
        sessionTags = emptyList(),
        favoritesOnly = false,
        lastReplyTime = emptyMap(),
        readTimes = emptyMap(),
        allReadAt = 0L,
        pendingQuestionIds = emptySet()
    )

    private fun ui() = SessionListUiInputs(
        expandedPaths = emptySet(),
        selectedIds = emptySet(),
        baseDirectory = null,
        lastToggledDirectory = null,
        searchQuery = null,
        viewMode = SessionViewMode.RECENT,
        categoryFilterIds = emptySet()
    )

    private fun nodeFor(state: SessionListContentState, id: String) =
        state.treeNodes.filterIsInstance<dev.leonardo.ocbeacon.ui.screens.sessions.components.TreeNode.Session>()
            .first { it.id == id }

    @Test
    fun `parent with busy subagent child shows busy`() = runTest {
        val state = buildContentState(
            data(
                sessions = listOf(session("p1"), session("c1", parentId = "p1")),
                statuses = mapOf("p1" to SessionStatus.Idle, "c1" to SessionStatus.Busy)
            ),
            ui(), "server_1", draftRepo
        )
        assertTrue(nodeFor(state, "p1").session.status is SessionStatus.Busy)
    }

    @Test
    fun `parent with busy grandchild shows busy`() = runTest {
        val state = buildContentState(
            data(
                sessions = listOf(
                    session("p1"),
                    session("c1", parentId = "p1"),
                    session("g1", parentId = "c1")
                ),
                statuses = mapOf(
                    "p1" to SessionStatus.Idle,
                    "c1" to SessionStatus.Idle,
                    "g1" to SessionStatus.Busy
                )
            ),
            ui(), "server_1", draftRepo
        )
        assertTrue(nodeFor(state, "p1").session.status is SessionStatus.Busy)
    }

    @Test
    fun `idle subagent child leaves parent idle`() = runTest {
        val state = buildContentState(
            data(
                sessions = listOf(session("p1"), session("c1", parentId = "p1")),
                statuses = mapOf("p1" to SessionStatus.Idle, "c1" to SessionStatus.Idle)
            ),
            ui(), "server_1", draftRepo
        )
        assertTrue(nodeFor(state, "p1").session.status is SessionStatus.Idle)
    }

    @Test
    fun `asking parent stays asking despite busy child`() = runTest {
        val state = buildContentState(
            data(
                sessions = listOf(session("p1"), session("c1", parentId = "p1")),
                statuses = mapOf("p1" to SessionStatus.Idle, "c1" to SessionStatus.Busy)
            ).let { it.copy(pendingQuestionIds = setOf("p1")) },
            ui(), "server_1", draftRepo
        )
        assertTrue(nodeFor(state, "p1").session.status is SessionStatus.Asking)
    }
}
