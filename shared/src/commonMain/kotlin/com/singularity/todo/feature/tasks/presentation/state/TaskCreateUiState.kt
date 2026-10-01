package com.singularity.todo.feature.tasks.presentation.state

import androidx.compose.runtime.Immutable
import com.singularity.todo.feature.tasks.domain.model.RecurrenceSpec
import com.singularity.todo.feature.tasks.domain.model.TaskPriority
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.serialization.Serializable

/**
 * Immutable UI state of the task creation screen.
 */
@Immutable
data class TaskCreateUiState(
    val draft: TaskDraft = TaskDraft(),
    val isSaveEnabled: Boolean = false,
    val error: String? = null,
    val isDirty: Boolean = false,
    val isSaving: Boolean = false,
)

/**
 * Draft of a task being created.
 * Persisted via [DraftStore] across process death.
 *
 * Uses plain data classes instead of domain entities so the draft is fully
 * self-contained and serializable without depending on repository entity shapes.
 */
@Serializable
@Immutable
data class TaskDraft(
    val title: String = "",
    val description: String = "",
    val priority: TaskPriority = TaskPriority.None,
    val dueDate: DueDateOption = DueDateOption.None,
    val dueTime: LocalTime? = null,
    val startDate: DueDateOption = DueDateOption.None,
    val startTime: LocalTime? = null,
    val endDate: DueDateOption = DueDateOption.None,
    val endTime: LocalTime? = null,
    val accentColor: Long? = null,
    val emoji: String? = null,
    val projectId: String? = null,
    val tagIds: List<String> = emptyList(),
    val isPinned: Boolean = false,
    val recurrence: RecurrenceSpec? = null,
    val checklist: List<DraftChecklistItem> = emptyList(),
    val attachments: List<DraftAttachment> = emptyList(),
)

/**
 * A checklist item in a create-mode draft.
 * Uses plain fields instead of [com.singularity.todo.feature.checklist.ChecklistItem]
 * so the draft is self-contained and fully serializable.
 */
@Serializable
@Immutable
data class DraftChecklistItem(val id: String, val text: String, val isChecked: Boolean = false)

/**
 * An attachment in a create-mode draft.
 * Uses plain fields instead of [com.singularity.todo.core.attachments.Attachment]
 * so the draft is self-contained and fully serializable.
 */
@Serializable
@Immutable
data class DraftAttachment(val url: String, val title: String? = null)

/**
 * Due date selection options for the task creation editor.
 */
@Serializable
sealed interface DueDateOption {
    @Serializable data object None : DueDateOption

    @Serializable data object Today : DueDateOption

    @Serializable data object Tomorrow : DueDateOption

    @Serializable data class Custom(val date: LocalDate, val label: String) : DueDateOption
}
