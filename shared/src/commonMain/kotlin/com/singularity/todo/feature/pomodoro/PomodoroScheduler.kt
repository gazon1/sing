package com.singularity.todo.feature.pomodoro

/**
 * Port for scheduling and cancelling Pomodoro phase-end OS alarms.
 *
 * Android implementation: [PomodoroAlarmScheduler][com.singularity.todo.feature.pomodoro.PomodoroAlarmScheduler].
 * Test implementation: [FakePomodoroScheduler].
 */
interface PomodoroScheduler {
    /**
     * Schedules a phase-end alarm to fire at [fireAtEpochMs].
     *
     * @param fireAtEpochMs Wall-clock time when the phase should end.
     * @param taskId Optional task ID for display purposes (passed via PendingIntent extra).
     * @param phase Phase passed to [com.singularity.todo.feature.alarms.AlarmReceiver.handlePomodoroPhaseEnd].
     */
    fun schedulePhaseEnd(fireAtEpochMs: Long, taskId: String?, phase: PomodoroPhase)

    /** Cancels any scheduled phase-end alarm. Safe to call even if no alarm is currently scheduled. */
    fun cancelPhaseEndAlarm()
}
