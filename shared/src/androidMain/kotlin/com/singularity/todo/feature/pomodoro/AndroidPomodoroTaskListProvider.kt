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
 * The set is [com.singularity.todo.feature.tasks.domain.model.TaskFilter.All] — every
 * task that is not in the trash, the deferred (someday) ones included, dated or undated.
 * That is the query this screen has always read: `All` resolves to `watchActive`
 * (`archived_at IS NULL`), which is exactly the read the filter's old name resolved to,
 * so renaming the filter left the picker's contents alone. The set is pinned by
 * `TaskRepositoryContractTest` (`observeByFilter All returns every live task, the
 * deferred ones included`), because a picker that silently narrows is a picker that
 * hides work.
 *
 * Showing the deferred tasks here may be the wrong product answer — a focus session on
 * a "someday" task is a strange thing — but changing it is not this rename's job, and
 * [com.singularity.todo.feature.tasks.domain.model.TaskFilter.NotDeferred] is the filter
 * that would say so.
 *
 * It is *not* the agenda's Inbox tab: that one holds the undated tasks only.
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
                .observeByFilter(TaskFilter.All)
                .collect { _tasks.value = it.toImmutableList() }
        }
    }
}
