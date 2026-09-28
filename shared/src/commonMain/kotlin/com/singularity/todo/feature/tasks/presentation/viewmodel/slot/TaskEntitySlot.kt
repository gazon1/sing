package com.singularity.todo.feature.tasks.presentation.viewmodel.slot

import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.ui.featureSlot.FeatureSlot
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskDetailDeps
import com.singularity.todo.feature.tasks.domain.model.TaskFilter
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.presentation.state.TaskDetailIntent
import com.singularity.todo.feature.tasks.presentation.state.TaskEntityIntent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Task metadata: dates, priority, project, tags, kind, pin, recurrence, dependencies.
 *
 * Owns the two reads that are not the task row itself — the task's project and the full
 * tag catalogue — plus the candidate list backing the dependency picker.
 *
 * Mutations go through [TaskDetailDeps.updateTask] against the task from [taskFlow], never
 * against a snapshot the screen last observed. That is what keeps a concurrent remote edit
 * from being reverted by a field the user did not touch.
 */
class TaskEntitySlot(
    private val taskId: TaskId,
    private val deps: TaskDetailDeps,
    private val scope: AutoCloseableCoroutineScope,
    private val taskFlow: StateFlow<Task?>,
    private val onError: (String) -> Unit,
) : FeatureSlot<TaskEntityState, TaskEntityIntent> {

    private val _state = MutableStateFlow(TaskEntityState())
    override val state: StateFlow<TaskEntityState> = _state.asStateFlow()

    init {
        scope.launch {
            val projectFlow = taskFlow.flatMapLatest { task ->
                task?.projectId?.let { deps.projectsRepo.observe(it) } ?: flowOf(null)
            }
            // The picker must not offer the task as its own dependency, nor trashed tasks.
            val availableFlow = deps.taskRepo.observeByFilter(TaskFilter.All)
                .map { all -> all.filter { !it.isTrashed && it.id != taskId } }
            val allTags = deps.tagsRepo.observeAll()

            combine(projectFlow, allTags, availableFlow, taskFlow) { project, tags, available, task ->
                val taskTags = task?.tags.orEmpty()
                TaskEntityState(
                    project = project,
                    tags = tags.filter { it.id in taskTags },
                    availableTasks = available,
                )
            }.collect { _state.value = it }
        }
    }

    override fun onIntent(intent: TaskEntityIntent) {
        val task = taskFlow.value ?: return
        when (intent) {
            is TaskDetailIntent.Domain.SetDueDate -> mutate(task) { copy(dueDate = intent.date) }

            is TaskDetailIntent.Domain.SetDueTime -> mutate(task) { copy(dueTime = intent.time) }

            is TaskDetailIntent.Domain.SetStartDate -> mutate(task) { copy(startDate = intent.date) }

            is TaskDetailIntent.Domain.SetStartTime -> mutate(task) { copy(startTime = intent.time) }

            is TaskDetailIntent.Domain.SetPriority -> mutate(task) { copy(priority = intent.priority) }

            is TaskDetailIntent.Domain.SetProject -> mutate(task) { copy(projectId = intent.projectId) }

            is TaskDetailIntent.Domain.SetTags -> mutate(task) { copy(tags = intent.tagIds) }

            is TaskDetailIntent.Domain.RemoveTag -> mutate(task) { copy(tags = tags - intent.tagId) }

            is TaskDetailIntent.Domain.SetKind -> mutate(task, "Failed to set kind") { copy(kind = intent.kind) }

            TaskDetailIntent.Domain.ToggleSomeday ->
                mutate(task, "Failed to set someday") { copy(someday = !someday) }

            TaskDetailIntent.Domain.TogglePinned -> mutate(task) { copy(isPinned = !isPinned) }

            is TaskDetailIntent.Domain.SetRecurrence ->
                mutate(task, "Failed to set recurrence") { copy(recurrence = intent.spec) }

            is TaskDetailIntent.Domain.SetDependencies -> setDependencies(task, intent)
        }
    }

    private fun mutate(task: Task, error: String = "Save failed", transform: Task.() -> Task) = scope.launch {
        deps.updateTask(task.id) { it.transform() }.onFailure { onError(error) }
    }

    private fun setDependencies(task: Task, intent: TaskDetailIntent.Domain.SetDependencies) = scope.launch {
        deps.taskRepo.setDependencies(task.id, intent.dependsOn)
            .onFailure { onError("Failed to set dependencies") }
    }
}
