package com.singularity.todo.feature.pomodoro

import com.singularity.todo.feature.tasks.domain.model.Task
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * JVM stub implementation of [PomodoroTimer].
 *
 * The Pomodoro timer requires Android-specific APIs (AlarmManager, notification channels),
 * so this is a no-op placeholder. The timer UI will show a static state and the
 * start/pause/stop buttons will have no effect.
 */
class JvmPomodoroTimer : PomodoroTimer {
    override val config: PomodoroConfig = PomodoroConfig()

    private val _state = MutableStateFlow(PomodoroState())
    override val state: StateFlow<PomodoroState> = _state.asStateFlow()

    private val _tasks = MutableStateFlow<List<Task>>(emptyList())
    override val tasks: StateFlow<List<Task>> = _tasks.asStateFlow()

    override fun start(taskId: String?) {
        // No-op on JVM
    }

    override fun pause() {
        // No-op on JVM
    }

    override fun resume() {
        // No-op on JVM
    }

    override fun stop() {
        // No-op on JVM
    }

    override fun skip() {
        // No-op on JVM
    }
}
