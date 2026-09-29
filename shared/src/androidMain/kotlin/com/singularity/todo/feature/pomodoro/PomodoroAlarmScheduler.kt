package com.singularity.todo.feature.pomodoro

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.singularity.todo.feature.alarms.AlarmContract
import com.singularity.todo.feature.alarms.AlarmReceiver

/**
 * Schedules OS-level alarms for Pomodoro phase-end notifications.
 *
 * Uses [AlarmManager.setAlarmClock] so the alarm survives Doze and process death.
 * The actual countdown is driven by the in-app 1 Hz ticker in [AndroidPomodoroTimer];
 * this alarm is a safety net that fires even if the app was killed.
 *
 * Single shared alarm (requestCode = 1) — only one phase-end alarm can be active at a time.
 */
open class PomodoroAlarmScheduler(private val context: Context) : PomodoroScheduler {

    private val alarmManager: AlarmManager =
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    /**
     * Schedules a phase-end alarm to fire at [fireAtEpochMs].
     *
     * Falls back to an inexact alarm when the exact-alarm permission is not held —
     * user-revoked, OEM policy, or any device where the grant did not apply.
     * `setAlarmClock` throws [SecurityException] in that case, and this is reached
     * from a composable's click handler: an escaping exception there kills the
     * process. A Pomodoro phase that ends a few minutes late is a much better
     * outcome than a crash, so the fallback is deliberate rather than a stub.
     *
     * @param fireAtEpochMs Wall-clock time when the phase should end.
     * @param taskId Optional task ID for display purposes (passed via PendingIntent extra).
     * @param phase Phase passed to [AlarmReceiver.handlePomodoroPhaseEnd].
     */
    override fun schedulePhaseEnd(fireAtEpochMs: Long, taskId: String?, phase: PomodoroPhase) {
        try {
            alarmManager.setAlarmClock(
                AlarmManager.AlarmClockInfo(fireAtEpochMs, null),
                buildPending(taskId, phase),
            )
        } catch (_: SecurityException) {
            Log.w(
                "PomodoroAlarmScheduler",
                "Exact alarm denied — falling back to an inexact alarm. The phase may end " +
                    "late, and later still under battery saver.",
            )
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                fireAtEpochMs,
                buildPending(taskId, phase),
            )
        }
    }

    /**
     * Cancels any scheduled phase-end alarm.
     * Safe to call even if no alarm is currently scheduled.
     */
    override fun cancelPhaseEndAlarm() {
        alarmManager.cancel(buildPending(null, null))
    }

    private fun buildPending(taskId: String?, phase: PomodoroPhase?): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = AlarmReceiver.ACTION_POMODORO_PHASE_END
            putExtra(AlarmContract.EXTRA_PHASE, phase?.name)
            putExtra(AlarmContract.EXTRA_TASK_ID, taskId)
        }
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        private const val REQUEST_CODE = 1
    }
}
