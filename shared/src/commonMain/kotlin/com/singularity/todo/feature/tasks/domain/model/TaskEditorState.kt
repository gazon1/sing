package com.singularity.todo.feature.tasks.domain.model

import com.singularity.todo.core.ids.IdGenerator
import com.singularity.todo.core.platform.Clock
import com.singularity.todo.core.platform.TimeZoneProvider
import com.singularity.todo.feature.checklist.ChecklistItemId
import com.singularity.todo.feature.checklist.ChecklistUseCase
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.reminders.ReminderRepository
import com.singularity.todo.feature.settings.ReminderOffset
import com.singularity.todo.feature.tasks.domain.port.TaskRepository
import com.singularity.todo.feature.tasks.domain.usecase.CreateTaskUseCase
import com.singularity.todo.feature.tasks.domain.usecase.UpdateTaskUseCase

/**
 * Whether the editor is creating a new task or editing an existing one.
 */
sealed interface TaskEditorMode {
    data object New : TaskEditorMode
    data class Edit(val taskId: TaskId) : TaskEditorMode
}

/**
 * UI state for the create/edit task screen.
 *
 * The screen is stateless — every field belongs to the VM so it can be tested
 * without Compose and so save/error handling lives in one place.
 *
 * @param originalTask When non-null, the editor is in edit mode with this task pre-loaded.
 *                     Used for dirty tracking and discard.
 */
data class TaskEditorUiState(
    val mode: TaskEditorMode = TaskEditorMode.New,
    val title: String = "",
    val description: String = "",
    val priority: TaskPriority = TaskPriority.None,
    val dueDate: kotlinx.datetime.LocalDate? = null,
    val dueTime: kotlinx.datetime.LocalTime? = null,
    val projectId: String? = null,
    val tagIds: List<String> = emptyList(),
    val checklistItems: List<ChecklistItemUi> = emptyList(),
    val newChecklistItem: String = "",
    val reminderOffset: ReminderOffset? = null,
    val pendingAttachments: List<PendingAttachment> = emptyList(),
    val loading: Boolean = false,
    val saving: Boolean = false,
    val errorMessage: String? = null,
    /** Pre-loaded task for edit mode; used for dirty comparison and discard. */
    val originalTask: Task? = null,
) {
    /**
     * Detects unsaved changes by comparing against the pre-loaded [originalTask].
     * In New mode, dirty when any field has a non-default value.
     */
    val dirty: Boolean
        get() = when (val o = originalTask) {
            null -> title.isNotBlank() || description.isNotBlank() ||
                priority != TaskPriority.None || dueDate != null || dueTime != null ||
                projectId != null || tagIds.isNotEmpty() ||
                checklistItems.isNotEmpty() || reminderOffset != null ||
                pendingAttachments.isNotEmpty()
            else -> title != o.title || description != (o.description ?: "") ||
                priority != o.priority ||
                dueDate != o.dueDate ||
                dueTime != o.dueTime?.let { parseTime(it) } ||
                projectId != o.projectId?.value ||
                tagIds != o.tags.map { it.value }
        }

    private fun parseTime(hhmm: String): kotlinx.datetime.LocalTime? {
        val parts = hhmm.split(":")
        return if (parts.size == 2) {
            kotlinx.datetime.LocalTime(parts[0].toIntOrNull() ?: return null, parts[1].toIntOrNull() ?: return null)
        } else null
    }
}

data class PendingAttachment(
    val path: String,
    val name: String,
    val mimeType: String?,
)

data class ChecklistItemUi(
    val id: String,
    val title: String,
    val isCompleted: Boolean = false,
)

sealed interface TaskEditorIntent {
    data class TitleChanged(val text: String) : TaskEditorIntent
    data class DescriptionChanged(val text: String) : TaskEditorIntent
    data class PriorityChanged(val priority: TaskPriority) : TaskEditorIntent
    data class DueDateChanged(val date: kotlinx.datetime.LocalDate?) : TaskEditorIntent
    data object ClearDueDate : TaskEditorIntent
    data class DueTimeChanged(val time: kotlinx.datetime.LocalTime?) : TaskEditorIntent
    data object ClearDueTime : TaskEditorIntent
    data class ProjectChanged(val projectId: String?) : TaskEditorIntent
    data class TagsChanged(val tagIds: List<String>) : TaskEditorIntent
    data class NewChecklistItemChanged(val text: String) : TaskEditorIntent
    data object AddChecklistItem : TaskEditorIntent
    data class ToggleChecklistItem(val id: String) : TaskEditorIntent
    data class DeleteChecklistItem(val id: String) : TaskEditorIntent
    data class ReminderOffsetChanged(val offset: ReminderOffset?) : TaskEditorIntent
    data class AddAttachment(val path: String, val name: String, val mimeType: String?) : TaskEditorIntent
    data class RemovePendingAttachment(val path: String) : TaskEditorIntent
    /** Loads an existing task for editing; switches mode to Edit. */
    data class LoadTask(val taskId: TaskId) : TaskEditorIntent
    /** Resets state to the original loaded task (discards changes). */
    data object DiscardChanges : TaskEditorIntent
    data object Save : TaskEditorIntent
    data object ErrorShown : TaskEditorIntent
}

/**
 * Dependencies for [TaskEditorViewModel].
 */
data class TaskEditorDeps(
    val createTask: CreateTaskUseCase,
    val updateTask: UpdateTaskUseCase,
    val clock: Clock,
    val currentUser: ProfileAwareCurrentUser,
    val taskRepository: TaskRepository,
    val checklistUseCase: ChecklistUseCase,
    val reminderRepository: ReminderRepository,
    val attachmentSaver: AttachmentSaver,
    val idGen: IdGenerator,
    val timeZoneProvider: TimeZoneProvider,
)

/**
 * Pure reducer — `(State, Intent) -> State`. Covers all intents that don't
 * have IO / list-mutation side effects. Tested without a VM.
 */
internal fun TaskEditorUiState.reduce(intent: TaskEditorIntent): TaskEditorUiState = when (intent) {
    is TaskEditorIntent.TitleChanged -> copy(title = intent.text, errorMessage = null)
    is TaskEditorIntent.DescriptionChanged -> copy(description = intent.text)
    is TaskEditorIntent.PriorityChanged -> copy(priority = intent.priority)
    is TaskEditorIntent.DueDateChanged -> copy(dueDate = intent.date)
    TaskEditorIntent.ClearDueDate -> copy(dueDate = null)
    is TaskEditorIntent.DueTimeChanged -> copy(dueTime = intent.time)
    TaskEditorIntent.ClearDueTime -> copy(dueTime = null)
    is TaskEditorIntent.ProjectChanged -> copy(projectId = intent.projectId)
    is TaskEditorIntent.TagsChanged -> copy(tagIds = intent.tagIds)
    is TaskEditorIntent.NewChecklistItemChanged -> copy(newChecklistItem = intent.text)
    is TaskEditorIntent.ReminderOffsetChanged -> copy(reminderOffset = intent.offset)
    is TaskEditorIntent.AddAttachment -> copy(
        pendingAttachments = pendingAttachments + PendingAttachment(
            path = intent.path,
            name = intent.name,
            mimeType = intent.mimeType,
        ),
    )
    is TaskEditorIntent.RemovePendingAttachment -> copy(
        pendingAttachments = pendingAttachments.filter { it.path != intent.path },
    )
    TaskEditorIntent.ErrorShown -> copy(errorMessage = null)
    // Impure intents pass through unchanged — handled separately in the VM.
    TaskEditorIntent.AddChecklistItem,
    is TaskEditorIntent.ToggleChecklistItem,
    is TaskEditorIntent.DeleteChecklistItem,
    TaskEditorIntent.Save,
    is TaskEditorIntent.LoadTask,
    TaskEditorIntent.DiscardChanges -> this
}

// Re-export
typealias Clock = com.singularity.todo.core.platform.Clock
typealias IdGenerator = com.singularity.todo.core.ids.IdGenerator
