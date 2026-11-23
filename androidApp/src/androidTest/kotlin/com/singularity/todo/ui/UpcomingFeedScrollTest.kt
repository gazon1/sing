package com.singularity.todo.ui

import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiObject2
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.debug.DebugSeedActivity
import com.singularity.todo.testing.InstrumentedTestBase
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** How much of the feed one scroll gesture covers. */
private const val SCROLL_PERCENT = 0.6f

/** How long the feed is given to bring the row into view. */
private const val SCROLL_TIMEOUT_MS = 20_000L

/** The pause between two scroll gestures, so the list can settle. */
private const val SCROLL_PAUSE_MS = 250L

/** How long one seed link is given to finish. */
private const val SEED_TIMEOUT_MS = 30_000L

/** How often the seed's own screen is polled. */
private const val SEED_POLL_MS = 250L

/**
 * The Upcoming feed scrolls far enough to reach a task that is weeks ahead.
 *
 * The retired Maestro flow `agenda/09-upcoming-timeline.yaml` read the feed by
 * scrolling to each of its rows, because on a phone the rows are below the
 * fold. That reading was the flow's one claim no other test made: the desktop
 * scenario renders the whole feed in a desktop-sized window, so it never
 * scrolls, and the shared `LazyColumn` it drives is not the same gesture the
 * phone answers.
 *
 * So the guarantee is asserted here, on a device, with a real drag: a task six
 * days out is *below* the fold when the feed opens, and a drag on the feed
 * brings it into view.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class UpcomingFeedScrollTest : InstrumentedTestBase() {
    /**
     * The row the feed has to be scrolled to.
     *
     * The seeded title is the row's tag source: the tag of a task row is its
     * title in slug form.
     */
    private val farRow = TestTags.taskItem("Due in six days")

    /**
     * The rows above it, which put it below the fold.
     *
     * A phone shows about a dozen task rows, so the row that is six days out
     * is off-screen while the feed opens only if the feed carries more rows
     * than a screen holds. The fillers are all due tomorrow, so they share one
     * date header and push the row under it.
     */
    private val fillers = 24

    @Before
    fun setup() {
        // Unlock the lock screen so ActivityScenario can land on the app: a
        // device that is locked keeps the app behind the keyguard, and the
        // seed's "bring the app up" ends on the lock screen instead.
        device.executeShellCommand("input keyevent KEYCODE_WAKEUP")
        device.executeShellCommand("input keyevent 82") // KEYCODE_MENU = unlock
        device.executeShellCommand("input swipe 360 1400 360 400")
    }

    /**
     * The feed scrolls, and a task six days out is reachable by the scroll.
     */
    @Test
    fun the_feed_scrolls_to_a_task_six_days_out() {
        seed("reset=content")
        seed("task=Upcoming%20filler&due=+1d&count=$fillers")
        seed("task=Due%20in%20six%20days&due=+6d")

        device.awaitShell()
        device.tapTag(TestTags.navTab("Upcoming"))

        // The feed is up: the first filler row is on screen.
        device.awaitTag(
            TestTags.taskItem("Upcoming filler-1"),
            "the first filler row",
            TAB_TIMEOUT_MS,
        )

        // The row that is six days out is below the fold: the feed holds more
        // rows than a phone screen shows.
        assertTrue(
            "the row six days out is on screen before the feed was scrolled — " +
                "the fixture does not put it below the fold: ${device.diagnostics()}",
            device.findObject(By.res(farRow)) == null,
        )

        // A drag on the feed brings it into view.
        val row = scrollUntilVisible(farRow)
        assertNotNull(
            "the row six days out was not reachable by scrolling the feed: ${device.diagnostics()}",
            row,
        )
    }

    /**
     * Drags the feed until the row with [tag] is in view.
     *
     * @return the row, or null when the feed did not bring it into view.
     */
    private fun scrollUntilVisible(tag: String): UiObject2? {
        val deadline = SystemClock.uptimeMillis() + SCROLL_TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            val row = device.findObject(By.res(tag))
            if (row != null) return row
            // `By.scrollable` finds the feed as a `UiObject2`, which is the
            // handle that scrolls: `UiDevice.findObject(UiSelector)` returns
            // the legacy `UiObject`, and that one has no scroll by a fraction
            // of the view.
            val feed = device.findObject(By.scrollable(true))
            assertNotNull("the feed is not a scrollable view: ${device.diagnostics()}", feed)
            feed?.scroll(Direction.DOWN, SCROLL_PERCENT)
            SystemClock.sleep(SCROLL_PAUSE_MS)
        }
        return null
    }

    /**
     * Runs one seed link and waits for it to finish.
     *
     * The seed activity brings the app up when the link succeeds, and stays
     * up with the reason on it when it fails — so the activity finishing (or
     * being gone) is the sign that the seed ran.
     */
    private fun seed(query: String) {
        val intent =
            Intent(
                ApplicationProvider.getApplicationContext(),
                DebugSeedActivity::class.java,
            ).setData(Uri.parse("todo-debug://seed?$query"))
        val scenario = ActivityScenario.launch<DebugSeedActivity>(intent)
        try {
            assertTrue("the seed '$query' did not finish", awaitSeedFinished(scenario))
        } finally {
            closeQuietly(scenario)
        }
    }

    /** Whether the seed activity is done. */
    @Suppress("TooGenericExceptionCaught") // the harness fails in more ways than one
    private fun awaitSeedFinished(scenario: ActivityScenario<DebugSeedActivity>): Boolean {
        val deadline = SystemClock.uptimeMillis() + SEED_TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            var finishing = false
            try {
                scenario.onActivity { finishing = it.isFinishing }
            } catch (ignored: RuntimeException) {
                // The activity is gone: the seed brought the app up. The
                // harness says so in more than one way — an illegal state, or
                // a null activity — and both mean the same thing here.
                return true
            }
            if (finishing) return true
            SystemClock.sleep(SEED_POLL_MS)
        }
        return false
    }

    /**
     * Closes the seed scenario, tolerating the harness's own bookkeeping failure.
     *
     * A successful seed brings the app up with `CLEAR_TASK`: the task is
     * cleared and the activity is destroyed — but the scenario ignores the
     * lifecycle events whose intent does not match the one it launched with,
     * so it waits for a `DESTROYED` it never sees and fails its own teardown.
     */
    @Suppress("TooGenericExceptionCaught") // the harness fails in more ways than one
    private fun closeQuietly(scenario: ActivityScenario<DebugSeedActivity>) {
        try {
            scenario.close()
        } catch (ignored: Throwable) {
            android.util.Log.w(
                "UpcomingFeedScrollTest",
                "the scenario never saw the activity destroyed; the task was cleared by the seed",
                ignored,
            )
        }
    }
}
