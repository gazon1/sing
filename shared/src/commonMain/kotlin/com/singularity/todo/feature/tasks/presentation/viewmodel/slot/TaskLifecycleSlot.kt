package com.singularity.todo.feature.tasks.presentation.viewmodel.slot

import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.ui.featureSlot.FeatureSlot
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskDetailDeps
import com.singularity.todo.feature.tasks.presentation.state.TaskDetailIntent
import com.singularity.todo.feature.tasks.presentation.state.TaskLifecycleIntent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Soft delete, archive, and undo-restore.
 *
 * Deleting and archiving are the same repository call with different follow-up: delete
 * keeps a snapshot so the undo action can restore it and emits an undo event, archive
 * navigates away without offering a way back. Splitting them into two intents keeps that
 * difference explicit at the call site instead of hiding it behind a flag.
 *
 * A deleted task's reminders are cancelled first. Leaving the alarm scheduled would make
 * it fire for a task the user can no longer see.
 *
 * [TaskLifecycleState.recentlyDeleted] is deliberately not part of the screen's assembled
 * state — nothing renders it, and keeping it out means a delete does not recompute the
 * whole `TaskDetailUi`.
 */
class TaskLifecycleSlot(
    private val deps: TaskDetailDeps,
    private val scope: AutoCloseableCoroutineScope,
    private val taskFlow: StateFlow<Task?>,
    private val onError: (String) -> Unit,
    private val onUndoDelete: (Task) -> Unit,
    private val onNavigateBack: () -> Unit,
    private val onSaved: (String) -> Unit,
) : FeatureSlot<TaskLifecycleState, TaskLifecycleIntent> {

    private val _state = MutableStateFlow(TaskLifecycleState())
    override val state: StateFlow<TaskLifecycleState> = _state.asStateFlow()

    override fun onIntent(intent: TaskLifecycleIntent) {
        when (intent) {
            TaskDetailIntent.Domain.Delete -> delete()
            TaskDetailIntent.Domain.Archive -> archive()
            TaskDetailIntent.Domain.Restore -> restore()
        }
    }

    private fun delete() = scope.launch {
        val task = taskFlow.value ?: return@launch
        _state.update { it.copy(recentlyDeleted = task) }
        cancelReminders(task)
        deps.taskRepo.softDelete(task.id)
            .onSuccess { onUndoDelete(task) }
            .onFailure {
                _state.update { it.copy(recentlyDeleted = null) }
                onError("Delete failed")
            }
    }

    private fun archive() = scope.launch {
        val task = taskFlow.value ?: return@launch
        deps.taskRepo.softDelete(task.id)
            .onSuccess {
                onSaved("Task archived")
                onNavigateBack()
            }
            .onFailure { onError("Archive failed") }
    }

    private fun restore() = scope.launch {
        val task = _state.value.recentlyDeleted ?: return@launch
        deps.taskRepo.restore(task.id)
            .onSuccess {
                _state.update { it.copy(recentlyDeleted = null) }
                onSaved("Task restored")
            }
            .onFailure { onError("Restore failed") }
    }

    private suspend fun cancelReminders(task: Task) {
        val userId = deps.taskRepo.currentUserId()
        deps.reminderScheduler.cancelByTask(task.id, userId)
    }
}
