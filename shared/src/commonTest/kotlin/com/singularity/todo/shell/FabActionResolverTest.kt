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

    // ── The shell's own tab set, and the create action it must keep ─────────────

    /**
     * The bottom-bar tabs that deliberately have no shell create action.
     *
     * A calendar is not a list of tasks, so it has nothing to add. Naming it here
     * instead of filtering it out silently is the point: an exception the test does
     * not name is an exception nobody decided, and a button that quietly disappears
     * is the defect this guard exists for (#1953, #1959).
     */
    private val tabsWithoutCreateAction: List<AppDestination> =
        listOf(AppDestination.Calendar)

    /**
     * Every agenda tab the bottom bar shows must resolve to "Add task".
     *
     * The per-route tests below name one route each, so a tab whose route leaves the
     * create branch takes its expectation with it: the Upcoming tab once had no FAB,
     * and that was written into the suite as the expected result, so the suite stayed
     * green (#1953). This test reads the tab set the shell itself renders from, so the
     * set of tabs and the set of routes that resolve to a create action cannot drift
     * apart in silence — and a tab added to the bar is covered without editing the
     * test.
     */
    @Test
    fun everyAgendaTabInTheShellsTabSet_resolvesToAddTask() {
        val agendaTabs = DestinationKind.tabs.filterIsInstance<AppDestination.AgendaGraph>()
        assertTrue(
            agendaTabs.isNotEmpty(),
            "DestinationKind.tabs holds no agenda tab, so this guard measures nothing",
        )
        // Every tab is accounted for: it either carries "Add task" or is named above
        // as a tab that does not. A tab that is neither is an unstated exception.
        assertEquals(
            DestinationKind.tabs.toSet(),
            agendaTabs.toSet() + tabsWithoutCreateAction.toSet(),
            "Every shell tab must be either an agenda tab, which resolves to \"Add task\", " +
                "or a tab named in tabsWithoutCreateAction. A tab that is neither is an " +
                "exception nobody stated; a tab that left the bar is still named there.",
        )
        agendaTabs.forEach { tab ->
            val result = fabActionForNav3(tab, today, navigate)
            assertEquals(
                "Add task",
                result?.label,
                "Agenda tab \"${tab.title}\" (${tab.start}) is a shell tab, " +
                    "so it must resolve to \"Add task\"; " +
                    "fabActionForNav3 returned ${result?.label ?: "no action"}",
            )
            // The label is not the action: a tab whose button opened something other
            // than the create screen would still read "Add task". The per-route tests
            // pin today's three tabs; this pins whichever tab the bar gains next.
            navigatedTo = null
            result?.click()
            val nav = navigatedTo
            assertIs<AppDestination.TasksGraph>(
                nav,
                "Agenda tab \"${tab.title}\" (${tab.start}) must open the task create screen, " +
                    "but its action navigated to $nav",
            )
            assertIs<AppDestination.TasksStartRoute.Create>(
                nav.start,
                "Agenda tab \"${tab.title}\" (${tab.start}) must open the task create screen, " +
                    "but its action navigated to ${nav.start}",
            )
        }
        tabsWithoutCreateAction.forEach { tab ->
            assertNull(
                fabActionForNav3(tab, today, navigate),
                "Tab \"${tab.title}\" is declared in tabsWithoutCreateAction, so it must " +
                    "not resolve to a create action; fabActionForNav3 returned one",
            )
        }
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
