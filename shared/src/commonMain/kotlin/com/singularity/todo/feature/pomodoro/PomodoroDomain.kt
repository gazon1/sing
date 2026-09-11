package com.singularity.todo.feature.pomodoro

data class PomodoroConfig(
    val workMinutes: Int = 25,
    val shortBreakMinutes: Int = 5,
    val longBreakMinutes: Int = 15,
    val cyclesBeforeLongBreak: Int = 4,
)

enum class PomodoroPhase { Work, ShortBreak, LongBreak }

data class PomodoroState(
    val phase: PomodoroPhase = PomodoroPhase.Work,
    val remainingSeconds: Int = 25 * 60,
    val completedCycles: Int = 0,
    val isRunning: Boolean = false,
    val taskId: String? = null,
)

data class PomodoroSession(
    val id: String = com.singularity.todo.core.ids.nextId(),
    val taskId: String?,
    val phase: String,
    val startedAt: Long,
    val endedAt: Long,
    val completedCycles: Int,
)

internal fun nextPhase(current: PomodoroState, config: PomodoroConfig): PomodoroState {
    return when (current.phase) {
        PomodoroPhase.Work -> {
            val newCycles = current.completedCycles + 1
            if (newCycles >= config.cyclesBeforeLongBreak) {
                PomodoroState(
                    phase = PomodoroPhase.LongBreak,
                    remainingSeconds = config.longBreakMinutes * 60,
                    completedCycles = newCycles,
                    isRunning = false,
                    taskId = current.taskId,
                )
            } else {
                PomodoroState(
                    phase = PomodoroPhase.ShortBreak,
                    remainingSeconds = config.shortBreakMinutes * 60,
                    completedCycles = newCycles,
                    isRunning = false,
                    taskId = current.taskId,
                )
            }
        }
        PomodoroPhase.ShortBreak, PomodoroPhase.LongBreak -> PomodoroState(
            phase = PomodoroPhase.Work,
            remainingSeconds = config.workMinutes * 60,
            completedCycles = current.completedCycles,
            isRunning = false,
            taskId = current.taskId,
        )
    }
}
