package com.singularity.todo.feature.tasks.presentation.viewmodel.slot

import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.ui.featureSlot.FeatureSlot
import com.singularity.todo.feature.tasks.domain.model.CreateTaskInput
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskChildrenDeps
import com.singularity.todo.feature.tasks.domain.model.TaskContextDeps
import com.singularity.todo.feature.tasks.domain.model.TaskCoreDeps
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.presentation.state.TaskChildrenIntent
import com.singularity.todo.feature.tasks.presentation.state.TaskDetailIntent
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
    private val onError: (String) -> Unit,
    private val onSaved: (String) -> Unit,
) : FeatureSlot<TaskChildrenState, TaskChildrenIntent> {

    private val _state = MutableStateFlow(TaskChildrenState())
    override val state: StateFlow<TaskChildrenState> = _state.asStateFlow()

    private val parentTask: StateFlow<Task?> = taskFlow

    init {
        scope.launch {
            combine(
                children.checklistRepository.watchByTask(taskId.value),
                core.taskRepo.observeSubtasks(taskId),
                children.attachmentsRepo.watchByTask(taskId),
            ) { checklist, subtasks, attachments ->
                TaskChildrenState(checklist = checklist, subtasks = subtasks, attachments = attachments)
            }.collect { _state.value = it }
        }
    }

    override fun onIntent(intent: TaskChildrenIntent) {
        when (intent) {
            is TaskDetailIntent.Domain.AddChecklistItem -> addChecklistItem(intent.title)
            is TaskDetailIntent.Domain.ToggleChecklistItem -> toggleChecklistItem(intent)
            is TaskDetailIntent.Domain.DeleteChecklistItem -> deleteChecklistItem(intent)
            is TaskDetailIntent.Domain.AddSubtask -> addSubtask(intent.title)
            is TaskDetailIntent.Domain.ToggleSubtask -> toggleSubtask(intent.task)
            is TaskDetailIntent.Domain.DeleteSubtask -> deleteSubtask(intent.task)
            is TaskDetailIntent.Domain.AddUrlAttachment -> addAttachment(intent)
            is TaskDetailIntent.Domain.DeleteAttachment -> deleteAttachment(intent)
        }
    }

    // ── Checklist ───────────────────────────────────────────────────────────

    private fun addChecklistItem(title: String) = scope.launch {
        if (title.isBlank()) return@launch
        children.checklistRepository.addItem(taskId.value, title.trim())
            .onSuccess { onSaved("Item added") }
            .onFailure { onError("Add failed") }
    }

    private fun toggleChecklistItem(intent: TaskDetailIntent.Domain.ToggleChecklistItem) = scope.launch {
        children.checklistRepository.toggleItem(taskId.value, intent.item.id, "user")
            .onFailure { onError("Toggle failed") }
    }

    private fun deleteChecklistItem(intent: TaskDetailIntent.Domain.DeleteChecklistItem) = scope.launch {
        children.checklistRepository.delete(intent.id)
            .onFailure { onError("Delete failed") }
    }

    // ── Subtasks ────────────────────────────────────────────────────────────

    private fun addSubtask(title: String) = scope.launch {
        val parent = parentTask.value ?: return@launch
        if (title.isBlank()) return@launch
        core.createTask(CreateTaskInput(title = title.trim(), parentTaskId = parent.id))
            .onSuccess { onSaved("Subtask added") }
            .onFailure { onError("Add subtask failed: ${it.message}") }
    }

    private fun toggleSubtask(subtask: Task) = scope.launch {
        val completedAt = if (subtask.completedAt == null) context.clock.now() else null
        core.updateTask(subtask.id) { it.copy(completedAt = completedAt) }
            .onFailure { onError("Save failed") }
    }

    private fun deleteSubtask(subtask: Task) = scope.launch {
        core.taskRepo.softDelete(subtask.id)
            .onFailure { onError("Delete subtask failed") }
    }

    // ── Attachments ─────────────────────────────────────────────────────────

    private fun addAttachment(intent: TaskDetailIntent.Domain.AddUrlAttachment) = scope.launch {
        children.attachmentsRepo.addUrlAttachment(taskId, intent.url, intent.title)
            .onSuccess { onSaved("Attachment added") }
            .onFailure { onError("Failed to add attachment") }
    }

    private fun deleteAttachment(intent: TaskDetailIntent.Domain.DeleteAttachment) = scope.launch {
        children.attachmentsRepo.delete(intent.id)
            .onSuccess { onSaved("Attachment deleted") }
            .onFailure { onError("Failed to delete attachment") }
    }
}
