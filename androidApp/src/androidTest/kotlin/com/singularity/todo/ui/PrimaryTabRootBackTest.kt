package com.singularity.todo.ui

import android.content.Intent
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import com.singularity.todo.MainActivity
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.testing.InstrumentedTestBase
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The primary tab roots draw no Back arrow on Android (#3093).
 *
 * The desktop suite proves the same policy through the desktop shell, but the
 * Android shell is a different shell: it has a system Back, a menu button in the
 * leading slot of its bar and a calendar that publishes its own navigation icon
 * to it. A policy that both shells honour is only proved on the shell that
 * draws its arrow the other way.
 *
 * The arrows are drawn from the navigation state alone, and after a tab switch
 * that state says "there is a previous tab to go back to" — so every root is
 * reached by a tab switch on purpose: the first screen of the app has nothing
 * to pop at all, and a shell that drew an arrow only when it had something to
 * pop would pass there. The switch is what puts a poppable tab behind the root
 * and makes the missing arrow mean something.
 *
 * The state still says it. Nothing here changes what a back *does* — the arrow
 * is gone, the navigation is not — so the test asserts on the arrow and not on
 * the stack.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class PrimaryTabRootBackTest : InstrumentedTestBase() {
    @Before
    fun unlockTheScreen() {
        // A locked screen answers the first tap with itself, and the test would
        // then measure the lock screen instead of the shell.
        device.executeShellCommand("input keyevent KEYCODE_WAKEUP")
        device.executeShellCommand("input keyevent 82") // KEYCODE_MENU = unlock
    }

    /**
     * The Inbox, Today and Calendar roots draw no Back arrow.
     */
    @Test
    fun the_primary_tab_roots_draw_no_back_arrow() {
        val scenario = launchApp()
        try {
            // The tab the app starts on, before anything was switched.
            assertNoBackArrow("the tab the app starts on")

            switchToTab("Inbox")
            assertNoBackArrow("the Inbox root")

            switchToTab("Today")
            assertNoBackArrow("the Today root")

            // The calendar is a tab like the others, and its own month title is
            // what proves the switch arrived rather than the click.
            switchToTab("Calendar")
            device.awaitTag(
                TestTags.CALENDAR_MONTH_TITLE,
                "the calendar's month title",
                TAB_TIMEOUT_MS,
            )
            assertNoBackArrow("the Calendar root")
        } finally {
            scenario.close()
        }
    }

    /**
     * A tab that is not one of the three keeps the Back arrow.
     *
     * The Upcoming tab is a tab like the others and still draws the arrow after
     * a tab switch — the issue names three roots, and the policy is not widened
     * here. It is also what keeps the assertion above honest: the same lookup
     * that finds nothing on a root finds the arrow here, so the test cannot pass
     * because the arrow is never drawn at all.
     */
    @Test
    fun a_tab_that_is_not_a_primary_root_keeps_the_back_arrow() {
        val scenario = launchApp()
        try {
            switchToTab("Inbox")
            switchToTab("Upcoming")

            // `awaitTag` fails the test when the arrow does not arrive, and its
            // message says what was on the screen instead.
            device.awaitTag(
                TestTags.SHELL_BACK_BUTTON,
                "the shell's Back arrow on the Upcoming tab",
                TAB_TIMEOUT_MS,
            )
        } finally {
            scenario.close()
        }
    }

    /**
     * Opens the app and waits for its shell.
     */
    private fun launchApp(): ActivityScenario<MainActivity> {
        val intent =
            Intent(
                ApplicationProvider.getApplicationContext(),
                MainActivity::class.java,
            )
        val scenario = ActivityScenario.launch<MainActivity>(intent)
        device.awaitShell()
        return scenario
    }

    /**
     * Switches to the tab with the given title and waits for the switch to land.
     *
     * The tab item reports itself as the selected one once the switch has
     * arrived, and that is the signal the test waits for: a tap that had not
     * landed yet would be judged against the screen the tap came from.
     */
    private fun switchToTab(title: String) {
        val tag = TestTags.navTab(title)
        device.tapTag(tag)

        val deadline = SystemClock.uptimeMillis() + TAB_TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            val item = device.findObject(By.res(tag))
            if (item != null && item.isSelected) return
            SystemClock.sleep(POLL_MS)
        }
        throw AssertionError(
            "the $title tab was not selected after the tap. " + device.diagnostics(),
        )
    }

    /**
     * Asserts that the shell draws no Back arrow on the screen on top.
     *
     * The arrow has to be *gone*, not merely absent at the moment of the check:
     * the arrow of the screen the user came from is in the tree until the switch
     * has been applied, and an assertion made in that window would measure the
     * transition rather than the root.
     */
    private fun assertNoBackArrow(where: String) {
        val gone = device.wait(Until.gone(By.res(TestTags.SHELL_BACK_BUTTON)), TAB_TIMEOUT_MS)
        assertTrue("the shell must draw no Back arrow on $where", gone)
    }

    private companion object {
        /** How long to wait between lookups. */
        private const val POLL_MS = 250L
    }
}
