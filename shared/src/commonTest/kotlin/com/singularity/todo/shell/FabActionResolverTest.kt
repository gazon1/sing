package com.singularity.todo.shell

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
        assertEquals(AppDestination.TasksGraph(AppDestination.TasksStartRoute.Create), navigatedTo)
    }

    @Test
    fun agendaGraph_today_returnsAddTask() {
        val result = fabActionForNav3(AppDestination.AgendaGraph(AgendaStartRoute.Today), navigate)
        assertEquals("Add task", result.label())
        result.click()
        assertEquals(AppDestination.TasksGraph(AppDestination.TasksStartRoute.Create), navigatedTo)
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
        val result = fabActionForNav3(AppDestination.ProjectsGraph(AppDestination.ProjectsStartRoute.Editor()), navigate)
        assertEquals("Add project", result.label())
    }

    // ── Deprecated singletons (Android shell) ────────────────────────────────────

    @Suppress("DEPRECATION")
    @Test
    fun inboxDeprecated_returnsAddTask() {
        val result = fabActionForNav3(AppDestination.Inbox, navigate)
        assertEquals("Add task", result.label())
        result.click()
        assertEquals(AppDestination.TasksGraph(AppDestination.TasksStartRoute.Create), navigatedTo)
    }

    @Suppress("DEPRECATION")
    @Test
    fun todayDeprecated_returnsAddTask() {
        val result = fabActionForNav3(AppDestination.Today, navigate)
        assertEquals("Add task", result.label())
    }

    @Suppress("DEPRECATION")
    @Test
    fun plansDeprecated_returnsAddProject() {
        val result = fabActionForNav3(AppDestination.Plans, navigate)
        assertEquals("Add project", result.label())
    }

    // ── Destinations with no FAB ────────────────────────────────────────────────

    @Suppress("DEPRECATION")
    @Test
    fun notes_returnsNull() {
        assertNull(fabActionForNav3(AppDestination.Notes, navigate))
    }

    @Suppress("DEPRECATION")
    @Test
    fun pomodoro_returnsNull() {
        assertNull(fabActionForNav3(AppDestination.Pomodoro, navigate))
    }

    @Suppress("DEPRECATION")
    @Test
    fun statistics_returnsNull() {
        assertNull(fabActionForNav3(AppDestination.Statistics, navigate))
    }

    @Suppress("DEPRECATION")
    @Test
    fun archive_returnsNull() {
        assertNull(fabActionForNav3(AppDestination.Archive, navigate))
    }

    @Suppress("DEPRECATION")
    @Test
    fun settings_returnsNull() {
        assertNull(fabActionForNav3(AppDestination.Settings, navigate))
    }
}
