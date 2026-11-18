package com.singularity.todo.debug

import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.view.ViewGroup
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import com.singularity.todo.feature.profile.domain.port.ProfileRepository
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.port.TaskRepository
import com.singularity.todo.testing.InstrumentedTestBase
import com.singularity.todo.ui.awaitShell
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext

/**
 * Two seed links that reach the debug activity at once.
 *
 * The activity is `singleTask`, so the second link is delivered to the live
 * instance through [DebugSeedActivity.onNewIntent] while the first seed is
 * still running. The seeds have to run one at a time, in the order the links
 * arrived, and the newest link has to own the screen: the older one keeps
 * writing its rows, but it does not bring the app up and does not show its
 * failure.
 *
 * The order is not observable in the data on its own — a link that seeds a row
 * and a link that seeds another row leave both of them there, in whatever
 * order they ran. The `reset=content` scenario is the one that shows the
 * order: a wipe that ran *after* a seed takes the seeded rows away, and a wipe
 * that ran *before* it leaves them. The profile the first link named is the
 * witness that the first link ran at all, so "the rows are gone" cannot be
 * satisfied by "the first link never ran".
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class DebugSeedOrderTest : InstrumentedTestBase() {
    /**
     * The second link owns the screen, and it is seeded once.
     *
     * The first link seeds two hundred rows, so it is still running when the
     * second link arrives — the two links are in the queue together, and the
     * queue is what decides the order. The second link seeds three rows with
     * `count=`: every run of it writes three *new* rows, so a second run of the
     * same link leaves six of them. The resume that follows the new intent
     * must not seed it again.
     */
    @Test
    fun the_second_link_owns_the_screen_and_is_seeded_once() {
        val first = seedIntent("task=A$runId&count=200")
        val second = seedIntent("task=B$runId&count=3")

        val scenario = ActivityScenario.launch<DebugSeedActivity>(first)
        try {
            // The activity is stopped first, so the second link reaches a live
            // instance that is *not* in the foreground: the system brings it
            // back to the front, hands it the new intent (`onNewIntent`) and
            // resumes it (`onResume`). That is the order two links really
            // arrive in, and it is the order that has to hold.
            scenario.moveToState(Lifecycle.State.CREATED)

            // The link is delivered the way a link is delivered: the activity
            // starts it, and `singleTask` hands it to the live instance.
            scenario.onActivity { it.startActivity(second) }

            assertTrue(
                "The seeds did not finish in ${TIMEOUT_MS} ms. The activity says: ${screenText(scenario)}",
                awaitSeedsFinished(scenario),
            )

            val tasks = settledTasks()
            assertEquals(
                "The second link was seeded more than once — the resume that followed the new " +
                    "intent seeded it again.",
                3,
                tasks.count { it.title.startsWith("B$runId") },
            )
            val firstRows = settledTasks().filter { it.title.startsWith("A$runId") }
            assertEquals(
                "The first link did not write its rows. Missing: ${missingIndices(firstRows)}",
                200,
                firstRows.size,
            )

            // The screen belongs to the newest link: the app came up.
            device.awaitShell()
        } finally {
            closeQuietly(scenario)
        }
    }

    /**
     * The seeds run in the order the links arrived — the `reset=content` case.
     *
     * The first link names a profile of its own and seeds rows into it; the
     * second link wipes the content of the active profile, which is the profile
     * the first link named. The rows are gone at the end, so the wipe ran after
     * the seed — and the profile is there, so the first link ran at all.
     */
    @Test
    fun the_seeds_run_in_the_order_the_links_arrived() {
        val profile = "P$runId"
        val first = seedIntent("profile=$profile&task=A$runId&count=200")
        val second = seedIntent("reset=content")

        val scenario = ActivityScenario.launch<DebugSeedActivity>(first)
        try {
            scenario.onActivity { it.startActivity(second) }

            assertTrue(
                "The seeds did not finish in ${TIMEOUT_MS} ms. The activity says: ${screenText(scenario)}",
                awaitSeedsFinished(scenario),
            )

            assertNotNull(
                "The first link never ran: it named the profile '$profile' and the profile is not there.",
                profileNamed(profile),
            )
            assertEquals(
                "The reset ran before the seed, so the rows of the first link survived it.",
                0,
                settledTasks().count { it.title.startsWith("A$runId") },
            )

            // The screen belongs to the newest link: the app came up.
            device.awaitShell()
        } finally {
            closeQuietly(scenario)
        }
    }

    /**
     * Closes the scenario, tolerating the harness's own bookkeeping failure.
     *
     * A successful seed brings the app up with `CLEAR_TASK`: the task is
     * cleared and the activity is destroyed — but the scenario ignores the
     * lifecycle events whose intent does not match the one it launched with,
     * so it waits for a `DESTROYED` it never sees and fails its own teardown.
     * The assertions have already run by then; this is the harness
     * complaining, not the seed.
     */
    @Suppress("TooGenericExceptionCaught") // the harness fails in more ways than one
    private fun closeQuietly(scenario: ActivityScenario<DebugSeedActivity>) {
        try {
            scenario.close()
        } catch (ignored: Throwable) {
            android.util.Log.w(
                "DebugSeedOrderTest",
                "the scenario never saw the activity destroyed; the task was cleared by the seed",
                ignored,
            )
        }
    }

    /** The seed link the activity is started with, or that arrives at it. */
    private fun seedIntent(query: String): Intent =
        Intent(
            ApplicationProvider.getApplicationContext(),
            DebugSeedActivity::class.java,
        ).setData(Uri.parse("todo-debug://seed?$query"))

    /**
     * Whether both seeds are done.
     *
     * The activity brings the app up and finishes when the newest link's seed
     * succeeds, and stays up with the reason on it when a seed fails — so the
     * activity being gone is the sign that the seeds ran and succeeded.
     */
    private fun awaitSeedsFinished(scenario: ActivityScenario<DebugSeedActivity>): Boolean {
        val deadline = SystemClock.uptimeMillis() + TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            var finishing = false
            try {
                scenario.onActivity { finishing = it.isFinishing }
            } catch (ignored: IllegalStateException) {
                // The activity is gone: the seed brought the app up.
                return true
            }
            if (finishing) return true
            SystemClock.sleep(POLL_INTERVAL_MS)
        }
        return false
    }

    /** What the activity shows, for the failure message. */
    private fun screenText(scenario: ActivityScenario<DebugSeedActivity>): String {
        var text = "<nothing>"
        try {
            scenario.onActivity { activity ->
                val content = activity.findViewById<ViewGroup>(android.R.id.content)
                val view = content?.getChildAt(0)
                text = (view as? TextView)?.text?.toString() ?: "<nothing>"
            }
        } catch (ignored: IllegalStateException) {
            return "<the activity is gone>"
        }
        return text
    }

    /** The titles the first link was asked to write, minus the ones that are there. */
    private fun missingIndices(rows: List<Task>): String {
        val present = rows.map { it.title }.toSet()
        return (1..200)
            .map { "A$runId-$it" }
            .filterNot { it in present }
            .toString()
    }

    /** The profile of that name, as the app sees it. */
    @Suppress("NoRunBlocking") // Instrumented test: the profile repository is a suspend API.
    private fun profileNamed(name: String) = runBlocking { profileRepository().findByName(name) }

    /** The tasks of the active profile, as the app sees them. */
    @Suppress("NoRunBlocking") // Instrumented test: the task repository is a suspend API.
    private fun tasks(): List<Task> = runBlocking { taskRepository().observeAll().first() }

    /**
     * The tasks of the active profile, read once they have settled.
     *
     * The first emission of a query flow is the answer as of the moment the
     * collector registered, and the seed writes its rows in a transaction that
     * commits *after* the collector registered: the first answer can therefore
     * be one row short of the truth. Two reads that agree are the truth.
     */
    @Suppress("NoRunBlocking") // Instrumented test: the task repository is a suspend API.
    private fun settledTasks(): List<Task> {
        var previous = tasks()
        repeat(SETTLE_ROUNDS) {
            SystemClock.sleep(SETTLE_INTERVAL_MS)
            val current = tasks()
            if (current.size == previous.size) return current
            previous = current
        }
        return previous
    }

    /** The profile the first link named, as the app sees it. */
    private fun profileRepository(): ProfileRepository = GlobalContext.get().get()

    private fun taskRepository(): TaskRepository = GlobalContext.get().get()

    /**
     * The run's marker.
     *
     * The titles carry it, so a run that is repeated on a device that still
     * holds the rows of an earlier run does not read them as its own.
     */
    private val runId: String = System.currentTimeMillis().toString()

    private companion object {
        /** How long the seeds are given to run. */
        const val TIMEOUT_MS = 120_000L

        /** How long to wait between the checks. */
        const val POLL_INTERVAL_MS = 250L

        /** How many times the rows are read again before they are believed. */
        const val SETTLE_ROUNDS = 5

        /** How long to wait between the reads. */
        const val SETTLE_INTERVAL_MS = 300L
    }
}
