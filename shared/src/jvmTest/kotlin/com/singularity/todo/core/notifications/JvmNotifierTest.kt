package com.singularity.todo.core.notifications

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.jupiter.api.Tag
import java.io.File
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
    /**
     * Enough fresh probes that a race cannot pass.
     *
     * Measured on the broken implementation: a single probe answered `false` about four
     * times in five.
     */
    private companion object {
        const val PROBE_RUNS = 12
    }

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
     * The real probe must not wobble between calls.
     *
     * Built with no injection at all. Repeated, because the defect it guards against — a
     * parent that races the child's output instead of waiting for it — is intermittent by
     * nature, and one call would hide that.
     *
     * **What this test does not catch**, verified by sabotaging the runner back to its
     * broken form: when the parent closes the child's streams, this host answers `false`
     * *consistently*, so `distinct().size == 1` passes and this test stays green while the
     * capability is wrong. The systematic failure is caught by
     * `the real probe agrees with the host's lookup of notify-send`, and by
     * `SubprocessTest`.
     * Kept because a flaky answer is its own bug, but it is not the guard for this one.
     */
    @Test
    fun `the real capability probe is stable across repeated fresh instances`() {
        val answers =
            (1..PROBE_RUNS).map {
                JvmNotifier(scope = CoroutineScope(Dispatchers.Unconfined)).isSupported
            }

        assertEquals(
            1,
            answers.distinct().size,
            "the probe answered $answers. A spread means the parent is racing the child's " +
                "output rather than waiting for it, and every false answer here disables " +
                "reminders for the whole machine",
        )
    }

    /**
     * The probe must agree with the host it is talking about.
     *
     * The expectation is computed by **lookup** — is `notify-send` on this host's
     * `PATH`? — while the production probe decides by **version query**: it runs
     * `notify-send --version` and reads the exit code. Two different mechanisms, so
     * their agreement is evidence rather than a restatement of the subprocess helper
     * the probe itself uses.
     *
     * No `assumeTrue` skip: the expected value is computed on every host, so both
     * branches of the assertion (`false == false` or `true == true`) hold whether or
     * not `notify-send` is installed, and the assertion says something everywhere.
     */
    @Test
    fun `the real probe agrees with the host's lookup of notify-send`() {
        val expected = binaryIsOnPath("notify-send")
        val probed = JvmNotifier(scope = CoroutineScope(Dispatchers.Unconfined)).isSupported

        assertEquals(
            expected,
            probed,
            "the probe answered $probed and the lookup answered $expected. The lookup asks " +
                "whether notify-send is on this host's PATH; the probe asks whether " +
                "`notify-send --version` exits 0. A disagreement means the binary is there " +
                "and does not answer, or it answers and is not there.",
        )
    }

    /**
     * The lookup must be able to say "no".
     *
     * A detector that always answers `true` agrees with a probe that always answers
     * `true`, and the agreement above would be worth nothing. So the same lookup is
     * asked about a name no host has.
     */
    @Test
    fun `the lookup is not vacuous - a binary no host has is not found`() {
        assertFalse(
            binaryIsOnPath("notify-send-that-no-host-has"),
            "the lookup found a binary that does not exist, so it cannot be trusted to " +
                "say whether notify-send is on this host",
        )
    }

    /**
     * Whether a binary of that name is on this host's `PATH`, found by lookup.
     *
     * The operating system's own rule: every entry of `PATH`, in order, and the
     * first directory that holds an executable file of that name is the one that
     * would run. An empty entry means the current directory, exactly as the shell
     * reads it — so it is not skipped here either.
     */
    private fun binaryIsOnPath(name: String): Boolean =
        pathEntries().any { directory ->
            File(directory, name).let { it.isFile && it.canExecute() }
        }

    /** This process's `PATH`, split the way the operating system splits it. */
    private fun pathEntries(): List<String> =
        pathVariable()
            ?.split(File.pathSeparator)
            .orEmpty()

    /** The `PATH` this process was given, whatever the platform spells it. */
    private fun pathVariable(): String? =
        System.getenv()
            .entries
            .firstOrNull { it.key.equals("PATH", ignoreCase = true) }
            ?.value

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
