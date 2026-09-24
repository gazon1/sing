package com.singularity.todo.feature.pomodoro

import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskFilter
import com.singularity.todo.feature.tasks.domain.port.TaskRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Android implementation of [PomodoroTaskListProvider] that observes inbox tasks.
 *
 * Converts the [TaskRepository] flow to a [StateFlow] via [stateIn] so callers
 * always have synchronous read access to the current task list.
 *
 * @param scope CoroutineScope for collecting the task flow. In production this is
 *   the app-level scope provided by the Android lifecycle.
 */
class AndroidPomodoroTaskListProvider(
    private val taskRepository: TaskRepository,
    private val scope: CoroutineScope,
) : PomodoroTaskListProvider {

    private val _tasks = MutableStateFlow<List<Task>>(emptyList())
    override fun tasks(): StateFlow<List<Task>> = _tasks

    init {
        scope.launch {
            taskRepository.observeByFilter(TaskFilter.Inbox)
                .collect { _tasks.value = it }
        }
    }
}
