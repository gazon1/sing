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
import kotlin.test.assertNotNull
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

    private fun FabAction?.click(): AppDestination.TasksGraph {
        assertIs<FabAction>(this)
        this.onClick()
        val nav = navigatedTo as? AppDestination.TasksGraph
        assertIs<AppDestination.TasksGraph>(nav)
        return nav
    }

    // ── Modern AgendaGraph routes (Desktop) ─────────────────────────────────────

    @Test
    fun agendaGraph_inbox_returnsAddTask() {
        val result = fabActionForNav3(AppDestination.AgendaGraph(AgendaStartRoute.Inbox), today, navigate)
        assertEquals("Add task", result.label())
        val nav = result.click()
        assertEquals(AppDestination.TasksStartRoute.Create(), nav.start)
        assertNull(nav.initialDueDate, "Inbox FAB should not prefill due date")
    }

    @Test
    fun agendaGraph_today_returnsAddTask_withTodayDueDate() {
        val today = today
        val result = fabActionForNav3(AppDestination.AgendaGraph(AgendaStartRoute.Today), today, navigate)
        assertEquals("Add task", result.label())
        val nav = result.click()
        assertEquals(AppDestination.TasksStartRoute.Create(), nav.start)
        assertEquals(today, nav.initialDueDate, "Today FAB should prefill due date to today")
    }

    @Test
    fun agendaGraph_upcoming_returnsAddTask() {
        val result = fabActionForNav3(AppDestination.AgendaGraph(AgendaStartRoute.Upcoming), today, navigate)
        assertEquals("Add task", result.label())
        val nav = result.click()
        assertEquals(AppDestination.TasksStartRoute.Create(), nav.start)
        assertNull(nav.initialDueDate, "Upcoming FAB should not prefill due date")
    }

    @Test
    fun agendaGraph_project_returnsAddTask_seededWithThatProject() {
        val result = fabActionForNav3(AppDestination.AgendaGraph(AgendaStartRoute.Project(project)), today, navigate)
        assertEquals("Add task", result.label())
        val nav = result.click()
        assertEquals(
            AppDestination.TasksStartRoute.Create(projectId = project),
            nav.start,
            "The project agenda must seed the task with the project whose list was open",
        )
        assertNull(nav.initialDueDate, "a project agenda prefills no date")
    }

    @Test
    fun agendaGraph_tag_returnsAddTask_seededWithThatTag() {
        val result = fabActionForNav3(AppDestination.AgendaGraph(AgendaStartRoute.Tag(tag)), today, navigate)
        assertEquals("Add task", result.label())
        val nav = result.click()
        assertEquals(
            AppDestination.TasksStartRoute.Create(tagIds = setOf(tag)),
            nav.start,
            "The tag agenda must seed the task with the tag whose list was open",
        )
        assertNull(nav.initialDueDate, "a tag agenda prefills no date")
    }

    @Test
    fun agendaGraph_tags_returnsAddTask_seededWithThoseTags() {
        val tags = setOf(tag, otherTag)
        val result = fabActionForNav3(AppDestination.AgendaGraph(AgendaStartRoute.Tags(tags)), today, navigate)
        assertEquals("Add task", result.label())
        val nav = result.click()
        assertEquals(
            AppDestination.TasksStartRoute.Create(tagIds = tags),
            nav.start,
            "The multi-tag agenda must seed the task with the tags whose list was open",
        )
        assertNull(nav.initialDueDate, "a multi-tag agenda prefills no date")
    }

    // ── Saved-view routes: not task lists, so no action ────────────────────────

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
            AgendaStartRoute.Blocked,
            AgendaStartRoute.Stagnant,
            AgendaStartRoute.Overdue,
            -> true

            // #2057 asks for an "Add task" action on the saved-view *results*
            // screen; #1952 and #1958 keep every saved-view screen buttonless.
            // The majority won, and `FabActionResolver`'s saved-view branch
            // records why — this line is the same decision seen from the test
            // side, so the two must be changed together.
            AgendaStartRoute.SavedAgendaList,
            is AgendaStartRoute.SavedAgendaResults,
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
