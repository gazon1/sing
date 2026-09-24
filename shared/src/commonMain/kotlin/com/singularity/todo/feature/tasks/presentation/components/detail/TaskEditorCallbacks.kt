package com.singularity.todo.feature.tasks.presentation.components.detail

import androidx.compose.runtime.Composable
import com.singularity.todo.core.attachments.AttachmentId
import com.singularity.todo.feature.checklist.ChecklistItemId
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.tags.TagId
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.model.TaskPriority
import com.singularity.todo.feature.tasks.domain.model.RecurrenceSpec
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime

/**
 * Base callback bundle for a single-value attribute row (priority, project, tags, recurrence, dependencies).
 * `null` means the row is hidden entirely.
 *
 * @param onChange called when the value changes (e.g. priority selected, project set)
 * @param onClear called when the X/clear button is tapped. Null means no clear button shown.
 */
data class RowCallbacks<T>(
    val onChange: (T) -> Unit,
    val onClick: () -> Unit = {},
    val onClear: (() -> Unit)? = null,
)

/**
 * Callback bundle for date+time rows (Due Date, Start Date).
 * `null` means the row is hidden entirely.
 */
data class DateRowCallbacks(
    val onChangeDate: (LocalDate?) -> Unit,
    val onChangeTime: (LocalTime?) -> Unit,
    val onClick: () -> Unit = {},
    val onClear: (() -> Unit)? = null,
)

/** Callback bundle for a toggle (checkbox) attribute. */
data class ToggleCallbacks(
    val onToggle: () -> Unit,
)

/** Callback bundle for the checklist section. `null` means checklist row is hidden. */
data class ChecklistCallbacks(
    val onOpen: () -> Unit,
    val onAdd: (String) -> Unit,
    val onToggle: (ChecklistItemId) -> Unit,
    val onDelete: (ChecklistItemId) -> Unit,
)

/** Callback bundle for the attachments section. `null` means attachments row is hidden. */
data class AttachmentsCallbacks(
    val onOpen: () -> Unit,
    val onAddUrl: (String, String?) -> Unit,
    val onAttachFile: () -> Unit,
    val onDelete: (AttachmentId) -> Unit,
)

/** A dropdown menu item for archive/delete actions in View mode. */
data class TaskEditorMenuItem(val label: String, val onClick: () -> Unit)

/**
 * All callbacks for [TaskEditorContent], grouped by attribute.
 * `null` for a bundle = that row is not shown.
 *
 * Reduces 17+ flat parameters to 10 focused bundles.
 */
data class TaskEditorCallbacks(
    val onBack: () -> Unit,
    val onTitleChange: (String) -> Unit,
    val onCheckToggle: () -> Unit,
    val onDescriptionChange: (String) -> Unit,
    /** Priority row — null = hidden */
    val priority: RowCallbacks<TaskPriority>?,
    /** Due date + time row — null = hidden */
    val dueDate: DateRowCallbacks?,
    /** Start date + time row — null = hidden */
    val startDate: DateRowCallbacks?,
    /** Project row — null = hidden */
    val project: RowCallbacks<ProjectId?>?,
    /** Tags row — null = hidden */
    val tags: RowCallbacks<List<TagId>>?,
    /** Recurrence row — null = hidden */
    val recurrence: RowCallbacks<RecurrenceSpec?>?,
    /** Pin/toggle row — null = hidden */
    val pin: ToggleCallbacks?,
    /** Dependencies row — null = hidden */
    val dependencies: RowCallbacks<Set<TaskId>>?,
    /** Checklist row — null = hidden */
    val checklist: ChecklistCallbacks?,
    /** Attachments row — null = hidden */
    val attachments: AttachmentsCallbacks?,
    /** Bottom bar content — null means no bottom bar */
    val bottomBar: (@Composable () -> Unit)? = null,
    /** Overflow menu items — empty list = no menu shown */
    val menuItems: List<TaskEditorMenuItem> = emptyList(),
)
