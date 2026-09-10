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
    fun isTabRecognisesAllFiveBottomBarTabs() {
        assertTrue(DestinationKind.isTab(AppDestination.Inbox))
        assertTrue(DestinationKind.isTab(AppDestination.Today))
        assertTrue(DestinationKind.isTab(AppDestination.Plans))
        assertTrue(DestinationKind.isTab(AppDestination.Habits))
        assertTrue(DestinationKind.isTab(AppDestination.Calendar))
    }

    @Test
    fun isTabRejectsMenuEntries() {
        assertFalse(DestinationKind.isTab(AppDestination.Notes))
        assertFalse(DestinationKind.isTab(AppDestination.AiChat))
        assertFalse(DestinationKind.isTab(AppDestination.Search))
        assertFalse(DestinationKind.isTab(AppDestination.Archive))
        assertFalse(DestinationKind.isTab(AppDestination.Settings))
    }

    @Test
    fun isTabRejectsSubRoutes() {
        assertFalse(DestinationKind.isTab(AppDestination.TaskDetail("42")))
        assertFalse(DestinationKind.isTab(AppDestination.TaskEditor()))
        assertFalse(DestinationKind.isTab(AppDestination.NoteView("n1")))
    }

    @Test
    fun isMenuEntryRecognisesFiveSheetDestinations() {
        assertTrue(DestinationKind.isMenuEntry(AppDestination.Notes))
        assertTrue(DestinationKind.isMenuEntry(AppDestination.AiChat))
        assertTrue(DestinationKind.isMenuEntry(AppDestination.Search))
        assertTrue(DestinationKind.isMenuEntry(AppDestination.Archive))
        assertTrue(DestinationKind.isMenuEntry(AppDestination.Settings))
    }

    @Test
    fun isMenuEntryRejectsTabsAndSubRoutes() {
        assertFalse(DestinationKind.isMenuEntry(AppDestination.Today))
        assertFalse(DestinationKind.isMenuEntry(AppDestination.TaskEditor()))
    }

    @Test
    fun isSubRouteOnlyMatchesSubRoutes() {
        assertTrue(DestinationKind.isSubRoute(AppDestination.TaskDetail("1")))
        assertTrue(DestinationKind.isSubRoute(AppDestination.TaskEditor()))
        assertTrue(DestinationKind.isSubRoute(AppDestination.NoteView("n")))
        assertTrue(DestinationKind.isSubRoute(AppDestination.NoteEditor()))
        assertTrue(DestinationKind.isSubRoute(AppDestination.ProjectEditor()))
    }

    @Test
    fun isSubRouteRejectsTabsAndMenuEntries() {
        assertFalse(DestinationKind.isSubRoute(AppDestination.Today))
        assertFalse(DestinationKind.isSubRoute(AppDestination.Notes))
    }

    @Test
    fun classificationIsMutuallyExclusiveAcrossThreeBuckets() {
        val all = listOf(
            AppDestination.Inbox,
            AppDestination.Today,
            AppDestination.Plans,
            AppDestination.Habits,
            AppDestination.Calendar,
            AppDestination.Notes,
            AppDestination.AiChat,
            AppDestination.Search,
            AppDestination.Archive,
            AppDestination.Settings,
            AppDestination.TaskDetail("x"),
            AppDestination.TaskEditor(),
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
