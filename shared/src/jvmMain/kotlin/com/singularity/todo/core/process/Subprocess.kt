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
}
