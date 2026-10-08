package dev.leonardo.ocbeacon.ui.screens.portable

import dev.leonardo.ocbeacon.data.api.voice.*
import dev.leonardo.ocbeacon.domain.model.*
import org.junit.Assert.*
import org.junit.Test

class PortableSelectionStateTest {
    private val a = PortableProject("server", "/a", "A", listOf(
        PortableSession("a1", "First", SessionStatus.Busy, 20),
        PortableSession("a2", "Second", SessionStatus.Asking, 10)))
    private val b = PortableProject("server", "/b", "B", listOf(PortableSession("b1", "Other", SessionStatus.Idle, 5)))
    private val projects = listOf(a, b)

    @Test fun `project paging wraps both directions and remembers session`() {
        val selected = PortableSelectionState().pageSession(projects, 1)
        val next = selected.pageProject(projects, 1)
        assertEquals(b, next.project(projects))
        assertEquals("a2", next.pageProject(projects, 1).session(projects)?.id)
        assertEquals(b, PortableSelectionState().pageProject(projects, -1).project(projects))
    }

    @Test fun `session paging is confined to selected project and wraps`() {
        val initial = PortableSelectionState()
        assertEquals("a2", initial.pageSession(projects, -1).session(projects)?.id)
        assertEquals("a1", initial.pageSession(projects, 1).pageSession(projects, 1).session(projects)?.id)
        val next = initial.pageProject(projects, 1)
        assertEquals("b1", next.pageSession(projects, 1).session(projects)?.id)
    }

    @Test fun `identity survives reorder and removal falls back safely`() {
        val selected = PortableSelectionState().pageProject(projects, 1)
        assertEquals(b, selected.project(projects.reversed()))
        assertEquals(a, selected.project(listOf(a)))
        assertNull(selected.project(emptyList()))
        assertEquals(selected, selected.pageSession(emptyList(), 1))
    }

    @Test fun `empty project clears session instead of retaining another project`() {
        val empty = b.copy(sessions = emptyList())
        val list = listOf(a, empty)
        val context = PortableSelectionState().pageProject(list, 1).context(list)
        assertEquals(VoiceProject("/b", "B"), context.project)
        assertEquals("", context.session?.id)
    }

    @Test fun `context carries exact root session state view and visual selection`() {
        val selection = VoiceSelection(SelectionKind.OPTION, "ctx-1:2", "Option C")
        val context = PortableSelectionState().context(projects, ViewContextView.COMPARISON, selection)
        assertEquals(VoiceProject("/a", "A"), context.project)
        assertEquals(VoiceSession("a1", "First", VoiceSessionState.RUNNING), context.session)
        assertEquals(ViewContextView.COMPARISON, context.view)
        assertEquals(selection, context.selection)
        assertEquals(VoiceSessionState.WAITING, PortableSelectionState().pageSession(projects, 1).context(projects).session?.state)
        assertEquals(VoiceProject("", ""), PortableSelectionState().context(emptyList()).project)
    }

    @Test fun `previous returns last viewed project and session`() {
        val selected = PortableSelectionState().pageSession(projects, 1).pageProject(projects, 1)
        assertEquals("a2", selected.returnToPrevious(projects).session(projects)?.id)
    }

    @Test fun `catalog maps longest enclosing root not labels and isolates servers`() {
        val snapshot = SupervisorSnapshot(2, 0, 0, listOf(
            SupervisorAttentionItem("1", "Question", "Root", "now", root = "/work/a"),
            SupervisorAttentionItem("2", "Question", "Nested", "now", root = "/work/a/nested")), emptyList())
        val sessions = listOf(
            Session("1", directory = "/work/a/nested/src", time = Session.Time(1, 20)),
            Session("2", directory = "/work/ab", time = Session.Time(1, 30)),
            Session("3", directory = "/work/a", time = Session.Time(1, 40, archived = 41)))
        val catalog = PortableCatalog.build("srv", sessions, mapOf("1" to SessionStatus.Busy), snapshot)
        assertEquals("/work/ab", catalog.first().root)
        assertEquals("1", catalog.first { it.root == "/work/a/nested" }.sessions.single().id)
        assertTrue(catalog.first { it.root == "/work/a" }.sessions.isEmpty())
        assertNotEquals(catalog.first().key, PortableCatalog.build("other", sessions, emptyMap(), snapshot).first().key)
    }

    @Test fun `catalog accepts remote windows separators`() {
        val session = Session("w", directory = "C:\\work\\beacon", time = Session.Time(1, 2))
        assertEquals("beacon", PortableCatalog.build("srv", listOf(session), emptyMap(), null).single().label)
    }
}
