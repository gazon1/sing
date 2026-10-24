package com.singularity.todo.feature.tasks.presentation.state

import com.singularity.todo.core.text.FieldOwnedText
import com.singularity.todo.core.text.FieldOwnedTextAsString
import com.singularity.todo.core.text.StaticText
import com.singularity.todo.feature.tasks.domain.model.RecurrenceSpec
import com.singularity.todo.feature.tasks.domain.model.TaskPriority
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.serialization.Serializable

/**
 * Immutable UI state of the task creation screen.
 *
 * ## Why there is no `@Immutable`, and why the file no longer imports Compose
 *
 * The annotation made the compiler compare this state by `equals`, and the
 * draft inside it holds three collections — an `equals` walked all of them on
 * every comparison.
 *
 * The draft is listed in the stability contract
 * (`config/compose/stability_config.conf`) instead: `kotlinx.serialization`
 * cannot serialize a persistent collection, so the draft cannot be given the
 * type that would make it stable *and* serializable. The contract is the
 * honest way to say it.
 *
 * That is also why this file no longer imports `androidx.compose.runtime`: a
 * domain-free presentation model has no business depending on the UI
 * toolkit, and the architecture test (`ArchitectureTest.kt`) records the one
 * place where a presentation type still reaches into the domain.
 */
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
data class TaskDraft(
    @Serializable(with = FieldOwnedTextAsString::class)
    val title: FieldOwnedText = FieldOwnedText.empty(),
    @Serializable(with = FieldOwnedTextAsString::class)
    val description: FieldOwnedText = FieldOwnedText.empty(),
    val priority: TaskPriority = TaskPriority.None,
    val dueDate: DueDateOption = DueDateOption.None,
    /**
     * The other dates of the same work, beyond the primary one in [dueDate].
     *
     * The primary date stays a [DueDateOption] so that "Today" and "Tomorrow" keep
     * meaning the day of the *save*, not the day the draft was first written: a
     * draft restored the next morning is still "today", not "yesterday". The extra
     * dates are concrete, because there is no relative form for them — they are
     * days the user picked.
     *
     * Empty means "one date" (or "no date", when [dueDate] is
     * [DueDateOption.None]).
     */
    val extraDueDates: List<LocalDate> = emptyList(),
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
data class DraftChecklistItem(val id: String, val text: String, val isChecked: Boolean = false)

/**
 * An attachment in a create-mode draft.
 * Uses plain fields instead of [com.singularity.todo.core.attachments.Attachment]
 * so the draft is self-contained and fully serializable.
 */
@Serializable
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
