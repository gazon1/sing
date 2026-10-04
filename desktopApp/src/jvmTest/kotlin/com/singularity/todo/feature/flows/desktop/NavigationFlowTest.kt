package com.singularity.todo.feature.flows.desktop

import androidx.compose.ui.test.ExperimentalTestApi
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.test.helpers.DesktopShell
import com.singularity.todo.test.helpers.assertContentDescriptionDisplayed
import com.singularity.todo.test.helpers.assertCurrentTab
import com.singularity.todo.test.helpers.assertTagDisplayed
import com.singularity.todo.test.helpers.assertTextDisplayed
import com.singularity.todo.test.helpers.openDrawer
import com.singularity.todo.test.helpers.runDesktopAppTest
import com.singularity.todo.test.helpers.tapTab
import org.junit.Test

/**
 * Desktop mirror of `Maestro/flows/nav/bottom-nav-tabs.yaml`.
 *
 * The Android flow walks the bottom bar; desktop reaches the same six
 * destinations through the `ModalNavigationDrawer` hamburger instead.
 *
 * Every arrival is asserted on content the destination itself renders — never on
 * the drawer entry, which slides off-screen as soon as it is tapped. A tap that
 * silently no-ops therefore fails instead of passing on a leftover node.
 */
@OptIn(ExperimentalTestApi::class)
class NavigationFlowTest {

    @Test
    fun drawer_exposes_every_destination() = runDesktopAppTest(checkA11y = true) {
        openDrawer()

        DesktopShell.TABS.forEach { label ->
            assertContentDescriptionDisplayed(label)
        }
        DesktopShell.MENU_ENTRIES.forEach { label ->
            assertContentDescriptionDisplayed(label)
        }
    }

    @Test
    fun pomodoro_and_calendar_render_their_own_screens() = runDesktopAppTest(checkA11y = true) {
        // The phase label is tagged, so this proves the Pomodoro VM produced state
        // rather than merely that the drawer entry was clicked.
        tapTab("Pomodoro")
        assertTagDisplayed(TestTags.Pomodoro.PHASE_LABEL)

        // The month grid's weekday columns exist only in the calendar.
        tapTab("Calendar")
        assertTextDisplayed("Mon")
        assertTextDisplayed("Sun")
    }

    @Test
    fun inbox_is_reachable_from_the_default_today_tab() = runDesktopAppTest(checkA11y = true) {
        // Both agendas are empty on a fresh database and the title text is
        // ambiguous, so the drawer's own Selected semantics is what proves the
        // tab actually switched.
        assertCurrentTab("Today")

        tapTab("Inbox")

        assertCurrentTab("Inbox")
    }
}
