package com.singularity.todo.feature.tasks.presentation.state

import androidx.compose.runtime.Immutable
import com.singularity.todo.core.ui.textfields.FieldOwnedText
import com.singularity.todo.core.ui.textfields.FieldOwnedTextAsString
import com.singularity.todo.core.ui.textfields.StaticText
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
 *
 * The title and the description are [FieldOwnedText]s, so every write to them is named: the
 * field's own text, a seed (the draft as it was stored, a section's prefill), or a clear.
 */
@Serializable
@Immutable
data class TaskDraft(
    @Serializable(with = FieldOwnedTextAsString::class)
    val title: FieldOwnedText = FieldOwnedText.empty(),
    @Serializable(with = FieldOwnedTextAsString::class)
    val description: FieldOwnedText = FieldOwnedText.empty(),
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
) {
    /**
     * The title as the field's source.
     *
     * The draft is seeded once — from the store, or from a section's prefill — and
     * after that the field's own keystrokes are what it holds. A replacement therefore
     * lands on a value the field never produced, the field's echo guard needs no
     * lifting, and there is no counter to read.
     *
     * The promise is in the type of the value, not in this sentence: the title is a
     * [FieldOwnedText], and the only ways to write one are the field's own text, a seed
     * and a clear.
     */
    val titleSource: StaticText get() = StaticText(title.text)

    /**
     * The description as the field's source; see [titleSource] for why no counter is read
     * and for the promise that lives in the type of the value.
     */
    val descriptionSource: StaticText get() = StaticText(description.text)
}

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
