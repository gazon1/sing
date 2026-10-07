package com.singularity.todo.feature.reminders

import com.singularity.todo.core.error.runCatchingCancellable

/**
 * Re-arms every future reminder after the user manager restarts.
 *
 * ## Why this exists at all
 *
 * A `systemd --user` **transient** unit dies with the user manager. Reboot, logout and
 * `systemctl --user daemon-reload` all leave the reminder rows in the database with no
 * timer behind them. Without a re-arm pass, a Desktop reminder works exactly once.
 *
 * This is the Desktop counterpart to Android's `ACTION_BOOT_COMPLETED` catch-up, and it is
 * the reason the feature survives a restart rather than merely appearing to work.
 *
 * ## Why it is a function and not a call inside `main`
 *
 * It was originally a private `suspend fun` in `desktopApp/main.kt`, which meant nothing
 * executed it in any test — and the property that actually matters is the one most likely
 * to be broken by an innocent-looking edit.
 *
 * ## The property that matters: one failure must not abort the batch
 *
 * [ReminderScheduler.schedule] throws when `systemd-run` fails, and that is correct: a
 * caller that returns normally believes it armed something. In a *loop*, though, an
 * uncaught throw abandons every reminder after the first failure — so one bad unit becomes
 * every later reminder silently unarmed, and the user finds out at 18:00 when nothing
 * arrives. Each arm is therefore caught individually, reported through [Report.failed],
 * and the loop continues.
 *
 * That trade is deliberate and bounded: a failed arm is loudly counted and logged, not
 * swallowed. The alternative — aborting — is not "stricter", it is just a worse blast
 * radius.
 */
object JvmReminderRearm {

    /**
     * What one re-arm pass did.
     *
     * Exists so a caller can log a summary without having to reconstruct it, and so a
     * test can assert on the counts rather than on which calls happened to be made.
     */
    data class Report(
        /** Reminders successfully handed to the scheduler. */
        val armed: Int,
        /** Reminders whose arming threw. The exception is the caller's to log. */
        val failed: List<ReminderId>,
        /** Future reminders deliberately not armed, because the platform cannot arm. */
        val skippedPastDue: Int,
        /** Whether the platform supports arming at all. `false` means nothing was attempted. */
        val supported: Boolean,
    ) {
        /**
         * A one-line summary for the launch log.
         *
         * On an unsupported host this says so explicitly rather than reporting zeroes,
         * because "armed nothing" and "cannot arm anything" are different states and only
         * one of them is a problem.
         */
        fun describe(): String = when {
            !supported -> "reminders not armed: this platform cannot schedule them"

            failed.isNotEmpty() ->
                "re-armed $armed reminder(s); ${failed.size} FAILED: " +
                    failed.joinToString { it.value }

            else -> "re-armed $armed reminder(s); $skippedPastDue already past due"
        }
    }

    /**
     * Arm every reminder in [reminders] whose [Reminder.fireAt] is still ahead of
     * [nowEpochMs].
     *
     * Returns without touching anything when the platform reports
     * [ReminderScheduler.isSupported] as `false`. That check is the capability gate doing
     * its job: on a container or an SSH session there is no `systemd --user`, and asking
     * anyway would produce a log full of failures for an operation nobody can perform.
     *
     * [onFailure] is called once per failed arm, with the reminder and its exception. It
     * exists so this class does not need a logger of its own and so a test can observe
     * failures without capturing output.
     */
    suspend fun run(
        scheduler: ReminderScheduler,
        reminders: List<Reminder>,
        nowEpochMs: Long,
        onFailure: (Reminder, Throwable) -> Unit = { _, _ -> },
    ): Report {
        if (!scheduler.isSupported) {
            return Report(armed = 0, failed = emptyList(), skippedPastDue = 0, supported = false)
        }

        val future = reminders.filter { it.fireAt > nowEpochMs }
        val armed = mutableListOf<Reminder>()
        val failed = mutableListOf<ReminderId>()

        for (reminder in future) {
            // `runCatchingCancellable`, not `runCatching`: the latter also swallows
            // `CancellationException`, which here would mean the app cannot be shut down
            // while it is re-arming. That is a hang, not a batch failure.
            runCatchingCancellable { scheduler.schedule(reminder) }
                .onSuccess { armed += reminder }
                .onFailure { error ->
                    failed += reminder.id
                    onFailure(reminder, error)
                }
        }

        return Report(
            armed = armed.size,
            failed = failed,
            skippedPastDue = reminders.size - future.size,
            supported = true,
        )
    }
}
