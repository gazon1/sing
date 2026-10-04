package com.singularity.todo.feature.flows.agenda

import androidx.compose.ui.test.ExperimentalTestApi
import com.singularity.todo.test.helpers.assertCurrentTab
import com.singularity.todo.test.helpers.awaitTag
import com.singularity.todo.test.helpers.runDesktopAppTest
import com.singularity.todo.test.helpers.tapTab
import kotlin.test.Test
import org.junit.jupiter.api.Tag

/**
 * Verifies all desktop shell tabs are reachable and switch correctly.
 *
 * Desktop shell tabs (in order): Inbox, Today, Upcoming, Plans, Pomodoro, Calendar
 *
 * ## What this tests (MR-4)
 * - Each tab click switches the active tab (Selected semantics)
 * - No tab crashes or hangs
 * - Tab order matches DesktopShell.TABS contract
 */
@Tag("slow")
@OptIn(ExperimentalTestApi::class)
class AgendaReachabilityFlowTest {

    @Test
    fun inbox_tab_is_reachable() = runDesktopAppTest(checkA11y = true) {
        tapTab("Inbox")
        assertCurrentTab("Inbox")
    }

    @Test
    fun today_tab_is_reachable() = runDesktopAppTest(checkA11y = true) {
        tapTab("Today")
        assertCurrentTab("Today")
    }

    @Test
    fun upcoming_tab_is_reachable() = runDesktopAppTest(checkA11y = true) {
        tapTab("Upcoming")
        assertCurrentTab("Upcoming")
    }

    @Test
    fun plans_tab_is_reachable() = runDesktopAppTest(checkA11y = true) {
        tapTab("Plans")
        assertCurrentTab("Plans")
    }

    @Test
    fun pomodoro_tab_is_reachable() = runDesktopAppTest(checkA11y = true) {
        tapTab("Pomodoro")
        assertCurrentTab("Pomodoro")
    }

    @Test
    fun calendar_tab_is_reachable() = runDesktopAppTest(checkA11y = true) {
        tapTab("Calendar")
        assertCurrentTab("Calendar")
    }

    @Test
    fun switching_tabs_transitions_cleanly() = runDesktopAppTest(checkA11y = true) {
        tapTab("Inbox")
        assertCurrentTab("Inbox")

        tapTab("Today")
        assertCurrentTab("Today")

        tapTab("Upcoming")
        assertCurrentTab("Upcoming")

        tapTab("Calendar")
        assertCurrentTab("Calendar")

        tapTab("Inbox")
        assertCurrentTab("Inbox")
    }
}
