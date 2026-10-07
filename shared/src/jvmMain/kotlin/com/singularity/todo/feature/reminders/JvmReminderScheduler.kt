package com.singularity.todo.feature.reminders

import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.tasks.domain.model.TaskId

/**
 * JVM stub implementation of [ReminderScheduler].
 *
 * Alarms require OS-level scheduling, which the desktop app has no equivalent of.
 * Every method is a no-op, so [isSupported] is `false`: callers must refuse to persist
 * a reminder rather than writing a row that renders as a working reminder forever and
 * never fires.
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

    override suspend fun schedule(reminder: Reminder) {
        // No-op on JVM: there is no alarm scheduler to arm. Callers gate on
        // `isSupported` before persisting, so reaching here means the guard was skipped.
    }

    override suspend fun cancel(id: ReminderId, userId: UserId) {
        // No-op on JVM: nothing was ever armed.
    }

    override suspend fun cancelByTask(taskId: TaskId, userId: UserId) {
        // No-op on JVM: nothing was ever armed.
    }
}
