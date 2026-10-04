package com.singularity.todo.feature.nav

import com.singularity.todo.feature.notes.NoteId
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.tasks.domain.model.TaskId
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Every declared navigation key classifies into exactly one [ScreenFamily] —
 * the structural knowledge [NavigationPolicy] needs to decide same-feature
 * (`Push`) vs cross-feature (`ExitAndOpen`) opens (REQ-NAV-004).
 *
 * [familyOf] is already compile-time exhaustive over the sealed `AppNavKey` root,
 * so this test's job is not "no branch missing" but "each branch produces the
 * *right* family": a mis-classified family silently turns a Push into an
 * ExitAndOpen (or vice versa) with no compiler complaint.
 *
 * The inventory is exhaustive-by-hand, mirroring [NavKeyRegistrationTest]: one
 * representative per declared leaf.
 */
@Tag("fast")
class ScreenFamilyTest {

    private fun assertFamily(expected: ScreenFamily, key: AppNavKey) {
        assertEquals(expected, familyOf(key), "familyOf($key)")
    }

    @Test
    fun `top-level destinations classify by feature`() {
        assertFamily(ScreenFamily.Projects, AppDestination.Plans)
        assertFamily(ScreenFamily.Tasks, AppDestination.TasksByProject(ProjectId("p1")))
        assertFamily(ScreenFamily.Pomodoro, AppDestination.Pomodoro)
        assertFamily(ScreenFamily.Statistics, AppDestination.Statistics)
        assertFamily(ScreenFamily.Calendar, AppDestination.Calendar)
        assertFamily(ScreenFamily.Notes, AppDestination.Notes)
        assertFamily(ScreenFamily.AiChat, AppDestination.AiChat)
        assertFamily(ScreenFamily.Search, AppDestination.Search)
        assertFamily(ScreenFamily.Archive, AppDestination.Archive)
        assertFamily(ScreenFamily.Settings, AppDestination.Settings)
        assertFamily(ScreenFamily.Settings, AppDestination.AiUsage)
        assertFamily(ScreenFamily.Settings, AppDestination.ProfileSwitcher)
    }

    @Test
    fun `graph wrappers classify by their feature`() {
        assertFamily(
            ScreenFamily.Tasks,
            AppDestination.TasksGraph(AppDestination.TasksStartRoute.Detail(TaskId("t1"))),
        )
        assertFamily(ScreenFamily.Projects, AppDestination.ProjectsGraph())
        assertFamily(ScreenFamily.Projects, AppDestination.ProjectDetail("p1"))
        assertFamily(ScreenFamily.Projects, AppDestination.ProjectEditor(null))
        assertFamily(ScreenFamily.Notes, AppDestination.NotesGraph())
        assertFamily(ScreenFamily.Calendar, AppDestination.CalendarGraph())
        assertFamily(ScreenFamily.Agenda, AppDestination.AgendaGraph(AgendaStartRoute.Today))
        assertFamily(ScreenFamily.Agenda, AppDestination.AgendaGraph(AgendaStartRoute.SavedAgendaCreate))
    }

    @Test
    fun `nested-but-AppNavKey start routes classify by their feature`() {
        // Declared inside AppDestination's body yet extending AppNavKey — the
        // shapes `is AppDestination` alone would silently miss.
        assertFamily(ScreenFamily.Tasks, AppDestination.TasksStartRoute.Create())
        assertFamily(ScreenFamily.Tasks, AppDestination.TasksStartRoute.Detail(TaskId("t1")))
        assertFamily(ScreenFamily.Projects, AppDestination.ProjectsStartRoute.List)
        assertFamily(ScreenFamily.Projects, AppDestination.ProjectsStartRoute.Editor(ProjectId("p1")))
        assertFamily(ScreenFamily.Notes, AppDestination.NotesStartRoute.List)
        assertFamily(ScreenFamily.Notes, AppDestination.NotesStartRoute.Preview(NoteId("n1")))
        assertFamily(ScreenFamily.Notes, AppDestination.NotesStartRoute.EditorForTask(TaskId("t1")))
        assertFamily(ScreenFamily.Calendar, AppDestination.CalendarStartRoute.Month("2026-10"))
    }

    @Test
    fun `agenda start routes classify as Agenda`() {
        listOf<AgendaStartRoute>(
            AgendaStartRoute.Inbox,
            AgendaStartRoute.Today,
            AgendaStartRoute.Upcoming,
            AgendaStartRoute.Project("p1"),
            AgendaStartRoute.Tag("g1"),
            AgendaStartRoute.SavedAgendaList,
            AgendaStartRoute.SavedAgendaResults("v1"),
            AgendaStartRoute.SavedAgendaEdit("v1"),
            AgendaStartRoute.SavedAgendaCreate,
        ).forEach { assertFamily(ScreenFamily.Agenda, it) }
    }

    @Test
    fun `inner feature routes classify by their feature`() {
        assertFamily(ScreenFamily.Tasks, TasksRoute.Detail(TaskId("t1")))
        assertFamily(ScreenFamily.Tasks, TasksRoute.Create())
        assertFamily(ScreenFamily.Projects, ProjectsRoute.List)
        assertFamily(ScreenFamily.Projects, ProjectsRoute.Editor(ProjectId("p1")))
        assertFamily(ScreenFamily.Projects, ProjectsRoute.Detail(ProjectId("p1")))
        assertFamily(ScreenFamily.Notes, NotesRoute.List)
        assertFamily(ScreenFamily.Notes, NotesRoute.Preview(NoteId("n1")))
        assertFamily(ScreenFamily.Notes, NotesRoute.Editor())
        assertFamily(ScreenFamily.Calendar, CalendarRoute.Month("2026-10"))
        assertFamily(ScreenFamily.Calendar, CalendarRoute.Day("2026-10-04"))
    }

    @Test
    fun `lone settings and search objects classify`() {
        // Top-level `data object`s outside AppDestination — the pair the
        // navigation model keeps as singletons rather than graph instances.
        assertFamily(ScreenFamily.Settings, Settings)
        assertFamily(ScreenFamily.Search, Search)
    }
}
