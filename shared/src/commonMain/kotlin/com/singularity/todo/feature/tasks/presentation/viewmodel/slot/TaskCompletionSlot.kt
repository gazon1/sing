package com.singularity.todo.feature.tasks.presentation.viewmodel.slot

import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.ui.featureSlot.FeatureSlot
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskContextDeps
import com.singularity.todo.feature.tasks.domain.model.TaskCoreDeps
import com.singularity.todo.feature.tasks.presentation.state.TaskCompletionIntent
import com.singularity.todo.feature.tasks.presentation.state.TaskDetailIntent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Marking the task complete.
 *
 * A recurring task is not completed in place: completing one rolls the recurrence forward
 * and creates the next occurrence, so the write goes through
 * [TaskCoreDeps.completeRecurring] rather than stamping `completedAt`. The state this slot
 * publishes is the projection the hero row renders, so the screen does not have to reach
 * into the task row for completion.
 */
class TaskCompletionSlot(
    private val core: TaskCoreDeps,
    private val context: TaskContextDeps,
    private val scope: AutoCloseableCoroutineScope,
    private val taskFlow: StateFlow<Task?>,
    private val onError: (String) -> Unit,
    private val onSaved: (String) -> Unit,
) : FeatureSlot<TaskCompletionState, TaskCompletionIntent> {

    private val _state = MutableStateFlow(TaskCompletionState())
    override val state: StateFlow<TaskCompletionState> = _state.asStateFlow()

    init {
        scope.launch {
            taskFlow.map { task ->
                TaskCompletionState(
                    isCompleted = task?.isCompleted == true,
                    completedAt = task?.completedAt,
                    hasRecurrence = task?.recurrence != null,
                )
            }.collect { _state.value = it }
        }
    }

    override fun onIntent(intent: TaskCompletionIntent) {
        val task = taskFlow.value ?: return
        when (intent) {
            TaskDetailIntent.Domain.ToggleComplete -> toggleComplete(task)
        }
    }

    private fun toggleComplete(task: Task) {
        val completing = task.completedAt == null
        if (completing && task.recurrence != null) {
            scope.launch {
                core.completeRecurring(task.id)
                    .onSuccess { onSaved("Recurrence completed") }
                    .onFailure { onError("Failed to complete recurring task") }
            }
            return
        }
        val completedAt = if (completing) context.clock.now() else null
        scope.launch {
            core.updateTask(task.id) { it.copy(completedAt = completedAt) }
                .onSuccess { onSaved(if (completedAt != null) "Marked done" else "Marked active") }
                .onFailure { onError("Save failed") }
        }
    }
}
