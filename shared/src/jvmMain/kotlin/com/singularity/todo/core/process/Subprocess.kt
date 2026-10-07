package com.singularity.todo.core.process

/**
 * Runs a subprocess for its exit code alone.
 *
 * ## Why this is one shared function
 *
 * It existed as two copies — one in `JvmNotifier`, one in `JvmReminderScheduler` — and
 * both were wrong in the same way. Anything this file does is a decision about how this
 * process talks to the operating system, and it should not be a decision two files can
 * make differently.
 *
 * ## Why there is no `close()` anywhere in here
 *
 * The first version drained the child by closing its streams immediately after `start()`:
 *
 * ```kotlin
 * val process = ProcessBuilder(argv).redirectErrorStream(true).start()
 * process.inputStream.close()
 * process.errorStream.close()
 * process.waitFor()
 * ```
 *
 * That looks like tidiness and is in fact a race that loses. Closing the read end of a
 * pipe before the child has written to it delivers **SIGPIPE**, and the child dies with
 * exit code **141**. Whether the child writes first is a race it usually loses:
 *
 * ```
 * $ java Probe      # notify-send --version, streams closed right after start()
 * exit=0
 * exit=141
 * exit=141
 * exit=141
 * ```
 *
 * ## Why that was not a cosmetic bug
 *
 * These exit codes are not logged and discarded — they are *capabilities*. `notify-send
 * --version` deciding the host can display notifications, and `systemd-run --user
 * --version` deciding the host can schedule any, are the two facts
 * `JvmReminderScheduler.isSupported` is built from. A 141 turns both into `false`, and
 * `false` means **Desktop reminders refuse to arm at all** — no error, no unit, no
 * notification, and a UI that correctly believes the platform has no scheduler.
 *
 * Every test passed, because every test injected a recording runner in place of this one.
 * The bug lives exclusively in the default that no test reached.
 *
 * ## Why `Redirect.DISCARD` rather than draining
 *
 * Discarding to the null device removes the pipe, so there is nothing left to close and
 * nothing left to race. It is also cheaper than reading megabytes of output nobody wants,
 * and it does not block on a child that writes more than a pipe buffer would hold.
 */
object Subprocess {

    /**
     * Run [argv] and return its exit code, or `-1` if it could not be started.
     *
     * `-1` rather than a throw: every caller is asking "did this work?", and a missing
     * binary is an answer, not an exception.
     *
     * [argv] is a list and is never joined into a shell string. Every caller passes
     * user-authored text through it — a task title, a reminder id — and a shell would give
     * that text a way out of being data.
     */
    fun runQuietly(argv: List<String>): Int = runCatching {
        ProcessBuilder(argv)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
            .waitFor()
    }.getOrDefault(-1)

    /**
     * Run [argv] and return its standard output, or null when it could not be started or
     * exited non-zero.
     *
     * [stdin] is written to the child and then closed, for commands that take input —
     * `secret-tool store` reads the secret from stdin rather than the command line, which
     * is the right choice for a secret and the reason this parameter exists.
     *
     * ## Why this exists rather than callers reading `inputStream` themselves
     *
     * Three reasons, and the first is a latent deadlock.
     *
     * **Draining is mandatory, and only this function does it.** A caller that starts a
     * process and calls `waitFor()` without reading its output deadlocks the moment the
     * child writes more than a pipe buffer holds — 64 KiB on Linux. `JvmSecureStorage` had
     * exactly that shape in two of its four methods: no drain, no close, just `waitFor()`.
     * They work today because `which` and `secret-tool delete` write a few bytes, and they
     * would hang the day that changed. The hang is not the kind anyone debugs quickly,
     * because it appears on a machine where "this always works".
     *
     * **Standard error is folded into standard output.** A caller capturing stdout must
     * also consume stderr, or a chatty child fills the stderr pipe and blocks the same
     * way.
     *
     * **Secrets stay in one place.** `secret-tool lookup` returns a key. Routing that
     * through a shared, tested function means exactly one line in this repository reads a
     * subprocess's output, and it can be reviewed as such.
     *
     * Null covers both failure modes — not started, and started but failed — because
     * every caller wants "no usable value", not the distinction.
     */
    fun runCapturing(argv: List<String>, stdin: String? = null): String? = runCatching {
        val process = ProcessBuilder(argv)
            .redirectErrorStream(true)
            .start()

        // Close stdin whatever happens. A child that reads stdin to EOF and never gets it
        // waits forever, and `waitFor()` below would wait right along with it.
        runCatching {
            process.outputStream.use { out ->
                stdin?.let { out.write(it.toByteArray()) }
            }
        }

        // Read to EOF *before* waiting. Reversing these two is the deadlock above.
        val output = process.inputStream.bufferedReader().readText()
        if (process.waitFor() != 0) null else output
    }.getOrNull()
}
