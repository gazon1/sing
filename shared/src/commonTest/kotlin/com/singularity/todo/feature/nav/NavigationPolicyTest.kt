package com.singularity.todo.feature.nav

import com.singularity.todo.feature.notes.NoteId
import com.singularity.todo.feature.tasks.domain.model.TaskId
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Action table for [NavigationPolicy.resolve] — the single open decision (REQ-NAV-001/002/004).
 *
 * Pure commonTest: the policy has no Compose, no state, no platform. Prior art for the
 * (from, to) table style: `Nav3StateReselectTest`.
 */
@Tag("fast")
class NavigationPolicyTest {

    private fun resolve(from: AppNavKey, to: AppNavKey) = NavigationPolicy.resolve(from, to)

    // ── Rule 2: top-level destination → SwitchTab ─────────────────────────

    @Test
    fun `bottom-bar tab target resolves to SwitchTab from any context`() {
        assertEquals(
            OpenAction.SwitchTab,
            resolve(AppDestination.Plans, AppDestination.AgendaGraph(AgendaStartRoute.Today)),
        )
        assertEquals(OpenAction.SwitchTab, resolve(AppDestination.NotesGraph(), AppDestination.Pomodoro))
        assertEquals(OpenAction.SwitchTab, resolve(Settings, AppDestination.Calendar))
    }

    @Test
    fun `menu destination target resolves to SwitchTab from any context`() {
        assertEquals(OpenAction.SwitchTab, resolve(AppDestination.Plans, AppDestination.Search))
        assertEquals(
            OpenAction.SwitchTab,
            resolve(AppDestination.TasksGraph(AppDestination.TasksStartRoute.Create()), AppDestination.Settings),
        )
        // A menu entry can also be the target of a cross-feature open — activation wins.
        assertEquals(OpenAction.SwitchTab, resolve(AppDestination.Settings, AppDestination.ProfileSwitcher))
    }

    @Test
    fun `reopening the active top-level destination resolves to SwitchTab`() {
        // SwitchTab → Nav3State.onTabTapped → reselect event; must never push.
        assertEquals(OpenAction.SwitchTab, resolve(AppDestination.Plans, AppDestination.Plans))
        assertEquals(
            OpenAction.SwitchTab,
            resolve(
                AppDestination.AgendaGraph(AgendaStartRoute.Today),
                AppDestination.AgendaGraph(AgendaStartRoute.Today),
            ),
        )
    }

    // ── Rule 3: same family → Push ────────────────────────────────────────

    @Test
    fun `same-family app-level targets resolve to Push`() {
        assertEquals(
            OpenAction.Push,
            resolve(AppDestination.ProjectDetail("p1"), AppDestination.ProjectEditor("p1")),
        )
        assertEquals(
            OpenAction.Push,
            resolve(AppDestination.ProjectEditor(null), AppDestination.ProjectDetail("p2")),
        )
    }

    @Test
    fun `a pushed non-tab start of the current family resolves to Push`() {
        // AgendaGraph(Project) is NOT a tab instance — but it is the Agenda family.
        assertEquals(
            OpenAction.Push,
            resolve(
                AppDestination.AgendaGraph(AgendaStartRoute.Today),
                AppDestination.AgendaGraph(AgendaStartRoute.Project("p1")),
            ),
        )
    }

    // ── Rule 4: cross-family → ExitAndOpen ───────────────────────────────

    @Test
    fun `cross-feature opens resolve to ExitAndOpen`() {
        // Plans list → task (the allow-list path that used to degrade to goBack, REQ-NAV-002).
        assertEquals(
            OpenAction.ExitAndOpen,
            resolve(
                AppDestination.Plans,
                AppDestination.TasksGraph(AppDestination.TasksStartRoute.Detail(TaskId("t1"))),
            ),
        )
        // Task → linked note (the other swallowed path).
        assertEquals(
            OpenAction.ExitAndOpen,
            resolve(
                AppDestination.TasksGraph(AppDestination.TasksStartRoute.Detail(TaskId("t1"))),
                AppDestination.NotesGraph(AppDestination.NotesStartRoute.Preview(NoteId("n1"))),
            ),
        )
        // Calendar → project detail.
        assertEquals(
            OpenAction.ExitAndOpen,
            resolve(AppDestination.Calendar, AppDestination.ProjectDetail("p1")),
        )
    }

    @Test
    fun `inner feature routes as from-classify by their family`() {
        // The stack top can be a lone inner route object; classification still applies.
        assertEquals(
            OpenAction.ExitAndOpen,
            resolve(Search, AppDestination.NotesGraph(AppDestination.NotesStartRoute.Preview(NoteId("n1")))),
        )
        assertEquals(
            OpenAction.Push,
            resolve(AppDestination.ProjectDetail("p1"), AppDestination.ProjectDetail("p1")),
        )
    }

    // ── Rule 1: bare nested start route → descriptive error ───────────────

    @Test
    fun `bare tasks start route as an app-level target fails naming source and target`() {
        val from = AppDestination.Plans
        val to = AppDestination.TasksStartRoute.Create()

        val error = assertFailsWith<IllegalArgumentException> { resolve(from, to) }

        assertTrue(error.message.orEmpty().contains("$to"), "message must name the target: ${error.message}")
        assertTrue(error.message.orEmpty().contains("$from"), "message must name the source: ${error.message}")
    }

    @Test
    fun `every nested start-route family is rejected as an app-level target`() {
        val from = AppDestination.Plans
        listOf<AppNavKey>(
            AppDestination.TasksStartRoute.Create(),
            AppDestination.ProjectsStartRoute.List,
            AppDestination.NotesStartRoute.List,
            AppDestination.CalendarStartRoute.Month("2026-10"),
            AgendaStartRoute.Today,
            // Inner routes and the lone graph-root objects are equally invalid app-level.
            TasksRoute.Create(null),
            NotesRoute.List,
            Settings,
        ).forEach { to ->
            assertFailsWith<IllegalArgumentException>("must reject $to") { resolve(from, to) }
        }
    }

    // ── Production emissions: every app-level open through the policy ─────

    /** One `(from, to)` pair as actually constructed in production code. */
    private data class Emission(val from: AppNavKey, val to: AppNavKey, val expected: OpenAction)

    private val t1 = TaskId("t1")
    private val n1 = NoteId("n1")

    private val productionEmissions = listOf(
        // TasksNavigator: openProject / openNote / openCreateNote (onExitGraph).
        Emission(
            AppDestination.TasksGraph(AppDestination.TasksStartRoute.Detail(t1)),
            AppDestination.ProjectDetail("p1"),
            OpenAction.ExitAndOpen,
        ),
        Emission(
            AppDestination.TasksGraph(AppDestination.TasksStartRoute.Detail(t1)),
            AppDestination.NotesGraph(AppDestination.NotesStartRoute.Preview(n1)),
            OpenAction.ExitAndOpen,
        ),
        Emission(
            AppDestination.TasksGraph(AppDestination.TasksStartRoute.Detail(t1)),
            AppDestination.NotesGraph(AppDestination.NotesStartRoute.EditorForTask(t1)),
            OpenAction.ExitAndOpen,
        ),
        // NotesNavigator: openTask.
        Emission(
            AppDestination.NotesGraph(AppDestination.NotesStartRoute.Preview(n1)),
            AppDestination.TasksGraph(AppDestination.TasksStartRoute.Detail(t1)),
            OpenAction.ExitAndOpen,
        ),
        // ProjectsNavigator: openTasks (project → agenda) / openTask.
        Emission(
            AppDestination.ProjectsGraph(),
            AppDestination.AgendaGraph(AgendaStartRoute.Project("p1")),
            OpenAction.ExitAndOpen,
        ),
        Emission(
            AppDestination.ProjectsGraph(),
            AppDestination.TasksGraph(AppDestination.TasksStartRoute.Detail(t1)),
            OpenAction.ExitAndOpen,
        ),
        // CalendarNavigator: openTask / openCreateTask.
        Emission(
            AppDestination.Calendar,
            AppDestination.TasksGraph(AppDestination.TasksStartRoute.Detail(t1)),
            OpenAction.ExitAndOpen,
        ),
        Emission(
            AppDestination.Calendar,
            AppDestination.TasksGraph(AppDestination.TasksStartRoute.Create()),
            OpenAction.ExitAndOpen,
        ),
        // AgendaNavigator: openTask / openCreateInSection.
        Emission(
            AppDestination.AgendaGraph(AgendaStartRoute.Today),
            AppDestination.TasksGraph(AppDestination.TasksStartRoute.Detail(t1)),
            OpenAction.ExitAndOpen,
        ),
        Emission(
            AppDestination.AgendaGraph(AgendaStartRoute.Today),
            AppDestination.TasksGraph(AppDestination.TasksStartRoute.Create("section-1")),
            OpenAction.ExitAndOpen,
        ),
        // SearchNavigator: openTask / openNote / openProject / openTag.
        Emission(
            AppDestination.Search,
            AppDestination.TasksGraph(AppDestination.TasksStartRoute.Detail(t1)),
            OpenAction.ExitAndOpen,
        ),
        Emission(
            AppDestination.Search,
            AppDestination.NotesGraph(AppDestination.NotesStartRoute.Preview(n1)),
            OpenAction.ExitAndOpen,
        ),
        Emission(AppDestination.Search, AppDestination.ProjectDetail("p1"), OpenAction.ExitAndOpen),
        Emission(
            AppDestination.Search,
            AppDestination.AgendaGraph(AgendaStartRoute.Tag("g1")),
            OpenAction.ExitAndOpen,
        ),
        // SettingsNavigator: openProfileSwitcher — the target is a menu entry,
        // so rule 2 (activation) wins over the same-family Push.
        Emission(Settings, AppDestination.ProfileSwitcher, OpenAction.SwitchTab),
        // FabActionResolver: create task (cross-family) / new project (Plans IS
        // the Projects family — same-family non-tab push).
        Emission(
            AppDestination.Plans,
            AppDestination.TasksGraph(AppDestination.TasksStartRoute.Create()),
            OpenAction.ExitAndOpen,
        ),
        Emission(
            AppDestination.Plans,
            AppDestination.ProjectsGraph(AppDestination.ProjectsStartRoute.Editor()),
            OpenAction.Push,
        ),
        // ArchiveScreen: LocalAppNavigator.navigate → Navigator.open.
        Emission(
            AppDestination.Archive,
            AppDestination.TasksGraph(AppDestination.TasksStartRoute.Detail(t1)),
            OpenAction.ExitAndOpen,
        ),
        // LoginScreen: NavigateToHome — the tab is re-activated, never pushed.
        Emission(
            AppDestination.AgendaGraph(AgendaStartRoute.Inbox),
            AppDestination.AgendaGraph(AgendaStartRoute.Today),
            OpenAction.SwitchTab,
        ),
        // Shell tab bar (Android bottom bar / desktop drawer tabs).
        Emission(AppDestination.Plans, AppDestination.AgendaGraph(AgendaStartRoute.Today), OpenAction.SwitchTab),
        Emission(AppDestination.Plans, AppDestination.Pomodoro, OpenAction.SwitchTab),
        Emission(AppDestination.Plans, AppDestination.Calendar, OpenAction.SwitchTab),
        Emission(AppDestination.Plans, AppDestination.Plans, OpenAction.SwitchTab),
        // Shell menu sheet / drawer entries (AndroidMenuBottomSheet, desktop).
        Emission(AppDestination.Plans, AppDestination.Statistics, OpenAction.SwitchTab),
        Emission(AppDestination.Plans, AppDestination.Notes, OpenAction.SwitchTab),
        Emission(AppDestination.Plans, AppDestination.AiChat, OpenAction.SwitchTab),
        Emission(AppDestination.Plans, AppDestination.Search, OpenAction.SwitchTab),
        Emission(AppDestination.Plans, AppDestination.Archive, OpenAction.SwitchTab),
        Emission(AppDestination.Plans, AppDestination.Settings, OpenAction.SwitchTab),
        // Android deep link: singularity://agenda/{view} → edit saved view.
        // Same family (Agenda), not a tab instance → Push, not ExitAndOpen.
        Emission(
            AppDestination.AgendaGraph(AgendaStartRoute.Today),
            AppDestination.AgendaGraph(AgendaStartRoute.SavedAgendaEdit("v1")),
            OpenAction.Push,
        ),
        // Android deep link: singularity://task/{id} crosses into the tasks graph.
        Emission(
            AppDestination.AgendaGraph(AgendaStartRoute.Today),
            AppDestination.TasksGraph(AppDestination.TasksStartRoute.Detail(t1)),
            OpenAction.ExitAndOpen,
        ),
    )

    @Test
    fun `every production navigation emission resolves to its declared action`() {
        productionEmissions.forEach { (from, to, expected) ->
            assertEquals(expected, resolve(from, to), "emission $from → $to")
        }
    }
}
