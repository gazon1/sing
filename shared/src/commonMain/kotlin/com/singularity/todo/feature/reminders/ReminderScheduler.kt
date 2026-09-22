package com.singularity.todo.feature.reminders

import co.touchlab.kermit.Logger
import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.notifications.NotificationPort
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.cancelAndJoin
import kotlin.time.Duration.Companion.milliseconds

/**
 * Background scheduler that polls for due reminders and fires notifications.
 *
 * Runs a polling loop every [POLL_INTERVAL_MS]. On each tick it queries
 * [ReminderRepository.watchDueBefore] for the current user and dispatches
 * [NotificationPort.scheduleAt] for any overdue items, then removes one-shot
 * reminders from the store (recurring reminders are re-added by callers).
 *
 * Implements [AutoCloseable] so callers can bind it to a lifecycle that
 * manages the scope automatically.
 *
 * @param scope Injected [AutoCloseableCoroutineScope] — callers must call
 *              [AutoCloseable.close] or bind to a lifecycle that does so.
 *              Uses [AutoCloseableCoroutineScope.invoke] if not supplied.
 */
class ReminderScheduler(
    private val log: Logger,
    private val notificationPort: NotificationPort,
    private val reminderRepository: ReminderRepository,
    private val currentUser: ProfileAwareCurrentUser,
    private val scope: AutoCloseableCoroutineScope = AutoCloseableCoroutineScope(),
) : AutoCloseable by scope {

    private var job: Job? = null

    /**
     * Guards [poll] against concurrent calls from [start] and tests.
     * [NotificationPort.scheduleAt] is synchronous and can block, so concurrent
     * polls must be serialised.
     */
    private val pollMutex = Mutex()

    /** Start polling. Idempotent — safe to call multiple times. */
    fun start() {
        if (job?.isActive == true) return
        job = scope.launch { loop() }
    }

    /** Stop polling. Suspending to ensure the loop has actually terminated. */
    suspend fun stop() {
        job?.cancelAndJoin()
        job = null
    }

    private suspend fun loop() {
        while (true) {
            try {
                delay(POLL_INTERVAL_MS.milliseconds)
                poll()
            } catch (e: Throwable) {
                // Unexpected exception in the loop — log and continue polling.
                // The loop is long-lived and must not terminate due to a single
                // poll cycle failure (e.g. a transient DB error).
                log.e(e) { "Unexpected error in reminder poll loop" }
            }
        }
    }

    /**
     * Single poll cycle. Public for testing.
     *
     * Queries reminders with `fire_at <= now` for the current user, fires
     * OS notifications, and deletes one-shot reminders after firing.
     * Recurring reminders must be re-created by callers after firing.
     */
    suspend fun poll(nowEpochMs: Long = System.currentTimeMillis()) {
        if (!notificationPort.isAvailable) return

        pollMutex.withLock {
            if (!notificationPort.isAvailable) return@withLock

            val uid = currentUser.scopedUserId.value
            val due = reminderRepository.watchDueBefore(nowEpochMs)
                .first()
                .filter { it.userId == uid }

            for (reminder in due) {
                // Skip recurring reminders that fired recently (e.g., device restarted
                // while reminders were pending — prevents duplicate fires within a short window)
                if (reminder.recurringPattern != null) {
                    val lastFired = reminder.lastFiredAt
                    if (lastFired != null && nowEpochMs - lastFired < MIN_RECURRING_INTERVAL_MS) {
                        continue
                    }
                }

                val notificationKey = "reminder:${reminder.id.value}"

                // Fire the OS notification; any platform error will propagate as an exception
                try {
                    notificationPort.scheduleAt(
                        key = notificationKey,
                        title = "Task Reminder",
                        body = "A task reminder is due",
                        fireAtEpochMs = reminder.fireAt,
                        payload = reminder.id.value,
                        viewId = reminder.viewId?.raw,
                    )
                } catch (e: Throwable) {
                    log.w(e) { "NotificationPort.scheduleAt failed [key=$notificationKey]" }
                    continue
                }

                if (reminder.recurringPattern == null) {
                    // Remove one-shot reminder after successful firing
                    reminderRepository.delete(reminder.id).onFailure { e ->
                        log.w(e) { "Reminder delete failed [id=${reminder.id.value}]" }
                    }
                } else {
                    // Record last fire for recurring reminders to prevent duplicate fires
                    reminderRepository.markFired(reminder.id, nowEpochMs).onFailure { e ->
                        log.w(e) { "markFired failed [id=${reminder.id.value}]" }
                    }
                }
            }
        }
    }

    companion object {
        /** Poll interval in milliseconds. */
        const val POLL_INTERVAL_MS = 60_000L
        /**
         * Minimum interval between fires of the same recurring reminder.
         * Guards against duplicate fires when the device wakes from sleep
         * or when the scheduler picks up pending reminders after a restart.
         */
        const val MIN_RECURRING_INTERVAL_MS = 30_000L
    }
}
