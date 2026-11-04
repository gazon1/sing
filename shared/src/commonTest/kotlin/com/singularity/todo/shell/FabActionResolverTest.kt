package com.singularity.todo.shell

import com.singularity.todo.feature.nav.AgendaStartRoute
import com.singularity.todo.feature.nav.AppDestination
import com.singularity.todo.feature.nav.DestinationKind
import kotlinx.datetime.LocalDate
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Tag("fast")
class FabActionResolverTest {
    /**
     * The date the shell claims today is. The resolver used to read the system clock
     * itself, so this assertion compared the host's date against the host's date and
     * could not fail — it proved the two calls agreed, not that the prefill was right
     * (#91). Now the date is supplied, and a wrong prefill is visible.
     */
    private val today: LocalDate = LocalDate(2026, 9, 16)

    private var navigatedTo: AppDestination? = null
    private val navigate: (AppDestination) -> Unit = { navigatedTo = it }

    private fun FabAction?.label() = this?.label

    private fun FabAction?.click() {
        assertIs<FabAction>(this)
        this.onClick()
    }

    // ── Modern AgendaGraph routes (Desktop) ─────────────────────────────────────

    @Test
    fun agendaGraph_inbox_returnsAddTask() {
        val result = fabActionForNav3(AppDestination.AgendaGraph(AgendaStartRoute.Inbox), today, navigate)
        assertEquals("Add task", result.label())
        result.click()
        val nav = navigatedTo as? AppDestination.TasksGraph
        assertIs<AppDestination.TasksGraph>(nav)
        assertEquals(AppDestination.TasksStartRoute.Create(), nav.start)
        assertNull(nav.initialDueDate, "Inbox FAB should not prefill due date")
    }

    @Test
    fun agendaGraph_today_returnsAddTask_withTodayDueDate() {
        val today = today
        val result = fabActionForNav3(AppDestination.AgendaGraph(AgendaStartRoute.Today), today, navigate)
        assertEquals("Add task", result.label())
        result.click()
        val nav = navigatedTo as? AppDestination.TasksGraph
        assertIs<AppDestination.TasksGraph>(nav)
        assertEquals(AppDestination.TasksStartRoute.Create(), nav.start)
        assertEquals(today, nav.initialDueDate, "Today FAB should prefill due date to today")
    }

    @Test
    fun agendaGraph_upcoming_returnsAddTask() {
        val result = fabActionForNav3(AppDestination.AgendaGraph(AgendaStartRoute.Upcoming), today, navigate)
        assertEquals("Add task", result.label())
        result.click()
        val nav = navigatedTo as? AppDestination.TasksGraph
        assertIs<AppDestination.TasksGraph>(nav)
        assertEquals(AppDestination.TasksStartRoute.Create(), nav.start)
        assertNull(nav.initialDueDate, "Upcoming FAB should not prefill due date")
    }

    @Test
    fun agendaGraph_savedAgendaList_returnsNull() {
        val result = fabActionForNav3(AppDestination.AgendaGraph(AgendaStartRoute.SavedAgendaList), today, navigate)
        assertNull(result)
    }

    // ── Modern ProjectsGraph routes (Desktop) ───────────────────────────────────

    @Test
    fun projectsGraph_list_returnsAddProject() {
        val result =
            fabActionForNav3(
                AppDestination.ProjectsGraph(AppDestination.ProjectsStartRoute.List),
                today,
                navigate,
            )
        assertEquals("Add project", result.label())
        result.click()
        assertEquals(AppDestination.ProjectsGraph(AppDestination.ProjectsStartRoute.Editor()), navigatedTo)
    }

    @Test
    fun projectsGraph_editor_returnsAddProject() {
        val result =
            fabActionForNav3(
                AppDestination.ProjectsGraph(AppDestination.ProjectsStartRoute.Editor()),
                today,
                navigate,
            )
        assertEquals("Add project", result.label())
    }

    // ── Agenda routes (modern) ────────────────────────────────────────────────

    @Test
    fun plans_returnsAddTask() {
        // The plan tab creates the work that is due — a task due today — not a
        // project. The plan is the work ahead, and a project is not a piece of it.
        val result = fabActionForNav3(AppDestination.Plans, today, navigate)
        assertEquals("Add task", result.label())
    }

    // ── The bottom bar is the source of truth (#1959) ───────────────────────────

    /**
     * Tabs the shell deliberately gives no create action to, each with the reason.
     * Every other tab in [DestinationKind.tabs] must resolve to a task-creation
     * action. The Upcoming tab once shipped with no way to add a task because this
     * rule lived only in a hand-written list of routes, so the bar and the resolver
     * could disagree without anything failing.
     */
    private val tabsWithoutAFab: Map<AppDestination, String> =
        mapOf(
            AppDestination.Calendar to "a month grid is not a list of tasks",
        )

    /**
     * Walks [DestinationKind.tabs] rather than a copy of its routes, so a tab added
     * to the bar is covered without editing this test. If that tab lists tasks and
     * the resolver has no branch for it, the compiler's exhaustiveness check on
     * `AgendaStartRoute` refuses the build first — and if the branch returns `null`,
     * this fails.
     */
    @Test
    fun everyAgendaTabInTheBottomBar_returnsATaskCreationAction() {
        val agendaTabs = DestinationKind.tabs.filterIsInstance<AppDestination.AgendaGraph>()
        assertTrue(agendaTabs.isNotEmpty(), "the bottom bar is expected to hold agenda tabs")
        agendaTabs.forEach { tab ->
            navigatedTo = null
            val result = fabActionForNav3(tab, today, navigate)
            assertEquals("Add task", result.label(), "$tab must offer a way to create a task")
            result.click()
            val nav = navigatedTo
            assertIs<AppDestination.TasksGraph>(nav)
            assertEquals(
                AppDestination.TasksStartRoute.Create(),
                nav.start,
                "$tab must open the task editor",
            )
        }
    }

    /**
     * A tab with no create action has to be named above, not omitted. Comparing
     * against the tab list means a new non-agenda tab fails here until someone
     * says whether it lists tasks, and a stale exception fails too.
     */
    @Test
    fun everyNonAgendaTab_isANamedNoActionException() {
        val nonAgendaTabs = DestinationKind.tabs.filterNot { it is AppDestination.AgendaGraph }
        assertEquals(
            tabsWithoutAFab.keys,
            nonAgendaTabs.toSet(),
            "a tab that is not an agenda list must be named in tabsWithoutAFab with its reason",
        )
    }

    @Test
    fun namedNoActionTabs_returnNull() {
        tabsWithoutAFab.forEach { (tab, reason) ->
            assertNull(
                fabActionForNav3(tab, today, navigate),
                "$tab is listed in tabsWithoutAFab ($reason) but resolves to an action — " +
                    "move it out of the exception list instead",
            )
        }
    }

    // ── Destinations with no FAB ────────────────────────────────────────────────

    @Test
    fun notes_returnsNull() {
        assertNull(fabActionForNav3(AppDestination.Notes, today, navigate))
    }

    @Test
    fun pomodoro_returnsNull() {
        assertNull(fabActionForNav3(AppDestination.Pomodoro, today, navigate))
    }

    @Test
    fun statistics_returnsNull() {
        assertNull(fabActionForNav3(AppDestination.Statistics, today, navigate))
    }

    @Test
    fun archive_returnsNull() {
        assertNull(fabActionForNav3(AppDestination.Archive, today, navigate))
    }

    @Test
    fun settings_returnsNull() {
        assertNull(fabActionForNav3(AppDestination.Settings, today, navigate))
    }
}
