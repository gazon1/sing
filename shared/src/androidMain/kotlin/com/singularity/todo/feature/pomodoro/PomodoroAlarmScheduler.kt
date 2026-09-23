package com.singularity.todo.feature.pomodoro

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
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
     * @param fireAtEpochMs Wall-clock time when the phase should end.
     * @param taskId Optional task ID for display purposes (passed via PendingIntent extra).
     * @param phase Phase passed to [AlarmReceiver.handlePomodoroPhaseEnd].
     */
    override fun schedulePhaseEnd(fireAtEpochMs: Long, taskId: String?, phase: PomodoroPhase) {
        alarmManager.setAlarmClock(
            AlarmManager.AlarmClockInfo(fireAtEpochMs, null),
            buildPending(taskId, phase),
        )
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
