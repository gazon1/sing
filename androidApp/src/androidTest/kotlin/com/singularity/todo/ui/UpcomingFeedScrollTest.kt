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
import com.singularity.todo.feature.profile.Profile
import com.singularity.todo.feature.profile.domain.port.ProfileRepository
import com.singularity.todo.testing.InstrumentedTestBase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import java.util.UUID

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
 * The prefix of the profile the fixture lives in.
 *
 * The name of the profile carries a UUID and is checked against the profiles
 * on the device before the seed runs, so the profile the fixture writes into
 * is one the test made: a name that is not in use cannot be a profile that
 * already held somebody's data, and the fixture wipes nothing at all. The
 * profile is empty when the run starts, the test writes its rows into it, and
 * removes it afterwards — the device is left as the test found it.
 */
private const val PROFILE_PREFIX = "UpcomingScrollTest"

/**
 * The Upcoming feed scrolls far enough to reach a task six days ahead.
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
        val profileInUse = activeProfileName()
        // A name that is not in use on this device: the seed switches to the
        // profile it is given when a profile with that name exists, so the
        // name is checked before the seed runs, and the profile the seed
        // creates is the test's own.
        val fixtureName = unusedProfileName()
        var fixture: Profile? = null

        val bodyFailure =
            runCatching {
                seed("profile=${Uri.encode(fixtureName)}")
                // The profile the seed just made, kept by id: the cleanup
                // deletes this exact profile, never a profile found by name.
                fixture = profileByName(fixtureName)
                seed("profile=${Uri.encode(fixtureName)}&task=Upcoming%20filler&due=+1d&count=$fillers")
                seed("profile=${Uri.encode(fixtureName)}&task=Due%20in%20six%20days&due=+6d")

                device.awaitShell()
                device.tapTag(TestTags.navTab("Upcoming"))

                // The feed is up: the first filler row is on screen.
                device.awaitTag(
                    TestTags.taskItem("Upcoming filler-1"),
                    "the first filler row",
                    TAB_TIMEOUT_MS,
                )

                // The row that is six days out is below the fold: the feed holds
                // more rows than a phone screen shows.
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
            }.exceptionOrNull()

        // The profile a person was using is the active one again, and the
        // fixture's profile goes with it. Both steps run even when the first
        // one fails: a fixture that stays on the device is not a cleanup.
        val restoreFailure = runCatching { seed("profile=${Uri.encode(profileInUse)}") }.exceptionOrNull()
        val removalFailure = removeProfile(fixture)

        // A cleanup that failed is a failed test — the device was not left as
        // it was found — but it must not hide the failure that brought the
        // test here.
        rethrow(listOfNotNull(bodyFailure, restoreFailure, removalFailure))
    }

    /**
     * Throws the first failure, with the other ones attached to it.
     *
     * @param failures the failures, the one to report first.
     */
    private fun rethrow(failures: List<Throwable>) {
        val first = failures.firstOrNull() ?: return
        failures.drop(1).forEach { first.addSuppressed(it) }
        throw first
    }

    /**
     * The name of the profile the app is working as.
     *
     * Read before the fixture switches the profile, so that the profile can be
     * switched back afterwards.
     */
    private fun activeProfileName(): String =
        withRepository { repository ->
            repository.activeProfile().first().name
        }

    /**
     * A profile name that is not in use on this device.
     *
     * The name carries a UUID, and the repository is asked whether the name is
     * taken before the seed runs — the seed switches to a profile that
     * already exists under the name it is given, so a name that is free is
     * what makes the fixture's profile the test's own.
     */
    private fun unusedProfileName(): String =
        withRepository { repository ->
            var candidate = "$PROFILE_PREFIX ${UUID.randomUUID()}"
            while (repository.findByName(candidate) != null) {
                candidate = "$PROFILE_PREFIX ${UUID.randomUUID()}"
            }
            candidate
        }

    /**
     * The profile with the given name, or null when there is none.
     *
     * @param name the name of the profile.
     */
    private fun profileByName(name: String): Profile? =
        withRepository { repository ->
            repository.findByName(name)
        }

    /**
     * Removes the profile the fixture lived in.
     *
     * The profile holds the test's own rows, and the test created it, so
     * removing it takes the fixture with it and leaves the device as the run
     * found it. The profile is deleted by its id, so a profile that a person
     * had made can never be the one that goes.
     *
     * @param profile the fixture's profile, or null when it was never created.
     * @return the failure, if the removal did not work.
     */
    private fun removeProfile(profile: Profile?): Throwable? {
        if (profile == null) return null
        return runCatching {
            withRepository { repository ->
                repository.delete(profile.id).getOrThrow()
            }
        }.exceptionOrNull()
    }

    /**
     * Runs a block against the profile repository.
     *
     * @param block the work to do with the repository.
     */
    @Suppress("NoRunBlocking") // Instrumented test: the profile repository is a suspend API.
    private fun <T> withRepository(block: suspend (ProfileRepository) -> T): T =
        runBlocking {
            val repository =
                GlobalContext
                    .get()
                    .get<ProfileRepository>()
            block(repository)
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
