package com.singularity.todo.shell

import com.singularity.todo.feature.agenda.SavedAgendaViewId
import com.singularity.todo.feature.nav.AgendaStartRoute
import com.singularity.todo.feature.nav.AppDestination
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.tags.TagId
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Which screens are the root of a primary tab.
 *
 * The Inbox, Today and Calendar roots are where a tab starts. They are not
 * screens in a back stack, so a Back arrow on them promises a step back that
 * the root has no meaning for — the arrow appeared after a tab switch, because
 * the navigation state alone says "there is a previous tab to go back to"
 * (#3093).
 *
 * The decision is typed, so these tests are about routes and not about titles:
 * a destination whose name happens to read "Today" is not the Today root, and
 * the Inbox and Today roots have to be told apart from the drill-downs that
 * live inside the agenda graph — the saved views, the project and tag views —
 * which keep their own Back.
 */
@Tag("fast")
class PrimaryTabRootTest {
    private val project = ProjectId("project-1")
    private val tag = TagId("tag-1")
    private val view = SavedAgendaViewId("view-1")

    // ── The three roots ───────────────────────────────────────────────────────

    @Test
    fun theInboxRoot_isARoot() {
        assertTrue(
            isPrimaryTabRoot(AppDestination.AgendaGraph(AgendaStartRoute.Inbox)),
            "the Inbox root is where its tab starts, so it carries no Back arrow",
        )
    }

    @Test
    fun theTodayRoot_isARoot() {
        assertTrue(
            isPrimaryTabRoot(AppDestination.AgendaGraph(AgendaStartRoute.Today)),
            "the Today root is where its tab starts, so it carries no Back arrow",
        )
    }

    @Test
    fun theCalendarRoot_isARoot() {
        assertTrue(
            isPrimaryTabRoot(AppDestination.Calendar),
            "the Calendar root is where its tab starts, so it carries no Back arrow",
        )
    }

    // ── Everything else keeps the Back it has ─────────────────────────────────

    @Test
    fun anAgendaDrillDown_isNotARoot() {
        assertFalse(
            isPrimaryTabRoot(AppDestination.AgendaGraph(AgendaStartRoute.Project(project))),
            "a project view is a screen inside the agenda, and it keeps its Back",
        )
        assertFalse(
            isPrimaryTabRoot(AppDestination.AgendaGraph(AgendaStartRoute.Tag(tag))),
            "a tag view is a screen inside the agenda, and it keeps its Back",
        )
        assertFalse(
            isPrimaryTabRoot(AppDestination.AgendaGraph(AgendaStartRoute.SavedAgendaResults(view))),
            "a saved view's results are a screen inside the agenda, and they keep their Back",
        )
    }

    @Test
    fun anotherTab_isNotARoot() {
        assertFalse(
            isPrimaryTabRoot(AppDestination.AgendaGraph(AgendaStartRoute.Upcoming)),
            "the issue names the Inbox, Today and Calendar roots; every other destination " +
                "keeps the affordance it has",
        )
    }

    @Test
    fun aMenuDestination_isNotARoot() {
        assertFalse(
            isPrimaryTabRoot(AppDestination.Statistics),
            "a menu destination is not a tab root: it is a screen the menu opened, and it " +
                "keeps the Back that leaves it",
        )
    }

    // ── The route the agenda published decides ────────────────────────────────

    @Test
    fun thePublishedAgendaRoute_decidesTheRoot() {
        val target =
            shellFabTarget(
                current = AppDestination.AgendaGraph(AgendaStartRoute.Today),
                topMostRoute = AppDestination.AgendaGraph(AgendaStartRoute.Today),
                agendaRoute = AgendaStartRoute.SavedAgendaList,
            )

        assertFalse(
            isPrimaryTabRoot(target),
            "the tab the agenda opened with is not the screen on top: the saved-view list " +
                "is a screen inside the agenda and keeps its Back",
        )
    }

    @Test
    fun theAgendaStartRoute_decidesTheRootWhenNoRouteIsPublished() {
        val target =
            shellFabTarget(
                current = AppDestination.AgendaGraph(AgendaStartRoute.Inbox),
                topMostRoute = AppDestination.AgendaGraph(AgendaStartRoute.Inbox),
                agendaRoute = null,
            )

        assertTrue(
            isPrimaryTabRoot(target),
            "with no published route the agenda is on its own start route, and the Inbox " +
                "is a root",
        )
    }
}
