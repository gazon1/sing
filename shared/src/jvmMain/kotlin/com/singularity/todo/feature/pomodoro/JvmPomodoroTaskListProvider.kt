package com.singularity.todo.feature.pomodoro

import com.singularity.todo.feature.tasks.domain.model.Task
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * JVM implementation of [PomodoroTaskListProvider].
 * Returns an empty, never-updating task list — desktop has no concept of "inbox".
 */
class JvmPomodoroTaskListProvider : PomodoroTaskListProvider {
    override fun tasks(): StateFlow<List<Task>> = MutableStateFlow(emptyList())
}
