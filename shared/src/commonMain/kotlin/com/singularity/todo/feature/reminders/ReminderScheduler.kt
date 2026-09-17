package com.singularity.todo.feature.reminders

import co.touchlab.kermit.Logger
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.notifications.NotificationPort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

/**
 * Background scheduler that polls for due reminders and fires notifications.
 *
 * Runs a polling loop on [Dispatchers.Default] every [POLL_INTERVAL_MS].
 * On each tick it queries [ReminderRepository.watchDueBefore] and dispatches
 * [NotificationPort.scheduleAt] for any overdue items, then removes them from
 * the store (one-shot semantics — recurring reminders are re-added by callers).
 *
 * Cancel [job] to stop polling.
 */
class ReminderScheduler(
    private val log: Logger,
    private val notificationPort: NotificationPort,
    private val reminderRepository: ReminderRepository,
    private val currentUserId: UserId = UserId("current_user"),
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    /** Start polling. Idempotent — safe to call multiple times. */
    fun start() {
        if (job?.isActive == true) return
        job = scope.launch { loop() }
    }

    /** Stop polling. */
    fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun loop() {
        while (true) {
            delay(POLL_INTERVAL_MS.milliseconds)
            poll()
        }
    }

    /**
     * Single poll cycle. Public for testing.
     *
     * - Queries all reminders with `fire_at <= now` for [currentUserId].
     * - For each due reminder: schedules the OS notification, then deletes it.
     * - Recurring reminders must be re-created by callers after firing.
     */
    suspend fun poll(nowEpochMs: Long = System.currentTimeMillis()) {
        if (!notificationPort.isAvailable) return

        val due = reminderRepository.watchDueBefore(nowEpochMs, currentUserId).first()

        for (reminder in due) {
            val notificationKey = "reminder:${reminder.id.value}"

            // Fire the OS notification
            notificationPort.scheduleAt(
                key = notificationKey,
                title = "Task Reminder",
                body = "A task reminder is due",
                fireAtEpochMs = reminder.fireAt,
                payload = reminder.id.value,
                viewId = reminder.viewId?.raw,
            )

            // Remove one-shot reminder after firing
            if (reminder.recurringPattern == null) {
                reminderRepository.delete(reminder.id, currentUserId).onFailure { e ->
                    // Best-effort: one-shot reminder cleanup; will be retried on next poll
                    log.w(e) { "Reminder delete failed [id=${reminder.id.value}]" }
                }
            }
        }
    }

    companion object {
        /** Poll interval in milliseconds. */
        const val POLL_INTERVAL_MS = 60_000L
    }
}
