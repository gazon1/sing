package com.singularity.todo.feature.alarms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import co.touchlab.kermit.Logger
import com.singularity.todo.core.observability.CrashReportingPort
import com.singularity.todo.feature.alarms.AlarmContract.EXTRA_PHASE
import com.singularity.todo.feature.alarms.AlarmContract.EXTRA_REMINDER_ID
import com.singularity.todo.feature.alarms.AlarmContract.EXTRA_USER_ID
import com.singularity.todo.feature.pomodoro.PomodoroPhase
import com.singularity.todo.feature.reminders.AlarmHandler
import com.singularity.todo.feature.reminders.ReminderId
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.koin.core.context.GlobalContext

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

    private val handler: AlarmHandler by inject()
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
        handler.reminderFired(reminderId, userId)
    }

    private suspend fun handlePomodoroPhaseEnd(intent: Intent) {
        val phase = intent.phaseOrNull ?: return
        handler.pomodoroPhaseEnded(phase)
    }

    /**
     * Boot and data-changed catch-up.
     *
     * The ordering — fire what was missed, then re-arm what is still ahead — lives in
     * [AlarmHandler.catchUp], where it can be tested. This class only says *that* a boot
     * happened, which is the one fact only a `BroadcastReceiver` can supply.
     */
    private suspend fun rescheduleAll() = handler.catchUp()

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
