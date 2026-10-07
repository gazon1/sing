package com.singularity.todo.core.process

import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The subprocess runner, exercised against **real** child processes.
 *
 * ## Why this test exists and why it is not like the others
 *
 * Every other test of `JvmNotifier` and `JvmReminderScheduler` injects a recording runner
 * in place of the real one. That is correct for asserting argv — it is what lets a test
 * claim an exact argument list — and it is why a genuine defect in the real runner passed
 * a 2461-test suite.
 *
 * The defect: the original implementation closed the child's streams immediately after
 * `start()` instead of discarding them, which delivers SIGPIPE and kills the child with
 * exit code **141**. And these exit codes are capabilities —
 * `notify-send --version == 0` is what makes `Notifier.isSupported` true,
 * `systemd-run --user --version == 0` is what makes `ReminderScheduler.isSupported` true.
 * A 141 turns both into `false`, and `false` means Desktop reminders silently refuse to
 * arm on a machine that is perfectly capable of arming them.
 *
 * So this test does the one thing an injected double cannot: it runs actual commands.
 *
 * @see Subprocess for the full account.
 */
@Tag("fast")
class SubprocessTest {

    @Test
    fun `a command that writes to stdout is not killed for it`() {
        // `echo` is the smallest command that writes and then exits cleanly. The broken
        // implementation returned 141 here often enough to be the common case.
        repeat(RUNS) {
            assertEquals(
                0,
                Subprocess.runQuietly(listOf("sh", "-c", "echo hello")),
                "a child that writes to stdout must not be delivered SIGPIPE by its parent",
            )
        }
    }

    /**
     * The same thing at volume, because the original bug was a race.
     *
     * A single run passes often enough that a test asserting one invocation would be
     * green roughly a third of the time and would eventually go red for reasons nobody
     * could reproduce. The loop is what makes the failure mode visible.
     */
    @Test
    fun `the result is stable across repeated runs, not a race`() {
        val results = (1..RUNS).map { Subprocess.runQuietly(listOf("sh", "-c", "echo x")) }

        assertEquals(
            List(RUNS) { 0 },
            results,
            "every run must report the same thing; a spread means the parent is racing the child",
        )
    }

    @Test
    fun `a non-zero exit is reported, not swallowed`() {
        assertEquals(3, Subprocess.runQuietly(listOf("sh", "-c", "exit 3")))
    }

    /** A missing binary is an answer, not an exception — every caller asks "did it work?". */
    @Test
    fun `a command that does not exist reports -1 rather than throwing`() {
        assertEquals(-1, Subprocess.runQuietly(listOf("no-such-binary-anywhere-xyz")))
    }

    /**
     * The argv list is data, not syntax.
     *
     * A shell string would run this; an argv list passes it through untouched. Every
     * caller feeds user-authored text through here — a task title with a semicolon in it —
     * so this is the property the whole runner exists to hold.
     */
    @Test
    fun `arguments are never interpreted by a shell`() {
        // `sh` itself is the command here, so what is under test is that the *arguments*
        // after `-c` are passed through untouched rather than concatenated into one string.
        // A joined command would let the second half of the hostile argument run.
        val marker = "/tmp/subprocess_argv_marker"
        val hostile = "; touch $marker"
        val exit = Subprocess.runQuietly(
            listOf(
                "sh",
                "-c",
                "test \"\$1\" = safe && touch \"\$0\"",
                "sh",
                hostile,
            ),
        )

        assertTrue(
            exit != 0 || !java.io.File(marker).exists(),
            "an argument must not be able to create a file; the runner joined it into a shell string",
        )
        java.io.File(marker).delete()
    }

    @Test
    fun `an empty argv does not succeed`() {
        assertNotEquals(0, Subprocess.runQuietly(emptyList()))
    }

    private companion object {
        /**
         * High enough that the original race is caught every time.
         *
         * Measured: with the old implementation, one run of five hit 141 four times out of
         * five. At this count a regression cannot pass by luck.
         */
        const val RUNS = 20
    }

    // ─── runCapturing ────────────────────────────────────────────────────────

    @Test
    fun `capturing returns the child's stdout`() {
        val output = Subprocess.runCapturing(listOf("sh", "-c", "echo captured"))

        assertEquals("captured", output?.trim(), "the child's own output must come back")
    }

    /**
     * A non-zero exit yields null, not partial output.
     *
     * The alternative — returning whatever was written before the failure — hands callers
     * a value from a command that did not do what it was asked. For `secret-tool lookup`
     * that is the difference between "no stored secret" and "some error text, stored".
     */
    @Test
    fun `capturing a failing command returns null even when it printed something`() {
        assertEquals(
            null,
            Subprocess.runCapturing(listOf("sh", "-c", "echo partial; exit 1")),
            "output from a failed command is not a usable value",
        )
    }

    @Test
    fun `capturing a missing command returns null`() {
        assertNull(Subprocess.runCapturing(listOf("no-such-binary-anywhere-xyz")))
    }

    /**
     * stdin is delivered, and closed.
     *
     * `secret-tool store` reads the secret from stdin rather than the command line, which
     * is the point: an argument is visible in `ps` to every process on the machine.
     */
    @Test
    fun `stdin reaches the child`() {
        val output = Subprocess.runCapturing(listOf("cat"), stdin = "the-secret-value")

        assertEquals("the-secret-value", output, "a child reading stdin to EOF must not hang or see nothing")
    }

    /**
     * A child that reads stdin to EOF and gets nothing would wait forever.
     *
     * This is the regression the `stdin` parameter's own implementation could regress
     * silently: without the close, `cat` below would hang rather than fail, so the test
     * would time out instead of reporting — which is the shape of bug that survives.
     */
    @Test
    fun `stdin is closed even when the caller passes nothing`() {
        val output = Subprocess.runCapturing(listOf("cat"))

        assertEquals("", output?.trim(), "stdin must be closed, or a reading child waits forever")
    }

    /**
     * A child that writes more than a pipe buffer holds must not deadlock.
     *
     * 64 KiB is the Linux pipe buffer. A caller that `waitFor()`s without draining blocks
     * the moment the child crosses it — the exact shape two of `JvmSecureStorage`'s
     * methods had, invisible today only because `which` writes twenty bytes.
     */
    @Test
    fun `a chatty child does not deadlock the capture`() {
        val output = Subprocess.runCapturing(
            listOf("sh", "-c", "head -c 300000 /dev/zero | tr '\\0' 'x'"),
        )

        assertEquals(300_000, output?.length, "every byte must be drained, not just the first buffer")
    }
}
