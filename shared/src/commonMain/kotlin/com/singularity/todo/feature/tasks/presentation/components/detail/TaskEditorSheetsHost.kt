package com.singularity.todo.feature.tasks.presentation.components.detail

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.core.ui.components.sheet.DatePickerSheet
import com.singularity.todo.core.ui.components.sheet.TimePickerSheet
import com.singularity.todo.feature.attachments.components.AttachmentsSheet
import com.singularity.todo.feature.checklist.components.ChecklistEditorSheet
import com.singularity.todo.feature.tags.components.TagsPickerSheet
import com.singularity.todo.feature.tasks.presentation.components.ProjectPickerSheet
import com.singularity.todo.feature.tasks.presentation.components.TaskEditorSheetHost
import com.singularity.todo.feature.tasks.presentation.state.TaskEditorSheet
import com.singularity.todo.feature.timetracking.presentation.components.TimeEntryEditorSheet
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
 *
 * @param now the moment the sheets are rendered in. Required (#91): the time-entry
 *   sheet prefills its end field with "now", and reading that inside the sheet made
 *   the default a property of the host's wall clock that no test could set.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskEditorSheetsHost(
    model: TaskEditorModel,
    callbacks: TaskEditorCallbacks,
    activeSheet: TaskEditorSheet?,
    now: kotlin.time.Instant,
    onSheetDismiss: () -> Unit,
) {
    when (activeSheet) {
        // ── Date/Time sheets ────────────────────────────────────────────────
        is TaskEditorSheet.Date -> DatePickerSheet(
            initialDate = model.dueDate,
            onDateSelected = { date ->
                callbacks.dueDate.onChangeDate.invoke(date)
                onSheetDismiss()
            },
            onDismiss = onSheetDismiss,
        )

        is TaskEditorSheet.Time -> TimePickerSheet(
            initialTime = model.dueTime,
            onTimeSelected = { time ->
                callbacks.dueDate.onChangeTime.invoke(time)
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
                    callbacks.priority.onChange.invoke(p)
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
            testTagConfirm = TestTags.SHEET_CONFIRM,
        ) {
            RecurrencePickerSheet(
                currentSpec = model.recurrence,
                anchorDate = model.dueDate
                    ?: now.toLocalDateTime(TimeZone.currentSystemDefault()).date,
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
                onAttachFile = callbacks.attachments?.onAttachFile,
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

        // ── Estimate ────────────────────────────────────────────────────────
        is TaskEditorSheet.Estimate -> TaskEditorSheetHost(
            title = "Estimate",
            onClose = onSheetDismiss,
        ) {
            TaskEditorEstimateSheet(
                selected = model.estimateMinutes,
                onSelect = { minutes ->
                    callbacks.estimate?.onChange?.invoke(minutes)
                    onSheetDismiss()
                },
            )
        }

        // ── Time Entry ───────────────────────────────────────────────────────
        is TaskEditorSheet.TimeEntry -> TimeEntryEditorSheet(
            taskStartedAtMs = model.taskStartedAtMs,
            // The end of a new entry is "now". Passed in rather than read inside,
            // so a host that supplies a fixed clock produces a fixed default (#91).
            now = now,
            onSave = { startedAtMs, endedAtMs, kind, note ->
                callbacks.onTimeEntrySave?.invoke(startedAtMs, endedAtMs, kind, note)
                onSheetDismiss()
            },
            onDismiss = onSheetDismiss,
        )

        null -> {
            /* no sheet open */
        }
    }
}
