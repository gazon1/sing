package com.singularity.todo.feature.flows.desktop

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.test.helpers.DesktopShell
import com.singularity.todo.test.helpers.assertCurrentTab
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
    fun drawer_exposes_every_destination() = runDesktopAppTest {
        openDrawer()

        DesktopShell.TABS.forEach { label ->
            onNodeWithContentDescription(label).assertIsDisplayed()
        }
        DesktopShell.MENU_ENTRIES.forEach { label ->
            onNodeWithContentDescription(label).assertIsDisplayed()
        }
    }

    @Test
    fun pomodoro_and_calendar_render_their_own_screens() = runDesktopAppTest {
        // The phase label is tagged, so this proves the Pomodoro VM produced state
        // rather than merely that the drawer entry was clicked.
        tapTab("Pomodoro")
        onNodeWithTag(TestTags.Pomodoro.PHASE_LABEL).assertIsDisplayed()

        // The month grid's weekday columns exist only in the calendar.
        tapTab("Calendar")
        onNodeWithText("Mon").assertIsDisplayed()
        onNodeWithText("Sun").assertIsDisplayed()
    }

    @Test
    fun inbox_is_reachable_from_the_default_today_tab() = runDesktopAppTest {
        // Both agendas are empty on a fresh database and the title text is
        // ambiguous, so the drawer's own Selected semantics is what proves the
        // tab actually switched.
        assertCurrentTab("Today")

        tapTab("Inbox")

        assertCurrentTab("Inbox")
    }
}
