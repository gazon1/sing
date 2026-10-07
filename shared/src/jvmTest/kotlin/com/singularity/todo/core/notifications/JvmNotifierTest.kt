package com.singularity.todo.core.notifications

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Desktop notifier's two interesting properties: what it does with user text, and
 * what it claims about the host.
 *
 * ## Why the host is injected rather than probed
 *
 * `JvmNotifier` shells out to `notify-send --version` to decide `isSupported`, which is
 * right in production and wrong in a test: it makes the first assertion of this class
 * depend on whether the machine running the build happens to have libnotify installed.
 * The host capability is therefore a constructor parameter, so a test declares the host
 * it means rather than inheriting one. That is the same reasoning that put
 * `userSystemdAvailable` on `JvmReminderScheduler` and that the deleted `NotificationPort`
 * got wrong in the opposite direction, by asserting a constant nobody had measured.
 */
@Tag("fast")
class JvmNotifierTest {

    /** Records the argv it was handed, so the assertion is on the arguments, not the effect. */
    private class RecordingRunner {
        val calls = mutableListOf<List<String>>()

        suspend fun run(argv: List<String>): Int {
            calls += argv
            return 0
        }
    }

    private fun notifier(
        runner: RecordingRunner,
        hostCanDisplay: Boolean = true,
    ) = JvmNotifier(
        scope = CoroutineScope(Dispatchers.Unconfined),
        runCommand = { argv -> runner.run(argv) },
        // Unconfined so the launched body runs inline; with the default `Dispatchers.IO`
        // the assertion below would race the subprocess and pass vacuously.
        ioContext = Dispatchers.Unconfined,
        hostCanDisplay = { hostCanDisplay },
    )

    @Test
    fun `the host decides support, and it is not a constant`() {
        assertTrue(notifier(RecordingRunner(), hostCanDisplay = true).isSupported)
        assertFalse(
            notifier(RecordingRunner(), hostCanDisplay = false).isSupported,
            "a host without notify-send must not claim it can show notifications",
        )
    }

    /**
     * The load-bearing assertion.
     *
     * If this ever became a joined string, a task titled `; rm -rf ~` would execute. The
     * single-element-per-argument shape is what makes that impossible.
     */
    @Test
    fun `title and body stay separate arguments even when they contain shell syntax`() {
        val runner = RecordingRunner()

        notifier(runner).post(
            tag = "reminder:u1:r1",
            title = "Reminder: '; rm -rf ~ ' \" \$(whoami)",
            body = "a body with spaces & ; | and 'quotes'",
        )

        assertEquals(
            listOf(
                "notify-send",
                "--replace=reminder:u1:r1",
                "--app-name=Singularity",
                "Reminder: '; rm -rf ~ ' \" \$(whoami)",
                "a body with spaces & ; | and 'quotes'",
            ),
            runner.calls.single(),
            "every user-authored value must arrive as one argv element, unmodified",
        )
    }

    @Test
    fun `nothing is posted when the host cannot display anything`() {
        val runner = RecordingRunner()

        notifier(runner, hostCanDisplay = false)
            .post(tag = "t", title = "x", body = "y", viewId = null)

        assertTrue(
            runner.calls.isEmpty(),
            "an unsupported platform must not shell out and hope",
        )
    }

    /**
     * `--replace` is what stops a reminder firing twice from stacking two notifications.
     *
     * A catch-up after a reboot can land on top of a real fire; without the replace key
     * the user sees the same reminder twice and has no way to tell which is current.
     */
    @Test
    fun `the tag is passed as a replace key`() {
        val runner = RecordingRunner()

        notifier(runner).post(tag = "reminder:u1:abc", title = "t", body = "b")

        assertTrue(
            runner.calls.single().any { it == "--replace=reminder:u1:abc" },
            "the tag must drive --replace, got ${runner.calls}",
        )
    }
}
