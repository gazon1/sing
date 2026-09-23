package com.singularity.todo.feature.pomodoro

import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskFilter
import com.singularity.todo.feature.tasks.domain.port.TaskRepository
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/**
 * Android implementation of [PomodoroTaskListProvider] that observes inbox tasks.
 *
 * Converts the [TaskRepository] flow to a [StateFlow] via [stateIn] so callers
 * always have synchronous read access to the current task list.
 */
class AndroidPomodoroTaskListProvider(private val taskRepository: TaskRepository) : PomodoroTaskListProvider {

    override fun tasks(): StateFlow<List<Task>> = taskRepository.observeByFilter(TaskFilter.Inbox)
        .stateIn(MainScope(), SharingStarted.Eagerly, emptyList())
}
