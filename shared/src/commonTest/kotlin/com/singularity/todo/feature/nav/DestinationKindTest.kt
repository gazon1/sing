package com.singularity.todo.feature.nav

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pure-Kotlin unit tests for the destination predicate.
 *
 * No Compose, no Android, no JVM-specific APIs — runs in `commonTest`
 * on every platform. Verifies the **contract** (which destinations are
 * tabs/menu/sub-routes), not the implementation.
 */
class DestinationKindTest {

    @Test
    fun isTabRecognisesAllSixBottomBarTabs() {
        assertTrue(DestinationKind.isTab(AppDestination.AgendaGraph(AgendaStartRoute.Inbox)))
        assertTrue(DestinationKind.isTab(AppDestination.AgendaGraph(AgendaStartRoute.Today)))
        assertTrue(DestinationKind.isTab(AppDestination.AgendaGraph(AgendaStartRoute.Upcoming)))
        assertTrue(DestinationKind.isTab(AppDestination.Plans))
        assertTrue(DestinationKind.isTab(AppDestination.Pomodoro))
        assertTrue(DestinationKind.isTab(AppDestination.Calendar))
    }

    @Test
    fun isTabRejectsMenuEntries() {
        assertFalse(DestinationKind.isTab(AppDestination.Notes))
        assertFalse(DestinationKind.isTab(AppDestination.AiChat))
        assertFalse(DestinationKind.isTab(AppDestination.Search))
        assertFalse(DestinationKind.isTab(AppDestination.Archive))
        assertFalse(DestinationKind.isTab(AppDestination.Settings))
        assertFalse(DestinationKind.isTab(AppDestination.Statistics))
    }

    @Test
    fun isTabRejectsSubRoutes() {
        assertFalse(DestinationKind.isTab(AppDestination.TasksGraph(AppDestination.TasksStartRoute.Create())))
        assertFalse(DestinationKind.isTab(AppDestination.ProjectEditor()))
    }

    @Test
    fun isMenuEntryRecognisesSixSheetDestinations() {
        assertTrue(DestinationKind.isMenuEntry(AppDestination.Statistics))
        assertTrue(DestinationKind.isMenuEntry(AppDestination.Notes))
        assertTrue(DestinationKind.isMenuEntry(AppDestination.AiChat))
        assertTrue(DestinationKind.isMenuEntry(AppDestination.Search))
        assertTrue(DestinationKind.isMenuEntry(AppDestination.Archive))
        assertTrue(DestinationKind.isMenuEntry(AppDestination.Settings))
    }

    @Test
    fun isMenuEntryRejectsTabsAndSubRoutes() {
        assertFalse(DestinationKind.isMenuEntry(AppDestination.AgendaGraph(AgendaStartRoute.Today)))
        assertFalse(DestinationKind.isMenuEntry(AppDestination.AgendaGraph(AgendaStartRoute.Upcoming)))
        assertFalse(DestinationKind.isMenuEntry(AppDestination.TasksGraph(AppDestination.TasksStartRoute.Create())))
    }

    @Test
    fun isSubRouteOnlyMatchesSubRoutes() {
        assertTrue(DestinationKind.isSubRoute(AppDestination.TasksGraph(AppDestination.TasksStartRoute.Create())))
        assertTrue(DestinationKind.isSubRoute(AppDestination.ProjectEditor()))
        assertTrue(DestinationKind.isSubRoute(AppDestination.ProjectDetail("1")))
    }

    @Test
    fun isSubRouteRejectsTabsAndMenuEntries() {
        assertFalse(DestinationKind.isSubRoute(AppDestination.AgendaGraph(AgendaStartRoute.Today)))
        assertFalse(DestinationKind.isSubRoute(AppDestination.AgendaGraph(AgendaStartRoute.Upcoming)))
        assertFalse(DestinationKind.isSubRoute(AppDestination.Notes))
    }

    @Test
    fun classificationIsMutuallyExclusiveAcrossThreeBuckets() {
        val all = listOf(
            AppDestination.AgendaGraph(AgendaStartRoute.Inbox),
            AppDestination.AgendaGraph(AgendaStartRoute.Today),
            AppDestination.AgendaGraph(AgendaStartRoute.Upcoming),
            AppDestination.Plans,
            AppDestination.Pomodoro,
            AppDestination.Statistics,
            AppDestination.Notes,
            AppDestination.AiChat,
            AppDestination.Search,
            AppDestination.Archive,
            AppDestination.Settings,
            AppDestination.TasksGraph(AppDestination.TasksStartRoute.Create()),
            AppDestination.ProjectEditor(),
        )
        all.forEach { dest ->
            val tab = DestinationKind.isTab(dest)
            val menu = DestinationKind.isMenuEntry(dest)
            val sub = DestinationKind.isSubRoute(dest)
            // Exactly one bucket matches.
            assertFalse(tab && menu, "$dest matches both tab and menu")
            assertFalse(tab && sub, "$dest matches both tab and sub")
            assertFalse(menu && sub, "$dest matches both menu and sub")
        }
    }
}
