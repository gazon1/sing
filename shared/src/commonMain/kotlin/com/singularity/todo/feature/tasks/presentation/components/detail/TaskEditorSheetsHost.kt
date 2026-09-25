package com.singularity.todo.feature.tasks.presentation.components.detail

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import com.singularity.todo.core.ui.components.DatePickerSheet
import com.singularity.todo.core.ui.components.TimePickerSheet
import com.singularity.todo.feature.attachments.components.AttachmentsSheet
import com.singularity.todo.feature.checklist.components.ChecklistEditorSheet
import com.singularity.todo.feature.tasks.presentation.components.ProjectPickerSheet
import com.singularity.todo.feature.tasks.presentation.components.TaskEditorSheetHost
import com.singularity.todo.feature.tags.components.TagsPickerSheet
import com.singularity.todo.feature.tasks.presentation.state.TaskEditorSheet
import kotlinx.datetime.Clock
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Hosts all bottom sheets for [TaskEditorContent].
 * Renders the sheet identified by [activeSheet].
 *
 * @param model Passed to sheets that need current values
 * @param callbacks Passed to sheets that need to write back changes
 * @param activeSheet The currently open sheet, or null if none
 * @param onSheetDismiss Called when a sheet is dismissed
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskEditorSheetsHost(
    model: TaskEditorModel,
    callbacks: TaskEditorCallbacks,
    activeSheet: TaskEditorSheet?,
    onSheetDismiss: () -> Unit,
) {
    when (activeSheet) {
        // ── Date/Time sheets ────────────────────────────────────────────────
        is TaskEditorSheet.Date -> DatePickerSheet(
            initialDate = model.dueDate,
            onDateSelected = { date ->
                callbacks.dueDate?.onChangeDate?.invoke(date)
                onSheetDismiss()
            },
            onDismiss = onSheetDismiss,
        )

        is TaskEditorSheet.Time -> TimePickerSheet(
            initialTime = model.dueTime,
            onTimeSelected = { time ->
                callbacks.dueDate?.onChangeTime?.invoke(time)
                onSheetDismiss()
            },
            onDismiss = onSheetDismiss,
        )

        is TaskEditorSheet.StartDate -> DatePickerSheet(
            initialDate = model.startDate,
            onDateSelected = { date ->
                callbacks.startDate?.onChangeDate?.invoke(date)
                onSheetDismiss()
            },
            onDismiss = onSheetDismiss,
        )

        is TaskEditorSheet.StartTime -> TimePickerSheet(
            initialTime = model.startTime,
            onTimeSelected = { time ->
                callbacks.startDate?.onChangeTime?.invoke(time)
                onSheetDismiss()
            },
            onDismiss = onSheetDismiss,
        )

        // ── Priority ─────────────────────────────────────────────────────────
        is TaskEditorSheet.Priority -> TaskEditorSheetHost(
            title = "Приоритет",
            onClose = onSheetDismiss,
        ) {
            TaskEditorPrioritySheet(
                selected = model.priority,
                onSelect = { p ->
                    callbacks.priority?.onChange?.invoke(p)
                    onSheetDismiss()
                },
            )
        }

        // ── Project ─────────────────────────────────────────────────────────
        is TaskEditorSheet.Project -> TaskEditorSheetHost(
            title = "Project",
            onClose = onSheetDismiss,
        ) {
            ProjectPickerSheet(
                selectedId = model.project,
                onSelect = { id ->
                    callbacks.project?.onChange?.invoke(id)
                    onSheetDismiss()
                },
                onDismiss = onSheetDismiss,
            )
        }

        // ── Tags ──────────────────────────────────────────────────────────────
        is TaskEditorSheet.Tags -> TaskEditorSheetHost(
            title = "Tags",
            onClose = onSheetDismiss,
        ) {
            TagsPickerSheet(
                selectedIds = model.tags,
                onSelect = { ids ->
                    callbacks.tags?.onChange?.invoke(ids)
                    onSheetDismiss()
                },
                onDismiss = onSheetDismiss,
            )
        }

        // ── Recurrence ──────────────────────────────────────────────────────
        is TaskEditorSheet.Recurrence -> TaskEditorSheetHost(
            title = "Repeat",
            onClose = onSheetDismiss,
        ) {
            RecurrencePickerSheet(
                currentSpec = model.recurrence,
                anchorDate = model.dueDate ?: kotlin.time.Clock.System.now().toLocalDateTime(kotlinx.datetime.TimeZone.currentSystemDefault()).date,
                onApply = { spec ->
                    callbacks.recurrence?.onChange?.invoke(spec)
                    onSheetDismiss()
                },
                onDismiss = onSheetDismiss,
            )
        }

        // ── Checklist ────────────────────────────────────────────────────────
        is TaskEditorSheet.Checklist -> TaskEditorSheetHost(
            title = "Checklist",
            onClose = onSheetDismiss,
        ) {
            ChecklistEditorSheet(
                taskId = model.taskId,
                items = model.checklist,
                onAdd = { callbacks.checklist?.onAdd?.invoke(it) },
                onToggle = { callbacks.checklist?.onToggle?.invoke(it) },
                onDelete = { callbacks.checklist?.onDelete?.invoke(it) },
                onDismiss = onSheetDismiss,
            )
        }

        // ── Attachments ───────────────────────────────────────────────────
        is TaskEditorSheet.Attachments -> TaskEditorSheetHost(
            title = "Attachments",
            onClose = onSheetDismiss,
        ) {
            AttachmentsSheet(
                taskId = model.taskId,
                attachments = model.attachments,
                onAddUrl = { url, title -> callbacks.attachments?.onAddUrl?.invoke(url, title) },
                onAttachFile = { callbacks.attachments?.onAttachFile?.invoke() },
                onDelete = { callbacks.attachments?.onDelete?.invoke(it) },
                onDismiss = onSheetDismiss,
            )
        }

        // ── Dependencies ────────────────────────────────────────────────────
        is TaskEditorSheet.Dependencies -> DependencyPickerSheet(
            currentDeps = model.dependsOn,
            availableTasks = model.availableTasks,
            onApply = { newDeps ->
                callbacks.dependencies?.onChange?.invoke(newDeps)
                onSheetDismiss()
            },
            onDismiss = onSheetDismiss,
        )

        null -> { /* no sheet open */ }
    }
}
