package com.singularity.todo.shell

import androidx.navigation3.runtime.NavKey
import com.singularity.todo.feature.agenda.SavedAgendaViewId
import com.singularity.todo.feature.nav.AgendaStartRoute
import com.singularity.todo.feature.nav.AppDestination
import com.singularity.todo.feature.projects.domain.model.ProjectId
import kotlinx.datetime.LocalDate
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * Which destination the shell's button belongs to.
 *
 * The shell sits above the agenda's nested graph and cannot see the route inside
 * it: the graph publishes that route, and the shell resolves its button from it.
 * These tests pin what the shell does with the published route — in particular
 * that a route the resolver classifies as buttonless really ends up without a
 * shell button, which is what the saved-view screens need (they draw their own).
 */
@Tag("fast")
class ShellFabTargetTest {
    private val today: LocalDate = LocalDate(2026, 11, 12)
    private val project = ProjectId("project-1")
    private val view = SavedAgendaViewId("view-1")

    private var navigatedTo: AppDestination? = null
    private val navigate: (AppDestination) -> Unit = { navigatedTo = it }

    private fun fabFor(
        current: AppDestination,
        topMostRoute: NavKey,
        agendaRoute: AgendaStartRoute?,
    ): FabAction? = fabActionForNav3(shellFabTarget(current, topMostRoute, agendaRoute), today, navigate)

    // ── The agenda's own routes ─────────────────────────────────────────────────

    @Test
    fun aSavedViewRoute_resolvesToTheSavedViewDestination() {
        val target =
            shellFabTarget(
                current = AppDestination.AgendaGraph(AgendaStartRoute.Today),
                topMostRoute = AppDestination.AgendaGraph(AgendaStartRoute.Today),
                agendaRoute = AgendaStartRoute.SavedAgendaList,
            )
        assertEquals(
            AppDestination.AgendaGraph(AgendaStartRoute.SavedAgendaList),
            target,
            "the shell must ask about the route the agenda graph is on, not the tab it opened with",
        )
    }

    @Test
    fun aSavedViewRoute_carriesNoShellButton() {
        val result =
            fabFor(
                current = AppDestination.AgendaGraph(AgendaStartRoute.Today),
                topMostRoute = AppDestination.AgendaGraph(AgendaStartRoute.Today),
                agendaRoute = AgendaStartRoute.SavedAgendaList,
            )
        assertNull(
            result,
            "the saved-view screens draw their own create button; a shell button over it " +
                "covered that button on Android",
        )
    }

    @Test
    fun aDrillDownRoute_carriesTheButtonOfTheDrillDown() {
        val result =
            fabFor(
                current = AppDestination.AgendaGraph(AgendaStartRoute.Today),
                topMostRoute = AppDestination.AgendaGraph(AgendaStartRoute.Today),
                agendaRoute = AgendaStartRoute.Project(project),
            )
        assertEquals("Add task", result?.label)
        result?.onClick()
        val nav = assertIs<AppDestination.TasksGraph>(navigatedTo)
        assertEquals(
            AppDestination.TasksStartRoute.Create(projectId = project),
            nav.start,
            "a drill-down seeds the new task with the scope it was opened from",
        )
    }

    @Test
    fun theTodaysRoute_stillPrefillsToday() {
        val result =
            fabFor(
                current = AppDestination.AgendaGraph(AgendaStartRoute.Today),
                topMostRoute = AppDestination.AgendaGraph(AgendaStartRoute.Today),
                agendaRoute = AgendaStartRoute.Today,
            )
        assertEquals("Add task", result?.label)
        result?.onClick()
        val nav = assertIs<AppDestination.TasksGraph>(navigatedTo)
        assertEquals(today, nav.initialDueDate)
    }

    @Test
    fun noPublishedRoute_fallsBackToTheGraphsOwnStartRoute() {
        val target =
            shellFabTarget(
                current = AppDestination.AgendaGraph(AgendaStartRoute.Inbox),
                topMostRoute = AppDestination.AgendaGraph(AgendaStartRoute.Inbox),
                agendaRoute = null,
            )
        assertEquals(AppDestination.AgendaGraph(AgendaStartRoute.Inbox), target)
    }

    // ── Routes outside the agenda ──────────────────────────────────────────────

    @Test
    fun anotherDestination_ignoresThePublishedAgendaRoute() {
        val target =
            shellFabTarget(
                current = AppDestination.Archive,
                topMostRoute = AppDestination.Archive,
                agendaRoute = AgendaStartRoute.SavedAgendaList,
            )
        assertEquals(
            AppDestination.Archive,
            target,
            "a route published by an agenda graph that is no longer on screen must not " +
                "reclassify the destination the shell is on",
        )
    }

    @Test
    fun aRouteThatIsNotADestination_fallsBackToTheCurrentOne() {
        val target =
            shellFabTarget(
                current = AppDestination.Archive,
                topMostRoute = AgendaStartRoute.Today,
                agendaRoute = AgendaStartRoute.SavedAgendaList,
            )
        assertEquals(AppDestination.Archive, target)
    }
}
