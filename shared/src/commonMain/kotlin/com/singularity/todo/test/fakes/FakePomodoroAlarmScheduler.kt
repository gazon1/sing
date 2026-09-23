package com.singularity.todo.test.fakes

import com.singularity.todo.feature.pomodoro.PomodoroPhase
import com.singularity.todo.feature.pomodoro.PomodoroScheduler

/**
 * Test double for [PomodoroScheduler].
 *
 * Records all scheduled alarms and provides a way to:
 * - Inspect what was last scheduled
 * - Trigger the alarm manually (simulating OS callback)
 * - Reset state between tests
 *
 * Example:
 * ```
 * val scheduler = FakePomodoroScheduler()
 * val timer = AndroidPomodoroTimer(clock, taskList, scheduler, config, testScope.backgroundScope)
 * timer.start("t1")
 * assertEquals(PomodoroPhase.Work, scheduler.scheduledPhase)
 * ```
 */
class FakePomodoroScheduler : PomodoroScheduler {

    private var _scheduledFireAt: Long? = null
    private var _scheduledTaskId: String? = null
    private var _scheduledPhase: PomodoroPhase? = null
    private var _canceled = false

    /** The epoch millis of the last scheduled alarm, or null if none. */
    val scheduledFireAt: Long? get() = _scheduledFireAt

    /** The task ID passed to the last schedulePhaseEnd call. */
    val scheduledTaskId: String? get() = _scheduledTaskId

    /** The phase passed to the last schedulePhaseEnd call. */
    val scheduledPhase: PomodoroPhase? get() = _scheduledPhase

    /** True if cancelPhaseEndAlarm was called since the last reset. */
    val wasCanceled: Boolean get() = _canceled

    override fun schedulePhaseEnd(fireAtEpochMs: Long, taskId: String?, phase: PomodoroPhase) {
        _scheduledFireAt = fireAtEpochMs
        _scheduledTaskId = taskId
        _scheduledPhase = phase
        _canceled = false
    }

    override fun cancelPhaseEndAlarm() {
        _canceled = true
    }

    /** Resets all recorded state. Call between tests. */
    fun reset() {
        _scheduledFireAt = null
        _scheduledTaskId = null
        _scheduledPhase = null
        _canceled = false
    }
}
