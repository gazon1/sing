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

internal fun nextPhase(current: PomodoroState, config: PomodoroConfig): PomodoroState {
    val (nextPhase, nextCycles) = when (current.phase) {
        PomodoroPhase.Work -> {
            val newCycles = current.completedCycles + 1
            val phase = if (newCycles >= config.cyclesBeforeLongBreak) PomodoroPhase.LongBreak else PomodoroPhase.ShortBreak
            phase to newCycles
        }
        PomodoroPhase.ShortBreak, PomodoroPhase.LongBreak -> {
            val resetCycles = if (current.phase == PomodoroPhase.LongBreak) 0 else current.completedCycles
            PomodoroPhase.Work to resetCycles
        }
    }
    val secs = config.phaseSecondsOf(nextPhase)
    return PomodoroState(
        phase = nextPhase,
        remainingSeconds = secs,
        phaseDurationSeconds = secs,
        completedCycles = nextCycles,
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
