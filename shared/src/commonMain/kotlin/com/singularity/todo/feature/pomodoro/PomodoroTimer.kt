package com.singularity.todo.feature.pomodoro

import kotlinx.coroutines.flow.StateFlow

/**
 * Interface for the Pomodoro timer.
 *
 * - androidMain: [AndroidPomodoroTimer][com.singularity.todo.feature.pomodoro.AndroidPomodoroTimer] (real timer)
 * - jvmMain: [JvmPomodoroTimer][com.singularity.todo.feature.pomodoro.JvmPomodoroTimer] (placeholder — no-op timer)
 *
 * Task list is provided separately by [PomodoroTaskListProvider] to maintain layer separation.
 */
interface PomodoroTimer {
    val state: StateFlow<PomodoroState>
    /** Timer configuration. Exposed so [PomodoroScreen] can read phase durations without hardcoding them. */
    val config: PomodoroConfig
    fun start(taskId: String?)
    fun pause()
    fun resume()
    fun stop()
    fun skip()
}
