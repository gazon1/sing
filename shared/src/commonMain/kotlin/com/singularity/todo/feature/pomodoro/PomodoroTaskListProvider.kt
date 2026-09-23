package com.singularity.todo.feature.pomodoro

import com.singularity.todo.feature.tasks.domain.model.Task
import kotlinx.coroutines.flow.StateFlow

/**
 * Provides the current task list for the Pomodoro timer screen.
 *
 * - androidMain: [AndroidPomodoroTaskListProvider][com.singularity.todo.feature.pomodoro.AndroidPomodoroTaskListProvider]
 *   — observes inbox tasks from [com.singularity.todo.feature.tasks.domain.port.TaskRepository]
 * - jvmMain: [JvmPomodoroTaskListProvider][com.singularity.todo.feature.pomodoro.JvmPomodoroTaskListProvider]
 *   — returns an empty, never-updating flow
 */
fun interface PomodoroTaskListProvider {
    /** Returns the current inbox task list as a state flow. */
    fun tasks(): StateFlow<List<Task>>
}
