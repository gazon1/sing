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

    private val project = ProjectId("project-1")
    private val tag = TagId("tag-1")
    private val otherTag = TagId("tag-2")
    private val view = SavedAgendaViewId("view-1")

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

    @Test
    fun agendaGraph_savedAgendaResults_returnsNull() {
        val result =
            fabActionForNav3(
                AppDestination.AgendaGraph(AgendaStartRoute.SavedAgendaResults(view)),
                today,
                navigate,
            )
        assertNull(
            result,
            "a saved view's results screen is not the shell's task list: #1952 and #1958 " +
                "keep it buttonless, and FabActionResolver.kt records why that beat #2057",
        )
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

    // ── Every agenda route is classified ───────────────────────────────────────

    /**
     * What the shell is expected to offer on an agenda route: the editor it
     * opens, with the scope it seeds and the date it pre-fills — or no action.
     */
    private data class ExpectedFab(
        /** The editor route the action opens, or `null` when the route has no action. */
        val start: AppDestination.TasksStartRoute.Create?,
        /** The due date the editor opens with, or `null` when it prefills none. */
        val dueDate: LocalDate?,
    ) {
        companion object {
            /** No action at all. */
            val None = ExpectedFab(start = null, dueDate = null)
        }
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

    /**
     * What each agenda route carries, stated route by route.
     *
     * The `when` is exhaustive over the sealed hierarchy, so a route that is
     * added and not classified here does not compile — the same rule the
     * resolver follows, stated a second time from the test's side.
     */
    private fun expectedFabFor(route: AgendaStartRoute): ExpectedFab =
        when (route) {
            // The tabs and the health views: an unscoped task, no date.
            AgendaStartRoute.Inbox,
            AgendaStartRoute.Upcoming,
            AgendaStartRoute.Blocked,
            AgendaStartRoute.Stagnant,
            AgendaStartRoute.Overdue,
            -> {
                ExpectedFab(AppDestination.TasksStartRoute.Create(), dueDate = null)
            }

            // Today is the one route that knows what day it is.
            AgendaStartRoute.Today -> {
                ExpectedFab(AppDestination.TasksStartRoute.Create(), dueDate = today)
            }

            // A drill-down seeds the task with the scope of the list it came
            // from, so the task it creates belongs to that list.
            is AgendaStartRoute.Project -> {
                ExpectedFab(AppDestination.TasksStartRoute.Create(projectId = route.projectId), dueDate = null)
            }

            is AgendaStartRoute.Tag -> {
                ExpectedFab(AppDestination.TasksStartRoute.Create(tagIds = setOf(route.tagId)), dueDate = null)
            }

            is AgendaStartRoute.Tags -> {
                ExpectedFab(AppDestination.TasksStartRoute.Create(tagIds = route.tagIds), dueDate = null)
            }

            // The saved-view screens are not task lists — the list of views, the
            // editor, the creator, and a view's *results*. The results screen is
            // the contested one: #2057 asks for a button there, #1952 and #1958
            // keep it buttonless. The majority won, and `FabActionResolver`'s
            // saved-view branch records why. This line is the same decision seen
            // from the test side: move one, move both.
            AgendaStartRoute.SavedAgendaList,
            is AgendaStartRoute.SavedAgendaResults,
            is AgendaStartRoute.SavedAgendaEdit,
            AgendaStartRoute.SavedAgendaCreate,
            -> {
                ExpectedFab.None
            }
        }

    /**
     * Whether the route is expected to carry an "Add task" action.
     *
     * The projection of [expectedFabFor], kept under the name the resolver's
     * saved-view branch and the decision that settles it point at: the resolver
     * and this classification are the same decision written twice, so they move
     * together.
     */
    private fun expectedAddTask(route: AgendaStartRoute): Boolean = expectedFabFor(route).start != null

    /**
     * Every agenda route resolves to the action it is classified as, and the
     * failure names the route that was walked.
     */
    @Test
    fun everyAgendaRoute_resolvesToTheClassifiedAction() {
        everyAgendaRoute.forEach { route ->
            val expected = expectedFabFor(route)
            navigatedTo = null
            val result = fabActionForNav3(AppDestination.AgendaGraph(route), today, navigate)
            assertEquals(expectedAddTask(route), result != null, "route $route")
            if (expected.start == null) {
                assertNull(result, "$route must resolve to no action")
                return@forEach
            }
            assertEquals("Add task", result.label(), "$route must offer a way to create a task")
            result.click()
            val nav = assertIs<AppDestination.TasksGraph>(navigatedTo, "$route must open the task editor")
            assertEquals(expected.start, nav.start, "$route must open the editor with the scope it seeds")
            assertEquals(expected.dueDate, nav.initialDueDate, "$route must carry the due date it is given")
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
