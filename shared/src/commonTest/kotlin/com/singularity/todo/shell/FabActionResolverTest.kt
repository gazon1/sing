package com.singularity.todo.shell

import kotlinx.datetime.LocalDate
import com.singularity.todo.feature.nav.AgendaStartRoute
import com.singularity.todo.feature.nav.AppDestination
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

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
    fun agendaGraph_upcoming_returnsNull() {
        val result = fabActionForNav3(AppDestination.AgendaGraph(AgendaStartRoute.Upcoming), today, navigate)
        assertNull(result)
    }

    @Test
    fun agendaGraph_savedAgendaList_returnsNull() {
        val result = fabActionForNav3(AppDestination.AgendaGraph(AgendaStartRoute.SavedAgendaList), today, navigate)
        assertNull(result)
    }

    // ── Modern ProjectsGraph routes (Desktop) ───────────────────────────────────

    @Test
    fun projectsGraph_list_returnsAddProject() {
        val result = fabActionForNav3(
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
        val result = fabActionForNav3(
            AppDestination.ProjectsGraph(AppDestination.ProjectsStartRoute.Editor()),
            today,
            navigate,
        )
        assertEquals("Add project", result.label())
    }

    // ── Agenda routes (modern) ────────────────────────────────────────────────

    @Test
    fun plans_returnsAddProject() {
        val result = fabActionForNav3(AppDestination.Plans, today, navigate)
        assertEquals("Add project", result.label())
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
