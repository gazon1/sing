package com.singularity.todo.feature.reminders

import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.tasks.domain.model.TaskId

/**
 * JVM implementation of [ReminderScheduler] for a platform with no alarm scheduler.
 *
 * Alarms require OS-level scheduling, which the desktop app has no equivalent of.
 * [isSupported] is `false`, and [schedule] **throws** rather than returning quietly.
 *
 * ## Why `schedule` throws and `cancel` does not
 *
 * The three methods are not symmetric, and treating them the same is what made this bug
 * hard to see. Arming is the act that can silently lie: a caller that returns normally
 * believes it has scheduled something. Cancelling is a cleanup step that is *correct* to
 * perform unconditionally — nothing was ever armed, so "cancelled" is the true state and
 * there is nothing to report.
 *
 * Throwing on `schedule` moves the failure to the callsite that forgot its
 * [isSupported] check, at the moment it forgets it. Callers that do check are unaffected;
 * callers that do not now fail in a test rather than in production, weeks later, at fire
 * time, for a reminder nobody can see.
 *
 * ## What a replacement must not be
 *
 * The JVM half of the deleted `NotificationPort` used the `at(1)` daemon, and that
 * backend is not to be revived: `cancelAll()` ran `atq`/`atrm` across *every* `at` job
 * on the host, including jobs the user had queued outside this app, and `scheduleAt()`
 * fell back to firing immediately when `at` exited non-zero — so an 18:00 reminder
 * fired at once on any host without `atd`. The desktop replacement is a `systemd
 * --user` timer per reminder, keyed to this app only. See
 * `docs/decisions/2026-10-06-notification-port-deleted-because-it-cancelled-other-peoples-jobs.md`.
 */
class JvmReminderScheduler : ReminderScheduler {

    override val isSupported: Boolean = false

    /**
     * Always throws — this platform cannot arm an alarm.
     *
     * Reached only by a caller that skipped the [isSupported] check, which is the point:
     * the violation surfaces here instead of becoming a reminder that never fires.
     */
    override suspend fun schedule(reminder: Reminder): Unit =
        throw RemindersUnsupportedException("schedule a reminder")

    /** No-op: nothing was ever armed, so this call is already satisfied. */
    override suspend fun cancel(id: ReminderId, userId: UserId) {
        // Intentionally silent — see the class KDoc on why cancel is not symmetric
        // with schedule.
    }

    /** No-op: nothing was ever armed, so this call is already satisfied. */
    override suspend fun cancelByTask(taskId: TaskId, userId: UserId) {
        // Intentionally silent — see the class KDoc on why cancel is not symmetric
        // with schedule.
    }
}
