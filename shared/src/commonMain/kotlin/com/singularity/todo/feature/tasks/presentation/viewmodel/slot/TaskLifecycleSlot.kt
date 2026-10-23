package com.singularity.todo.feature.tasks.presentation.viewmodel.slot

import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.ui.featureSlot.FeatureSlot
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskCoreDeps
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.model.TaskSchedulingDeps
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
    private val core: TaskCoreDeps,
    private val scheduling: TaskSchedulingDeps,
    private val scope: AutoCloseableCoroutineScope,
    private val taskFlow: StateFlow<Task?>,
    private val onError: (String) -> Unit,
    private val onUndoDelete: (Task) -> Unit,
    private val onUndoDeleteSeries: (List<TaskId>) -> Unit,
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
            TaskDetailIntent.Domain.Unarchive -> unarchive()
            TaskDetailIntent.Domain.DeleteSeries -> deleteSeries()
            is TaskDetailIntent.Domain.RestoreSeries -> restoreSeries(intent.taskIds)
        }
    }

    private fun delete() = scope.launch {
        val task = taskFlow.value ?: return@launch
        _state.update { it.copy(recentlyDeleted = task) }
        cancelReminders(task)
        core.taskRepo.softDelete(task.id)
            .onSuccess { onUndoDelete(task) }
            .onFailure {
                _state.update { it.copy(recentlyDeleted = null) }
                onError("Delete failed")
            }
    }

    private fun archive() = scope.launch {
        val task = taskFlow.value ?: return@launch
        core.taskRepo.softDelete(task.id)
            .onSuccess {
                onSaved("Task archived")
                onNavigateBack()
            }
            .onFailure { onError("Archive failed") }
    }

    private fun restore() = scope.launch {
        val task = _state.value.recentlyDeleted ?: return@launch
        core.taskRepo.restore(task.id)
            .onSuccess {
                _state.update { it.copy(recentlyDeleted = null) }
                onSaved("Task restored")
            }
            .onFailure {
                // Keep the snapshot alive: the user saw the undo toast dismissed and the
                // error dialog will also auto-dismiss. If we clear the snapshot now, the
                // next snackbar auto-dismiss removes the only safety net and the task is
                // silently lost. Keeping it gives the user a chance to retry once the
                // server recovers.
                onError("Restore failed")
            }
    }

    /**
     * Archives every occurrence of the series, and offers to put them back.
     *
     * A series is several dates of one work, and the work is not something the
     * user throws away by accident — so the undo is not a confirmation dialog
     * with a list of dates to read: it is a way back, offered right after the
     * deletion, with the number of dates the user is about to lose.
     *
     * The screen does not navigate away here: the toast that carries the offer
     * lives in this screen, so leaving before the toast has been seen would
     * take the way back with it. The screen leaves when the offer is declined —
     * the screen decides that, because it is the one that knows how the toast is
     * hosted.
     */
    private fun deleteSeries() = scope.launch {
        val task = taskFlow.value ?: return@launch
        val seriesId = task.seriesId ?: return@launch
        core.updateSeries.archiveSeries(seriesId)
            .onSuccess { ids ->
                onUndoDeleteSeries(ids)
            }
            .onFailure { onError("Failed to delete the series") }
    }

    /**
     * Puts back the occurrences of a series that was deleted.
     *
     * Every id is restored on its own: one that cannot be restored is reported
     * and the others still come back, because a partly restored series is
     * better than a series that stayed in the trash.
     */
    private fun restoreSeries(taskIds: List<TaskId>) = scope.launch {
        if (taskIds.isEmpty()) return@launch
        var restored = 0
        var failed = false
        taskIds.forEach { id ->
            core.taskRepo.restore(id)
                .onSuccess { restored++ }
                .onFailure { failed = true }
        }
        if (restored > 0) onSaved("Series restored")
        if (failed) onError("Restore failed")
    }

    /**
     * Un-trashes the task this screen is open on — the action behind "Восстановить"
     * when the detail screen was reached from the Archive screen. Unlike [restore]
     * there is no recently-deleted snapshot to consult: the write targets
     * [taskFlow]'s task directly, and success pops back to the list it came from.
     */
    private fun unarchive() = scope.launch {
        val task = taskFlow.value ?: return@launch
        core.taskRepo.restore(task.id)
            .onSuccess {
                onSaved("Task restored")
                onNavigateBack()
            }
            .onFailure { onError("Restore failed") }
    }

    private suspend fun cancelReminders(task: Task) {
        val userId = core.taskRepo.currentUserId()
        scheduling.reminderScheduler.cancelByTask(task.id, userId)
    }
}
