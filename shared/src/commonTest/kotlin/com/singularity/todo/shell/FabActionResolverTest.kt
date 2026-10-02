package com.singularity.todo.shell

import com.singularity.todo.core.platform.todayInSystemZone
import com.singularity.todo.feature.nav.AgendaStartRoute
import com.singularity.todo.feature.nav.AppDestination
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class FabActionResolverTest {

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
        val result = fabActionForNav3(AppDestination.AgendaGraph(AgendaStartRoute.Inbox), navigate)
        assertEquals("Add task", result.label())
        result.click()
        val nav = navigatedTo as? AppDestination.TasksGraph
        assertIs<AppDestination.TasksGraph>(nav)
        assertEquals(AppDestination.TasksStartRoute.Create(), nav.start)
        assertNull(nav.initialDueDate, "Inbox FAB should not prefill due date")
    }

    @Test
    fun agendaGraph_today_returnsAddTask_withTodayDueDate() {
        val today = todayInSystemZone()
        val result = fabActionForNav3(AppDestination.AgendaGraph(AgendaStartRoute.Today), navigate)
        assertEquals("Add task", result.label())
        result.click()
        val nav = navigatedTo as? AppDestination.TasksGraph
        assertIs<AppDestination.TasksGraph>(nav)
        assertEquals(AppDestination.TasksStartRoute.Create(), nav.start)
        assertEquals(today, nav.initialDueDate, "Today FAB should prefill due date to today")
    }

    @Test
    fun agendaGraph_upcoming_returnsNull() {
        val result = fabActionForNav3(AppDestination.AgendaGraph(AgendaStartRoute.Upcoming), navigate)
        assertNull(result)
    }

    @Test
    fun agendaGraph_savedAgendaList_returnsNull() {
        val result = fabActionForNav3(AppDestination.AgendaGraph(AgendaStartRoute.SavedAgendaList), navigate)
        assertNull(result)
    }

    // ── Modern ProjectsGraph routes (Desktop) ───────────────────────────────────

    @Test
    fun projectsGraph_list_returnsAddProject() {
        val result = fabActionForNav3(AppDestination.ProjectsGraph(AppDestination.ProjectsStartRoute.List), navigate)
        assertEquals("Add project", result.label())
        result.click()
        assertEquals(AppDestination.ProjectsGraph(AppDestination.ProjectsStartRoute.Editor()), navigatedTo)
    }

    @Test
    fun projectsGraph_editor_returnsAddProject() {
        val result = fabActionForNav3(
            AppDestination.ProjectsGraph(AppDestination.ProjectsStartRoute.Editor()),
            navigate,
        )
        assertEquals("Add project", result.label())
    }

    // ── Agenda routes (modern) ────────────────────────────────────────────────

    @Test
    fun plans_returnsAddProject() {
        val result = fabActionForNav3(AppDestination.Plans, navigate)
        assertEquals("Add project", result.label())
    }

    // ── Destinations with no FAB ────────────────────────────────────────────────

    @Test
    fun notes_returnsNull() {
        assertNull(fabActionForNav3(AppDestination.Notes, navigate))
    }

    @Test
    fun pomodoro_returnsNull() {
        assertNull(fabActionForNav3(AppDestination.Pomodoro, navigate))
    }

    @Test
    fun statistics_returnsNull() {
        assertNull(fabActionForNav3(AppDestination.Statistics, navigate))
    }

    @Test
    fun archive_returnsNull() {
        assertNull(fabActionForNav3(AppDestination.Archive, navigate))
    }

    @Test
    fun settings_returnsNull() {
        assertNull(fabActionForNav3(AppDestination.Settings, navigate))
    }
}
