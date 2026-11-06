package com.singularity.todo.feature.nav

import com.singularity.todo.feature.projects.domain.model.ProjectId
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pure-Kotlin unit tests for the destination predicate.
 *
 * No Compose, no Android, no JVM-specific APIs — runs in `commonTest`
 * on every platform. Verifies the **contract** (which destinations are
 * tabs/menu/sub-routes), not the implementation.
 */
@Tag("fast")
class DestinationKindTest {
    @Test
    fun isTabRecognisesBottomBarTabs() {
        assertTrue(DestinationKind.isTab(AppDestination.AgendaGraph(AgendaStartRoute.Inbox)))
        assertTrue(DestinationKind.isTab(AppDestination.AgendaGraph(AgendaStartRoute.Today)))
        assertTrue(DestinationKind.isTab(AppDestination.AgendaGraph(AgendaStartRoute.Upcoming)))
        assertTrue(DestinationKind.isTab(AppDestination.Calendar))
    }

    @Test
    fun theCalendarIsNowATab() {
        assertTrue(DestinationKind.isTab(AppDestination.Calendar))
        assertTrue(AppDestination.Calendar in DestinationKind.tabs)
        assertFalse(DestinationKind.isMenuEntry(AppDestination.Calendar))
    }

    @Test
    fun theTabsAreNow() {
        assertEquals(
            listOf(
                AppDestination.AgendaGraph(AgendaStartRoute.Inbox),
                AppDestination.AgendaGraph(AgendaStartRoute.Today),
                AppDestination.AgendaGraph(AgendaStartRoute.Upcoming),
                AppDestination.Calendar,
            ),
            DestinationKind.tabs,
        )
    }

    /**
     * The fifth slot of the bottom bar stays empty.
     *
     * A promotion is meant to fail here, where the failure names the record it has to
     * face, rather than looking like an edit to a list. The argument for it lives in one
     * place: `docs/decisions/2026-11-04-the-fifth-tab-slot-stays-empty.md`.
     */
    @Test
    fun theFifthSlotStaysEmpty() {
        assertEquals(
            4,
            DestinationKind.tabs.size,
            "the bar holds four tabs plus a Menu button — a fifth tab spends its last slot; " +
                "see docs/decisions/2026-11-04-the-fifth-tab-slot-stays-empty.md",
        )
        assertFalse(
            DestinationKind.isTab(AppDestination.Search),
            "Search is an action, not a place; see " +
                "docs/decisions/2026-11-04-the-fifth-tab-slot-stays-empty.md",
        )
        assertTrue(DestinationKind.isMenuEntry(AppDestination.Search))
    }

    @Test
    fun isTabRejectsMenuEntries() {
        // Derived, not enumerated: a hand-written copy of the menu entries falls behind.
        // This one had already lost `Proposals`, `ProfileSwitcher` and `AiUsage`, and it
        // lost nothing when `Plans`/`Pomodoro` moved into the menu either — the test only
        // says a menu entry is not a tab if somebody remembers to add it.
        DestinationKind.menuEntries.forEach { destination ->
            assertFalse(DestinationKind.isTab(destination), "$destination is a menu entry, not a tab")
        }
    }

    @Test
    fun isTabRejectsSubRoutes() {
        assertFalse(DestinationKind.isTab(AppDestination.TasksGraph(AppDestination.TasksStartRoute.Create())))
        assertFalse(DestinationKind.isTab(AppDestination.ProjectEditor()))
    }

    @Test
    fun isMenuEntryRecognisesMenuDestinations() {
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
        assertTrue(DestinationKind.isSubRoute(AppDestination.ProjectDetail(ProjectId("1"))))
    }

    @Test
    fun isSubRouteRejectsTabsAndMenuEntries() {
        assertFalse(DestinationKind.isSubRoute(AppDestination.AgendaGraph(AgendaStartRoute.Today)))
        assertFalse(DestinationKind.isSubRoute(AppDestination.AgendaGraph(AgendaStartRoute.Upcoming)))
        assertFalse(DestinationKind.isSubRoute(AppDestination.Notes))
    }

    @Test
    fun classificationIsMutuallyExclusiveAcrossThreeBuckets() {
        // Derived from the two lists rather than enumerated: the hand-written copy had
        // already fallen behind by `Proposals` and `ProfileSwitcher`.
        val all =
            DestinationKind.tabs +
                DestinationKind.menuEntries +
                listOf(
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
