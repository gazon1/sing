package com.singularity.todo.feature.alarms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import co.touchlab.kermit.Logger
import com.singularity.todo.core.notifications.AndroidNotifier
import com.singularity.todo.core.platform.Clock
import com.singularity.todo.core.sync.SyncRepository
import com.singularity.todo.feature.pomodoro.PomodoroPhase
import com.singularity.todo.feature.reminders.ReminderFireLogic
import com.singularity.todo.feature.reminders.ReminderId
import com.singularity.todo.feature.reminders.ReminderRepository
import com.singularity.todo.feature.reminders.ReminderScheduler
import com.singularity.todo.feature.tasks.domain.port.TaskRepository
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Multi-action [BroadcastReceiver] that handles all alarm-driven events:
 *
 * - [ACTION_REMINDER_FIRE]        — reminder due; reads fresh task title from DB and posts notification
 * - [ACTION_POMODORO_PHASE_END]   — pomodoro phase ended; posts "work ended" / "back to work" notification
 * - [ACTION_BOOT_COMPLETED]       — device rebooted; catch-up past-due reminders + re-schedule all
 * - [ACTION_REMINDER_DATA_CHANGED] — reminder list changed; re-schedule all active reminders
 *
 * Uses [goAsync] + a dedicated [CoroutineScope] for clean lifecycle management.
 * All heavy work (DB reads, flows) runs on [Dispatchers.Default].
 *
 * ## Stale-text fix
 * Notification title/body are computed at fire time by reading the fresh task title from Room DB.
 * This avoids showing stale titles when the user renamed a task after scheduling the reminder.
 *
 * ## Multi-profile
 * All alarm keys include `userId` to prevent cross-profile collisions:
 * `"reminder:${userId.value}:${reminderId.value}"`
 */
class AlarmReceiver : BroadcastReceiver(), KoinComponent {

    private val log = Logger.withTag("AlarmReceiver")

    private val reminderRepo: ReminderRepository by inject()
    private val taskRepo: TaskRepository by inject()
    private val notifier: AndroidNotifier by inject()
    private val reminderScheduler: ReminderScheduler by inject()
    private val clock: Clock by inject()
    private val syncRepository: SyncRepository by inject()

    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        val scope = CoroutineScope(
            Dispatchers.Default
                + SupervisorJob()
                + CoroutineExceptionHandler { _, e -> log.e(e) { "AlarmReceiver failed" } },
        )
        scope.launch {
            try {
                when (intent.action) {
                    ACTION_REMINDER_FIRE -> handleReminderFire(intent)
                    ACTION_POMODORO_PHASE_END -> handlePomodoroPhaseEnd(intent)
                    ACTION_SYNC_ALARM -> handleSyncAlarm()
                    ACTION_BOOT_COMPLETED, ACTION_REMINDER_DATA_CHANGED -> rescheduleAll()
                }
            } finally {
                pendingResult.finish()
                scope.cancel()
            }
        }
    }

    // ─── Handlers ───────────────────────────────────────────────────────────────

    private suspend fun handleReminderFire(intent: Intent) {
        val reminderId = intent.reminderIdOrNull ?: return
        val userId = intent.userIdOrNull ?: return

        val reminder = reminderRepo.get(reminderId) ?: run {
            log.w { "Reminder not found: ${reminderId.value}" }
            return
        }

        // Fetch fresh task title from DB to avoid showing stale text in notifications.
        val taskTitle = taskRepo.get(reminder.taskId)?.title
        val outcome = ReminderFireLogic.execute(reminder, taskTitle)
        notifier.post(tagFor(userId, reminderId), outcome.title, outcome.body, reminder.viewId?.raw, reminderId.value)

        if (outcome.shouldDelete) {
            reminderRepo.delete(reminderId, userId)
        }
    }

    private suspend fun handlePomodoroPhaseEnd(intent: Intent) {
        val phase = intent.phaseOrNull ?: return
        val phaseName = when (phase) {
            PHASE_WORK -> "Work session"
            PHASE_SHORT_BREAK -> "Short break"
            PHASE_LONG_BREAK -> "Long break"
            else -> "Phase"
        }
        val body = if (phase == PHASE_WORK) "Time for a break ☕" else "Back to work!"
        notifier.post(
            tag = "pomodoro:$phase:${clock.now().toEpochMilliseconds()}",
            title = "$phaseName ended",
            body = body,
            viewId = null,
            payload = null,
        )
    }

    private suspend fun handleSyncAlarm() {
        log.d { "Sync alarm fired" }
        syncRepository.syncOnce()
    }

    /**
     * Catch-up handler for [ACTION_BOOT_COMPLETED] and [ACTION_REMINDER_DATA_CHANGED].
     *
     * 1. Fires past-due reminders (capped at 20 to avoid overload after long offline period).
     * 2. Re-schedules all active reminders from the database.
     *
     * Past-due fire deletes one-shot reminders after posting; recurring reminders are kept
     * and will be re-scheduled by step 2.
     */
    private suspend fun rescheduleAll() {
        val now = clock.now().toEpochMilliseconds()

        // Catch-up: fire past-due reminders (cap 20 to avoid notification storm on boot)
        reminderRepo.watchDueBefore(now).first()
            .takeLast(20)
            .forEach { reminder ->
                val taskTitle = taskRepo.get(reminder.taskId)?.title
                val outcome = ReminderFireLogic.execute(reminder, taskTitle)
                notifier.post(tagFor(reminder.userId, reminder.id), outcome.title, outcome.body, reminder.viewId?.raw, reminder.id.value)
                if (outcome.shouldDelete) {
                    reminderRepo.delete(reminder.id, reminder.userId)
                }
            }

        // Re-schedule all active reminders
        reminderRepo.observeAll().first().forEach { reminder ->
            reminderScheduler.schedule(reminder)
        }
    }

    // ─── Intent helpers ─────────────────────────────────────────────────────────

    private val Intent.reminderIdOrNull: ReminderId?
        get() = getStringExtra(EXTRA_REMINDER_ID)?.let(::ReminderId)

    private val Intent.userIdOrNull: com.singularity.todo.core.ids.UserId?
        get() = getStringExtra(EXTRA_USER_ID)?.let { com.singularity.todo.core.ids.UserId(it) }

    private val Intent.phaseOrNull: String?
        get() = getStringExtra(EXTRA_PHASE)

    // ─── Companion ──────────────────────────────────────────────────────────────

    companion object {
        const val ACTION_REMINDER_FIRE = "com.singularity.todo.feature.alarms.ACTION_REMINDER_FIRE"
        const val ACTION_POMODORO_PHASE_END = "com.singularity.todo.feature.alarms.ACTION_POMODORO_PHASE_END"
        const val ACTION_REMINDER_DATA_CHANGED = "com.singularity.todo.feature.alarms.ACTION_REMINDER_DATA_CHANGED"
        const val ACTION_SYNC_ALARM = "com.singularity.todo.SYNC_ALARM"
        const val ACTION_BOOT_COMPLETED = "android.intent.action.BOOT_COMPLETED"

        const val EXTRA_REMINDER_ID = "reminder_id"
        const val EXTRA_USER_ID = "user_id"
        const val EXTRA_PHASE = "phase"
        const val EXTRA_TASK_ID = "pomodoro_task_id"

        const val PHASE_WORK = "Work"
        const val PHASE_SHORT_BREAK = "ShortBreak"
        const val PHASE_LONG_BREAK = "LongBreak"

        fun tagFor(userId: com.singularity.todo.core.ids.UserId, id: ReminderId) =
            "reminder:${userId.value}:${id.value}"
    }
}
