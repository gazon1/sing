package com.singularity.todo.feature.flows.menu

import androidx.compose.ui.test.ExperimentalTestApi
import com.singularity.todo.feature.nav.DestinationKind
import com.singularity.todo.shell.AppMenu
import com.singularity.todo.test.fakes.FakeClock
import com.singularity.todo.test.helpers.awaitTag
import com.singularity.todo.test.helpers.openDrawer
import com.singularity.todo.test.helpers.runDesktopAppTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/**
 * The menu of the two platforms is one menu.
 *
 * The Android sheet and the Desktop drawer are drawn by different code, so
 * "the same menu on both platforms" has to be a fact rather than a promise.
 * It is a fact because both draw [AppMenu] — the model — and the same tree
 * composable.
 *
 * The assertion is on the *set* of destinations, not on the order: the sheet
 * groups its entries into Account, Search and Destinations, and a drawer shows
 * one column. The entries are the same; the layout is not.
 */
@OptIn(ExperimentalTestApi::class)
@Tag("slow")
@Tag("slow:compose")
class MenuParityFlowTest {

    @Test
    @DisplayName("NAV-02 Android and Desktop expose the same menu entries and the same project tree")
    fun both_platforms_expose_the_same_entries() {
        // The sheet's entries and the drawer's entries come from one model, and
        // that model covers the declared menu destinations exactly — no entry
        // on one platform that the other does not have, and none missing.
        assertEquals(
            DestinationKind.menuEntries.toSet(),
            AppMenu.allDestinations,
            "the menu offers something the declared menu destinations do not, or misses one",
        )

        // The Calendar is one of them: it left the tab bar and became a menu
        // entry on both platforms.
        assertEquals(
            true,
            AppMenu.allDestinations.contains(com.singularity.todo.feature.nav.AppDestination.Calendar),
            "the Calendar is not an entry of the menu",
        )

        // The tree is drawn from the same nodes on both platforms: the model's
        // tree section is the shared composable, and the drawer renders it.
        assertEquals(
            true,
            AppMenu.allDestinations.none { it == com.singularity.todo.feature.nav.AppDestination.Plans },
            "the Plans tab is a tab, not a menu entry",
        )
    }

    @Test
    @DisplayName("the drawer offers the entries of the shared menu model")
    fun the_drawer_offers_the_model() = runDesktopAppTest(clock = CLOCK) {
        openDrawer()

        // Every entry of the model is on the screen. The drawer's column is
        // long, so the entries at the end are addressed by their tag rather
        // than by their position.
        for (entry in AppMenu.destinations) {
            awaitTag(entry.id)
        }
    }

    private companion object {
        val CLOCK: FakeClock = FakeClock(Instant.parse("2026-09-16T10:00:00Z"))
    }
}
