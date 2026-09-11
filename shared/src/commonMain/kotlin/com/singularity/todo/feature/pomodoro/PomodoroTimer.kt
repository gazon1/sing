package com.singularity.todo.feature.pomodoro

import kotlinx.coroutines.flow.StateFlow

/**
 * Interface for the Pomodoro timer.
 *
 * - androidMain: [AndroidPomodoroTimer] (ViewModel-based, real timer)
 * - jvmMain: [JvmPomodoroTimer] (placeholder — no-op timer)
 */
interface PomodoroTimer {
    val state: StateFlow<PomodoroState>
    val tasks: StateFlow<List<com.singularity.todo.feature.tasks.domain.model.Task>>
    fun start(taskId: String?)
    fun pause()
    fun resume()
    fun stop()
    fun skip()
}
