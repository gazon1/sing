package com.singularity.todo.feature.pomodoro

import com.singularity.todo.feature.tasks.domain.model.Task
import kotlinx.coroutines.flow.StateFlow

/**
 * Interface for the Pomodoro timer.
 *
 * - androidMain: [AndroidPomodoroTimer][com.singularity.todo.feature.pomodoro.AndroidPomodoroTimer] (ViewModel-based, real timer)
 * - jvmMain: [JvmPomodoroTimer][com.singularity.todo.feature.pomodoro.JvmPomodoroTimer] (placeholder — no-op timer)
 */
interface PomodoroTimer {
    val state: StateFlow<PomodoroState>
    val tasks: StateFlow<List<Task>>
    /** Timer configuration. Exposed so [PomodoroScreen] can read phase durations without hardcoding them. */
    val config: PomodoroConfig
    fun start(taskId: String?)
    fun pause()
    fun resume()
    fun stop()
    fun skip()
}
