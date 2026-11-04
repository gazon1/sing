package com.singularity.todo.shell

import com.singularity.todo.feature.agenda.SavedAgendaViewId
import com.singularity.todo.feature.nav.AgendaStartRoute
import com.singularity.todo.feature.nav.AppDestination
import com.singularity.todo.feature.nav.DestinationKind
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.tags.TagId
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

<<<<<<< HEAD
=======
    @Test
    fun agendaGraph_savedAgendaResults_returnsAddTask() {
        val result =
            fabActionForNav3(
                AppDestination.AgendaGraph(AgendaStartRoute.SavedAgendaResults(view)),
                today,
                navigate,
            )
        assertEquals(
            "Add task",
            result.label(),
            "a saved view's results screen lists tasks, so it carries the action (#2057)",
        )
        val nav = result.click()
        assertEquals(
            AppDestination.TasksStartRoute.Create(),
            nav.start,
            "and it creates a plain task, with no date prefilled — as on Inbox",
        )
        assertNull(nav.initialDueDate, "a saved view's results prefill no date")
    }

    @Test
    fun agendaGraph_savedAgendaEdit_returnsNull() {
        val result =
            fabActionForNav3(AppDestination.AgendaGraph(AgendaStartRoute.SavedAgendaEdit(view)), today, navigate)
        assertNull(result)
    }

    @Test
    fun agendaGraph_savedAgendaCreate_returnsNull() {
        val result = fabActionForNav3(AppDestination.AgendaGraph(AgendaStartRoute.SavedAgendaCreate), today, navigate)
        assertNull(result)
    }

    // ── The shell's tab set is the rule ────────────────────────────────────────

    /**
     * The tab list, not a hand-copied set of routes. The Upcoming tab once listed
     * tasks with no button, and a per-route test recorded the absence as correct;
     * reading [DestinationKind.tabs] means a tab that loses its create action fails
     * here, and the failure names the route (#1953, #1959).
     */
    @Test
    fun everyAgendaTab_returnsAddTask() {
        val agendaTabs = DestinationKind.tabs.filterIsInstance<AppDestination.AgendaGraph>()
        assertTrue(agendaTabs.isNotEmpty(), "the shell's tab set must contain agenda tabs")
        agendaTabs.forEach { tab ->
            val action =
                assertNotNull(
                    fabActionForNav3(tab, today, navigate),
                    "agenda tab ${tab.start} must offer Add task",
                )
            assertEquals("Add task", action.label, "agenda tab ${tab.start} must offer Add task")
        }
    }

    /**
     * The tabs deliberately expected to resolve to nothing, named rather than left
     * implicit: the Calendar opens a calendar, not a task list (#1959).
     *
     * This walks the whole tab set, not just the agenda tabs, and the name says
     * "tab" rather than "agenda tab" for that reason: the claim is about every
     * tab the bottom bar can show. A tab added later that resolves to nothing
     * fails here until someone names it as an exception, agenda route or not —
     * which is what makes the exceptions explicit instead of silent (#1959).
     * The agenda-tab rule itself is [everyAgendaTab_returnsAddTask]; the two
     * tests ask different questions on purpose.
     *
     * The notes graph's `null` in the resolver's catch-all never reaches this
     * test: `AppDestination.Notes` is a menu entry, not a tab
     * (`DestinationKind.menuEntries`), and it has its own note creation button.
     */
    @Test
    fun theOnlyTabWithNoShellAction_isCalendar() {
        val withoutAction = DestinationKind.tabs.filter { fabActionForNav3(it, today, navigate) == null }
        assertEquals(
            listOf<AppDestination>(AppDestination.Calendar),
            withoutAction,
            "only the Calendar tab may resolve to no shell action",
        )
    }

    // ── Every agenda route is classified ───────────────────────────────────────

    /**
     * Every agenda start route, so the classification below walks all of them.
     * A route the sealed hierarchy gains later reaches [expectedAddTask] first:
     * that `when` has no `else`, so an unclassified route does not compile.
     */
    private val everyAgendaRoute: List<AgendaStartRoute> =
        listOf(
            AgendaStartRoute.Inbox,
            AgendaStartRoute.Today,
            AgendaStartRoute.Upcoming,
            AgendaStartRoute.Project(project),
            AgendaStartRoute.Tag(tag),
            AgendaStartRoute.Tags(setOf(tag, otherTag)),
            AgendaStartRoute.SavedAgendaList,
            AgendaStartRoute.SavedAgendaResults(view),
            AgendaStartRoute.SavedAgendaEdit(view),
            AgendaStartRoute.SavedAgendaCreate,
            AgendaStartRoute.Blocked,
            AgendaStartRoute.Stagnant,
            AgendaStartRoute.Overdue,
        )

    private fun expectedAddTask(route: AgendaStartRoute): Boolean =
        when (route) {
            AgendaStartRoute.Inbox,
            AgendaStartRoute.Today,
            AgendaStartRoute.Upcoming,
            is AgendaStartRoute.Project,
            is AgendaStartRoute.Tag,
            is AgendaStartRoute.Tags,
            // A saved view's results are a list of tasks — the view is a filter over
            // the same agenda — so the screen carries the action, plain and undated
            // (#2057). #1952 and #1958 asked for it buttonless; the newest
            // classification won, and `FabActionResolver`'s branch records why. This
            // line is the same decision seen from the test side: move one, move both.
            is AgendaStartRoute.SavedAgendaResults,
            AgendaStartRoute.Blocked,
            AgendaStartRoute.Stagnant,
            AgendaStartRoute.Overdue,
            -> true

            // The saved-view *management* screens are not task lists.
            AgendaStartRoute.SavedAgendaList,
            is AgendaStartRoute.SavedAgendaEdit,
            AgendaStartRoute.SavedAgendaCreate,
            -> false
        }

    @Test
    fun everyAgendaRoute_isClassified() {
        everyAgendaRoute.forEach { route ->
            val expected = expectedAddTask(route)
            val result = fabActionForNav3(AppDestination.AgendaGraph(route), today, navigate)
            assertEquals(expected, result != null, "route $route: Add task expected = $expected")
            if (result != null) {
                assertEquals("Add task", result.label, "route $route")
            }
        }
    }

>>>>>>> 37fa8c2e (fix: the review's findings)
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

    // ── Every agenda route is classified (#2057) ───────────────────────────────

    /**
     * What the shell is expected to offer on an agenda route.
     */
    private enum class ExpectedFab {
        /** An "Add task" action that opens the editor with today's date pre-filled. */
        AddTaskDueToday,

        /** An "Add task" action that opens the editor with no date pre-filled. */
        AddTaskNoDueDate,

        /** No action at all. */
        None,
    }

    /**
     * Every agenda start route, one instance of each.
     *
     * A route added to the hierarchy is added here too: the expectation is read
     * from an exhaustive `when` over the same sealed type, so a new route breaks
     * the compilation of this file — and of the resolver — until somebody says
     * what it is. That is the point: the Upcoming tab once shipped with no
     * button because nobody said anything about it (#1959).
     */
    private val everyAgendaStartRoute: List<AgendaStartRoute> =
        listOf(
            AgendaStartRoute.Inbox,
            AgendaStartRoute.Today,
            AgendaStartRoute.Upcoming,
            AgendaStartRoute.Project(ProjectId("project-1")),
            AgendaStartRoute.Tag(TagId("tag-1")),
            AgendaStartRoute.Tags(setOf(TagId("tag-1"), TagId("tag-2"))),
            AgendaStartRoute.SavedAgendaList,
            AgendaStartRoute.SavedAgendaResults(SavedAgendaViewId("view-1")),
            AgendaStartRoute.SavedAgendaEdit(SavedAgendaViewId("view-1")),
            AgendaStartRoute.SavedAgendaCreate,
            AgendaStartRoute.Blocked,
            AgendaStartRoute.Stagnant,
            AgendaStartRoute.Overdue,
        )

    /**
     * What each agenda route carries, stated route by route.
     *
     * The `when` is exhaustive over the sealed hierarchy, so a route that is
     * added and not classified here does not compile — the same rule the
     * resolver follows, stated a second time from the test's side.
     */
    private fun expectedFabFor(route: AgendaStartRoute): ExpectedFab =
        when (route) {
            // The tabs and the health views.
            AgendaStartRoute.Inbox,
            AgendaStartRoute.Upcoming,
            AgendaStartRoute.Blocked,
            AgendaStartRoute.Stagnant,
            AgendaStartRoute.Overdue,
            -> ExpectedFab.AddTaskNoDueDate

            // Today is the one route that knows what day it is.
            AgendaStartRoute.Today -> ExpectedFab.AddTaskDueToday

            // A list of tasks is a list of tasks: one project, one tag, a set of
            // tags, and a saved view's results (#2057).
            is AgendaStartRoute.Project,
            is AgendaStartRoute.Tag,
            is AgendaStartRoute.Tags,
            is AgendaStartRoute.SavedAgendaResults,
            -> ExpectedFab.AddTaskNoDueDate

            // The screens that manage saved views are not task lists.
            AgendaStartRoute.SavedAgendaList,
            AgendaStartRoute.SavedAgendaCreate,
            -> ExpectedFab.None

            is AgendaStartRoute.SavedAgendaEdit -> ExpectedFab.None
        }

    /**
     * Asserts that the route offers the "Add task" action and that tapping it
     * opens the task editor with the due date the route is supposed to carry.
     */
    private fun assertAddTaskAction(
        route: AgendaStartRoute,
        dueDate: LocalDate?,
    ) {
        navigatedTo = null
        val result = fabActionForNav3(AppDestination.AgendaGraph(route), today, navigate)
        assertEquals("Add task", result.label(), "$route must offer a way to create a task")
        result.click()
        val nav = assertIs<AppDestination.TasksGraph>(navigatedTo, "$route must open the task editor")
        assertEquals(AppDestination.TasksStartRoute.Create(), nav.start, "$route must open the task editor")
        assertEquals(dueDate, nav.initialDueDate, "$route must carry the due date it is given")
    }

    /**
     * Every agenda route resolves to the action it is classified as, and the
     * failure names the route that was walked.
     */
    @Test
    fun everyAgendaStartRoute_resolvesToTheClassifiedAction() {
        everyAgendaStartRoute.forEach { route ->
            when (expectedFabFor(route)) {
                ExpectedFab.None -> {
                    assertNull(
                        fabActionForNav3(AppDestination.AgendaGraph(route), today, navigate),
                        "$route must resolve to no action",
                    )
                }

                ExpectedFab.AddTaskDueToday -> {
                    assertAddTaskAction(route, today)
                }

                ExpectedFab.AddTaskNoDueDate -> {
                    assertAddTaskAction(route, null)
                }
            }
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
