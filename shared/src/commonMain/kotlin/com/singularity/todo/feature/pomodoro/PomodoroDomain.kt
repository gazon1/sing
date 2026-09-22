package com.singularity.todo.feature.pomodoro

data class PomodoroConfig(
    val workMinutes: Int = 25,
    val shortBreakMinutes: Int = 5,
    val longBreakMinutes: Int = 15,
    val cyclesBeforeLongBreak: Int = 4,
) {
    /** Returns the duration of [phase] in seconds. */
    fun phaseSecondsOf(phase: PomodoroPhase): Int = when (phase) {
        PomodoroPhase.Work -> workMinutes * 60
        PomodoroPhase.ShortBreak -> shortBreakMinutes * 60
        PomodoroPhase.LongBreak -> longBreakMinutes * 60
    }
}

enum class PomodoroPhase { Work, ShortBreak, LongBreak }

data class PomodoroState(
    val phase: PomodoroPhase = PomodoroPhase.Work,
    val remainingSeconds: Int = 25 * 60,
    val phaseDurationSeconds: Int = 25 * 60,
    val completedCycles: Int = 0,
    val isRunning: Boolean = false,
    val taskId: String? = null,
    /**
     * Epoch millis when the current phase started.
     * Used by [recomputeRemaining] to calculate accurate remaining time after pause/resume.
     * Null when the timer is paused or stopped.
     */
    val phaseStartedAtEpochMs: Long? = null,
)

/**
 * Placeholder session record — emitted when a work phase completes.
 * Will be replaced by a proper entity in a future iteration.
 */
data class PomodoroSession(
    val id: String = com.singularity.todo.core.ids.nextId(),
    val taskId: String?,
    val phase: String,
    val startedAt: Long,
    val endedAt: Long,
    val completedCycles: Int,
)

internal fun nextPhase(current: PomodoroState, config: PomodoroConfig): PomodoroState = when (current.phase) {
    PomodoroPhase.Work -> {
        val newCycles = current.completedCycles + 1
        if (newCycles >= config.cyclesBeforeLongBreak) {
            PomodoroState(
                phase = PomodoroPhase.LongBreak,
                remainingSeconds = config.longBreakMinutes * 60,
                phaseDurationSeconds = config.longBreakMinutes * 60,
                completedCycles = newCycles,
                isRunning = false,
                taskId = current.taskId,
            )
        } else {
            PomodoroState(
                phase = PomodoroPhase.ShortBreak,
                remainingSeconds = config.shortBreakMinutes * 60,
                phaseDurationSeconds = config.shortBreakMinutes * 60,
                completedCycles = newCycles,
                isRunning = false,
                taskId = current.taskId,
            )
        }
    }

    PomodoroPhase.ShortBreak, PomodoroPhase.LongBreak -> PomodoroState(
        phase = PomodoroPhase.Work,
        remainingSeconds = config.workMinutes * 60,
        phaseDurationSeconds = config.workMinutes * 60,
        completedCycles = if (current.phase == PomodoroPhase.LongBreak) 0 else current.completedCycles,
        isRunning = false,
        taskId = current.taskId,
    )
}

/**
 * Recomputes remaining seconds from [state] given the current wall-clock time [nowEpochMs].
 *
 * Used after `resume()` to calculate how much time is left, accounting for the pause duration.
 * When [state.phaseStartedAtEpochMs] is `null` (timer is paused/stopped), returns [state.remainingSeconds]
 * unchanged.
 */
internal fun recomputeRemaining(state: PomodoroState, nowEpochMs: Long): Int {
    val startedAt = state.phaseStartedAtEpochMs ?: return state.remainingSeconds
    val elapsed = (nowEpochMs - startedAt) / 1000
    return (state.remainingSeconds - elapsed.toInt()).coerceAtLeast(0)
}
