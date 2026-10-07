package com.singularity.todo.feature.alarms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import co.touchlab.kermit.Logger
import com.singularity.todo.core.observability.CrashReportingPort
import com.singularity.todo.core.notifications.AndroidNotifier
import com.singularity.todo.feature.alarms.AlarmContract.EXTRA_PHASE
import com.singularity.todo.feature.alarms.AlarmContract.EXTRA_REMINDER_ID
import com.singularity.todo.feature.alarms.AlarmContract.EXTRA_USER_ID
import com.singularity.todo.feature.pomodoro.PomodoroPhase
import com.singularity.todo.feature.reminders.ReminderDelivery
import com.singularity.todo.feature.reminders.ReminderId
import com.singularity.todo.feature.reminders.ReminderScheduler
import com.singularity.todo.feature.reminders.domain.port.ReminderRepository
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.koin.core.context.GlobalContext
import kotlin.time.Clock

/**
 * Multi-action [BroadcastReceiver] that handles all alarm-driven events:
 *
 * - [ACTION_REMINDER_FIRE]        — reminder due; reads fresh task title from DB and posts notification
 * - [ACTION_POMODORO_PHASE_END]   — pomodoro phase ended; posts "work ended" / "back to work" notification
 * - [ACTION_BOOT_COMPLETED]       — device rebooted; catch-up past-due reminders + re-schedule all
 * - [ACTION_REMINDER_DATA_CHANGED] — reminder list changed; re-schedule all active reminders
 *
 * Uses [goAsync] + a dedicated [CoroutineScope] for clean lifecycle management.
 * All heavy work (DB reads, notification posting) runs on [Dispatchers.IO].
 *
 * ## Stale-text fix
 * Notification title/body are computed at fire time by reading the fresh task title from Room DB.
 * This avoids showing stale titles when the user renamed a task after scheduling the reminder.
 *
 * ## Multi-profile
 * All alarm keys include `userId` to prevent cross-profile collisions:
 * `"reminder:${userId.value}:${reminderId.value}"`
 */
class AlarmReceiver :
    BroadcastReceiver(),
    KoinComponent {

    private val log = Logger.withTag("AlarmReceiver")

    private val reminderRepo: ReminderRepository by inject()
    private val notifier: AndroidNotifier by inject()
    private val delivery: ReminderDelivery by inject()
    private val reminderScheduler: ReminderScheduler by inject()
    private val clock: Clock by inject()
    private val crashReporter: CrashReportingPort by inject()

    override fun onReceive(context: Context, intent: Intent) {
        // Defensive: ensure Koin is initialized before injecting dependencies.
        // On some Android versions the BroadcastReceiver can fire before Application.onCreate.
        if (GlobalContext.getOrNull() == null) {
            log.e { "Koin not initialized — skipping alarm handling" }
            goAsync().finish()
            return
        }
        val pendingResult = goAsync()
        val scope = CoroutineScope(
            Dispatchers.IO +
                SupervisorJob() +
                CoroutineExceptionHandler { _, e ->
                    log.e(e) { "AlarmReceiver failed" }
                    crashReporter.report(e, "alarm.receiver_failed")
                },
        )
        scope.launch {
            try {
                when (intent.action) {
                    ACTION_REMINDER_FIRE -> handleReminderFire(intent)
                    ACTION_POMODORO_PHASE_END -> handlePomodoroPhaseEnd(intent)
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

        // The four delivery steps live in `ReminderDelivery`, shared with the Desktop
        // launcher. This class decides *when* to fire; it does not decide what firing
        // means, because that is the part the two platforms must not disagree about.
        delivery.fire(reminderId, userId)
    }

    private suspend fun handlePomodoroPhaseEnd(intent: Intent) {
        val phase = intent.phaseOrNull ?: return
        val phaseName = when (phase) {
            PomodoroPhase.Work -> "Work session"
            PomodoroPhase.ShortBreak -> "Short break"
            PomodoroPhase.LongBreak -> "Long break"
        }
        val body = if (phase == PomodoroPhase.Work) "Time for a break ☕" else "Back to work!"
        notifier.post(
            tag = "pomodoro:$phase:${clock.now().toEpochMilliseconds()}",
            title = "$phaseName ended",
            body = body,
            viewId = null,
        )
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
        reminderRepo.watchRecentDueBefore(now, 20).first()
            .forEach { reminder -> delivery.fireKnown(reminder) }

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

    private val Intent.phaseOrNull: PomodoroPhase?
        get() = getStringExtra(EXTRA_PHASE)?.let { phaseName ->
            runCatching { PomodoroPhase.valueOf(phaseName) }.getOrNull()
        }

    // ─── Companion ──────────────────────────────────────────────────────────────

    companion object {
        // Android-specific action names — not shared with commonMain
        const val ACTION_REMINDER_FIRE = "com.singularity.todo.feature.alarms.ACTION_REMINDER_FIRE"
        const val ACTION_POMODORO_PHASE_END = "com.singularity.todo.feature.alarms.ACTION_POMODORO_PHASE_END"
        const val ACTION_REMINDER_DATA_CHANGED = "com.singularity.todo.feature.alarms.ACTION_REMINDER_DATA_CHANGED"
        const val ACTION_BOOT_COMPLETED = "android.intent.action.BOOT_COMPLETED"
    }
}
