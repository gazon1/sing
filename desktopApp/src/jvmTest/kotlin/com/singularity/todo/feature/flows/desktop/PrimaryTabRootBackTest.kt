package com.singularity.todo.feature.flows.desktop

import androidx.compose.ui.test.ExperimentalTestApi
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.test.fakes.FakeClock
import com.singularity.todo.test.helpers.assertCurrentTab
import com.singularity.todo.test.helpers.assertTagNotExists
import com.singularity.todo.test.helpers.awaitTag
import com.singularity.todo.test.helpers.runDesktopAppTest
import com.singularity.todo.test.helpers.tapTab
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.time.Instant

/**
 * The primary tab roots draw no Back arrow (#3093).
 *
 * The Inbox, Today and Calendar roots are where a tab starts — they are not
 * screens in a back stack, so an arrow on them promises a step back that the
 * root has no meaning for. The shell drew one anyway whenever the navigation
 * state said "there is something to pop", and after a tab switch it always
 * says so: the state remembers the tab the user came from.
 *
 * The state still says it. Nothing here changes what a back *does* — the arrow
 * is gone, the navigation is not — so the test asserts on the arrow and not on
 * the stack.
 *
 * Every root is reached by a tab switch on purpose: the first screen of the
 * app has nothing to pop at all, so a shell that drew an arrow only when it
 * had something to pop would pass there. The switch is what puts a poppable
 * tab behind the root and makes the missing arrow mean something.
 */
@OptIn(ExperimentalTestApi::class)
@Tag("slow")
@Tag("slow:compose")
class PrimaryTabRootBackTest {
    @Test
    @DisplayName("#3093 the Inbox, Today and Calendar roots draw no Back arrow")
    fun the_primary_tab_roots_draw_no_back_arrow() =
        runDesktopAppTest(clock = CLOCK, checkA11y = true) {
            // The tab the app starts on, before anything was switched.
            assertTagNotExists(TestTags.SHELL_BACK_BUTTON)

            // A tab switch leaves the previous tab behind, so the navigation
            // state can pop — and the root still draws no arrow.
            tapTab("Inbox")
            assertCurrentTab("Inbox")
            assertTagNotExists(TestTags.SHELL_BACK_BUTTON)

            tapTab("Today")
            assertCurrentTab("Today")
            assertTagNotExists(TestTags.SHELL_BACK_BUTTON)

            // The calendar is a tab like the others, and its own month title is
            // what proves the switch arrived rather than the click.
            tapTab("Calendar")
            awaitTag(TestTags.CALENDAR_MONTH_TITLE)
            assertTagNotExists(TestTags.SHELL_BACK_BUTTON)
        }

    @Test
    @DisplayName("#3093 a destination that is not a tab root keeps its Back arrow")
    fun another_destination_keeps_the_back_arrow() =
        runDesktopAppTest(clock = CLOCK, checkA11y = true) {
            tapTab("Statistics")

            awaitTag(TestTags.SHELL_BACK_BUTTON)
        }

    private companion object {
        val CLOCK: FakeClock = FakeClock(Instant.parse("2026-09-16T10:00:00Z"))
    }
}
