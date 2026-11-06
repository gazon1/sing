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
 * The deferred tasks stay in the picker. The question was asked on 2026-11-06, when the
 * filter had just been renamed and [com.singularity.todo.feature.tasks.domain.model.TaskFilter.NotDeferred]
 * had appeared as the filter that would narrow this set: the answer was to keep
 * [com.singularity.todo.feature.tasks.domain.model.TaskFilter.All]. A focus session on a
 * "someday" task is a strange thing, but the picker is a list of everything the user can
 * start work on, and quietly hiding a task from it hides work the user chose to keep
 * visible. Narrowing the picker is a product decision about what a focus session is for,
 * not a naming one, and it is not made by a rename — so the set stays wide until that
 * decision is made on purpose.
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
