package com.singularity.todo.feature.tasks.presentation.viewmodel.slot

import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.ui.featureSlot.FeatureSlot
import com.singularity.todo.feature.checklist.ChecklistItem
import com.singularity.todo.feature.tasks.domain.logic.Blocking
import com.singularity.todo.feature.tasks.domain.model.BlockedCloseRequest
import com.singularity.todo.feature.tasks.domain.model.CreateTaskInput
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskChildrenDeps
import com.singularity.todo.feature.tasks.domain.model.TaskContextDeps
import com.singularity.todo.feature.tasks.domain.model.TaskCoreDeps
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.presentation.state.TaskChildrenIntent
import com.singularity.todo.feature.tasks.presentation.state.TaskDetailIntent
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * The collections owned by the task: checklist items, subtasks, and attachments.
 *
 * They live together because they share one rule — each is a child of the task, mutated
 * through its own repository, and rendered in the same editor body. Splitting them into
 * three slots would give three subscribers over the same task for no gain.
 *
 * Subtask toggle writes the subtask rather than the parent: the parent's fields are the
 * entity slot's business.
 */
class TaskChildrenSlot(
    private val taskId: TaskId,
    private val core: TaskCoreDeps,
    private val children: TaskChildrenDeps,
    private val context: TaskContextDeps,
    private val scope: AutoCloseableCoroutineScope,
    taskFlow: StateFlow<Task?>,
    /**
     * Every task the user can see, so the slot can answer "which prerequisites of this
     * subtask are still open". A subtask is a task, and closing one is closing one.
     */
    private val allTasks: StateFlow<List<Task>>,
    private val onError: (String) -> Unit,
    private val onSaved: (String) -> Unit,
    /** Raised when a close is refused until the user confirms. */
    private val onBlockedClose: (BlockedCloseRequest) -> Unit,
    /** A checklist item was deleted, and the user may want it back. */
    private val onUndoDeleteChecklistItem: (ChecklistItem) -> Unit = {},
    /** A subtask was deleted, and the user may want it back. */
    private val onUndoDeleteSubtask: (Task) -> Unit = {},
) : FeatureSlot<TaskChildrenState, TaskChildrenIntent> {
    private val _state = MutableStateFlow(TaskChildrenState())
    override val state: StateFlow<TaskChildrenState> = _state.asStateFlow()

    private val parentTask: StateFlow<Task?> = taskFlow

    init {
        scope.launch {
            combine(
                children.checklistRepository.watchByTask(taskId.value),
                core.taskRepo.observeSubtasks(taskId),
            ) { checklist, subtasks ->
                TaskChildrenState(checklist = checklist, subtasks = subtasks)
            }.collect { _state.value = it }
        }
    }

    override fun onIntent(intent: TaskChildrenIntent) {
        when (intent) {
            is TaskDetailIntent.Domain.AddChecklistItem -> {
                addChecklistItem(intent.title)
            }

            is TaskDetailIntent.Domain.ToggleChecklistItem -> {
                toggleChecklistItem(intent)
            }

            is TaskDetailIntent.Domain.DeleteChecklistItem -> {
                deleteChecklistItem(intent)
            }

            is TaskDetailIntent.Domain.AddSubtask -> {
                addSubtask(intent.title)
            }

            is TaskDetailIntent.Domain.ToggleSubtask -> {
                toggleSubtask(intent.task)
            }

            is TaskDetailIntent.Domain.ConfirmBlockedClose -> {
                // The confirmation names a task; this slot owns the subtasks and
                // nothing else. The task on this screen belongs to the completion slot.
                val subtask = _state.value.subtasks.firstOrNull { it.id == intent.taskId }
                if (subtask != null) toggleSubtask(subtask, force = true)
            }

            is TaskDetailIntent.Domain.DeleteSubtask -> {
                deleteSubtask(intent.task)
            }

            is TaskDetailIntent.Domain.RestoreChecklistItem -> {
                restoreChecklistItem(intent.item)
            }

            is TaskDetailIntent.Domain.RestoreSubtask -> {
                restoreSubtask(intent.taskId)
            }
        }
    }

    // ── Checklist ───────────────────────────────────────────────────────────

    private fun addChecklistItem(title: String) =
        scope.launch {
            if (title.isBlank()) return@launch
            children.checklistRepository
                .addItem(taskId.value, title.trim())
                .onSuccess { onSaved("Item added") }
                .onFailure { onError("Add failed") }
        }

    private fun toggleChecklistItem(intent: TaskDetailIntent.Domain.ToggleChecklistItem) =
        scope.launch {
            children.checklistRepository
                .toggleItem(taskId.value, intent.item.id, "user")
                .onFailure { onError("Toggle failed") }
        }

    /**
     * Deletes the item and offers to put it back.
     *
     * The item is read before the write — afterwards the row is gone and the
     * snapshot is the only copy of it there is.
     */
    private fun deleteChecklistItem(intent: TaskDetailIntent.Domain.DeleteChecklistItem) =
        scope.launch {
            val item = _state.value.checklist.firstOrNull { it.id == intent.id }
            children.checklistRepository
                .delete(intent.id)
                .onSuccess { if (item != null) onUndoDeleteChecklistItem(item) }
                .onFailure { onError("Delete failed") }
        }

    /** Puts a deleted checklist item back, with the id and the place it had. */
    private fun restoreChecklistItem(item: ChecklistItem) =
        scope.launch {
            children.checklistRepository
                .upsert(item)
                .onSuccess { onSaved("Item restored") }
                .onFailure { onError("Restore failed") }
        }

    // ── Subtasks ────────────────────────────────────────────────────────────

    private fun addSubtask(title: String) =
        scope.launch {
            val parent = parentTask.value ?: return@launch
            if (title.isBlank()) return@launch
            core
                .createTask(CreateTaskInput(title = title.trim(), parentTaskId = parent.id))
                .onSuccess { onSaved("Subtask added") }
                .onFailure { onError("Add subtask failed: ${it.message}") }
        }

    private fun toggleSubtask(
        subtask: Task,
        force: Boolean = false,
    ) {
        val completing = subtask.completedAt == null
        scope.launch {
            if (completing) {
                // The write decides: it refuses while a prerequisite is open,
                // and the refusal is part of the statement rather than a check
                // that runs before it.
                val completed =
                    core.taskRepo.complete(subtask.id, force).getOrElse {
                        onError("Save failed")
                        return@launch
                    }
                if (!completed) {
                    val blockers = Blocking.activeBlockers(subtask, allTasks.value)
                    if (blockers.isEmpty()) {
                        onError("Save failed")
                        return@launch
                    }
                    onBlockedClose(
                        BlockedCloseRequest(
                            taskId = subtask.id,
                            taskTitle = subtask.title,
                            blockerTitles = blockers.map { it.title }.toImmutableList(),
                        ),
                    )
                }
            } else {
                core.completion
                    .reopen(subtask.id)
                    .onFailure { onError("Save failed") }
            }
        }
    }

    /**
     * Soft-deletes the subtask and offers to put it back.
     *
     * A subtask is a task, so the write is the same soft delete the task screen
     * uses — and the undo is the same restore.
     */
    private fun deleteSubtask(subtask: Task) =
        scope.launch {
            core.taskRepo
                .softDelete(subtask.id)
                .onSuccess { onUndoDeleteSubtask(subtask) }
                .onFailure { onError("Delete subtask failed") }
        }

    /**
     * Puts a soft-deleted subtask back under its parent.
     *
     * Only a child of this task can be put back here: the undo belongs to the parent
     * that lost it, and a task that is not this parent's child is not this screen's
     * to bring back. The task is read before the write, because the write alone
     * cannot tell whose child it is.
     */
    private fun restoreSubtask(subtaskId: TaskId) =
        scope.launch {
            val subtask = core.taskRepo.get(subtaskId)
            if (subtask == null || subtask.parentTaskId != taskId) {
                onError("Restore failed")
                return@launch
            }
            core.taskRepo
                .restore(subtaskId)
                .onSuccess { onSaved("Subtask restored") }
                .onFailure { onError("Restore failed") }
        }
}
