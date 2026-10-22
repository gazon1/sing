package com.singularity.todo.feature.tasks.presentation.components.detail

import com.singularity.todo.core.attachments.Attachment
import com.singularity.todo.feature.checklist.ChecklistItem
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.tags.TagId
import com.singularity.todo.feature.tasks.domain.model.RecurrenceSpec
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.model.TaskPriority
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime

/**
 * Data model for [TaskEditorContent].
 *
 * Passed as a single parameter instead of 20+ individual fields.
 * Represents the current state of all editable attributes.
 *
 * In View mode, initialize from [TaskDetailUi][com.singularity.todo.feature.tasks.presentation.state.TaskDetailUi].
 * In Create mode, initialize from the create draft.
 *
 * The title and the description are **not** here: they are bridged fields, and the kind of
 * their source — static or owned — is a compile-time property of the editor's parameter. A
 * field of kind `TextSource` here would erase that kind and make the editor decide it at
 * runtime, which is what the split exists to prevent.
 *
 * @param taskId null in Create mode, non-null in View mode. Used by sheets to load sub-data (checklist, attachments).
 * @param priority Current priority.
 * @param dueDate Current due date (null = not set).
 * @param dueTime Current due time (null = not set).
 * @param startDate Current start date (null = not set).
 * @param startTime Current start time (null = not set).
 * @param project Project ID, or null for "no project".
 * @param tags List of tag IDs.
 * @param checklist List of checklist items.
 * @param attachments List of attachments.
 * @param recurrence Current recurrence rule (null = not repeating).
 * @param isPinned Whether the task is pinned.
 * @param blockedBy Set of task IDs this task depends on.
 * @param availableTasks Tasks available for dependency picker (reactive).
 */
data class TaskEditorModel(
    val taskId: TaskId? = null,
    val priority: TaskPriority = TaskPriority.None,
    val dueDate: LocalDate? = null,
    /**
     * The dates of the work, when it is scheduled on several of them.
     *
     * Empty means "one date" — and then [dueDate] is that date. The list exists
     * because the row has to *show* that there is more than one, and a single
     * [LocalDate] field cannot say that.
     */
    val dueDates: List<LocalDate> = emptyList(),
    /**
     * Whether the date row may open the multi-date picker.
     *
     * Off by default, and on only where several dates make sense: the create
     * editor. The detail screen of an existing task keeps one date per task —
     * the dates of a series belong to its members, not to the task on screen.
     */
    val multiDateEnabled: Boolean = false,
    val dueTime: LocalTime? = null,
    val startDate: LocalDate? = null,
    val startTime: LocalTime? = null,
    val project: ProjectId? = null,
    val tags: List<TagId> = emptyList(),
    val checklist: List<ChecklistItem> = emptyList(),
    val attachments: List<Attachment> = emptyList(),
    val recurrence: RecurrenceSpec? = null,
    val isPinned: Boolean = false,
    val blockedBy: Set<TaskId> = emptySet(),
    val availableTasks: List<com.singularity.todo.feature.tasks.domain.model.Task> = emptyList(),
    /** Estimated time in minutes. Null means no estimate has been set. */
    val estimateMinutes: Int? = null,
    /** Task creation timestamp in epoch milliseconds — used as default for time entry start. */
    val taskStartedAtMs: Long? = null,
)
