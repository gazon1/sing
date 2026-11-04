package com.singularity.todo.feature.pomodoro

import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskFilter
import com.singularity.todo.feature.tasks.domain.port.TaskRepository
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Android implementation of [PomodoroTaskListProvider] that observes the tasks a
 * focus session can be started on.
 *
 * The set is [com.singularity.todo.feature.tasks.domain.model.TaskFilter.NotDeferred]
 * — every task that is neither in the trash nor deferred. It is *not* the agenda's
 * Inbox tab: that one holds the undated tasks only.
 *
 * Maintains a private [MutableStateFlow] updated via [CoroutineScope.launch].
 * Callers receive a [StateFlow] view for synchronous read access.
 *
 * @param taskRepository Repository for task observation.
 * @param scope CoroutineScope for collecting the task flow. In production this is
 *   the app-level scope provided by the Android lifecycle.
 */
class AndroidPomodoroTaskListProvider(private val taskRepository: TaskRepository, private val scope: CoroutineScope) :
    PomodoroTaskListProvider {
    private val _tasks = MutableStateFlow<ImmutableList<Task>>(persistentListOf())

    override fun tasks(): StateFlow<ImmutableList<Task>> = _tasks

    init {
        scope.launch {
            taskRepository
                .observeByFilter(TaskFilter.NotDeferred)
                .collect { _tasks.value = it.toImmutableList() }
        }
    }
}
