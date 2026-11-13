package com.singularity.todo.feature.tasks.presentation.components.detail

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.core.ui.components.sheet.DatePickerSheet
import com.singularity.todo.core.ui.components.sheet.MultiDatePickerSheet
import com.singularity.todo.core.ui.components.sheet.TimePickerSheet
import com.singularity.todo.feature.attachments.components.AttachmentsSheet
import com.singularity.todo.feature.checklist.components.ChecklistEditorSheet
import com.singularity.todo.feature.tags.components.TagsPickerSheet
import com.singularity.todo.feature.tasks.domain.logic.TaskSeriesPlanner
import com.singularity.todo.feature.tasks.domain.model.DependencyVerb
import com.singularity.todo.feature.tasks.domain.state.ScheduleChange
import com.singularity.todo.feature.tasks.domain.state.TaskDetailIntent
import com.singularity.todo.feature.tasks.domain.state.TaskEditorSheet
import com.singularity.todo.feature.tasks.presentation.components.ProjectPickerSheet
import com.singularity.todo.feature.tasks.presentation.components.TaskEditorSheetHost
import com.singularity.todo.feature.timetracking.presentation.components.TimeEntryEditorSheet
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.collections.immutable.toImmutableSet
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.stringResource
import singularity_todo.shared.generated.resources.Res
import singularity_todo.shared.generated.resources.priority

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
    isCompleted: Boolean = false,
) {
    when (activeSheet) {
        // ── Date/Time sheets ────────────────────────────────────────────────
        //
        // The date sheet has two shapes. Where several dates make sense — the
        // create editor — it opens the multi-date picker; everywhere else it
        // opens the ordinary one. The switch is [TaskEditorModel.multiDateEnabled]
        // and not the presence of a callback: the callback says *where the result
        // goes*, the flag says *what the user is allowed to choose*.
        is TaskEditorSheet.Date,
        is TaskEditorSheet.Time,
        is TaskEditorSheet.StartDate,
        is TaskEditorSheet.StartTime,
        -> {
            TaskEditorDateSheets(
                model = model,
                callbacks = callbacks,
                activeSheet = activeSheet,
                now = now,
                onSheetDismiss = onSheetDismiss,
            )
        }

        // ── Planning ─────────────────────────────────────────────────────────
        //
        // The deadline and the reminder are facts of their own, and each opens
        // the surface that edits it: the deadline opens the planning sheet on
        // the deadline, the reminder opens the reminder sheet.
        is TaskEditorSheet.Deadline -> {
            val schedule = callbacks.schedule
            if (schedule != null) {
                TaskSchedulingSheetHost(
                    model = model,
                    schedule = schedule,
                    initialTarget = SchedulingTarget.DEADLINE,
                    now = now,
                    onSheetDismiss = onSheetDismiss,
                )
            }
        }

        is TaskEditorSheet.Reminder -> {
            TaskReminderSheet(
                currentState = model.reminderState,
                hasDueDate = model.dueDate != null,
                notificationsEnabled = model.notificationsEnabled,
                onSelect = { offset -> callbacks.reminder?.onSet?.invoke(offset) },
                onClear = { callbacks.reminder?.onClear?.invoke() },
                onRequestPermission = callbacks.onRequestNotificationPermission,
                onOpenSettings = callbacks.onOpenNotificationSettings,
                onDismiss = onSheetDismiss,
            )
        }

        // ── Priority ─────────────────────────────────────────────────────────
        is TaskEditorSheet.Priority -> {
            TaskEditorSheetHost(
                title = stringResource(Res.string.priority),
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
        }

        // ── Workflow state ──────────────────────────────────────────────────
        is TaskEditorSheet.WorkflowState -> {
            TaskEditorSheetHost(
                title = "State",
                onClose = onSheetDismiss,
            ) {
                TaskEditorStateSheet(
                    selected = model.workflowState,
                    enabled = !isCompleted,
                    onSelect = { state ->
                        callbacks.workflowState?.onChange?.invoke(state)
                        onSheetDismiss()
                    },
                )
            }
        }

        // ── Project ─────────────────────────────────────────────────────────
        is TaskEditorSheet.Project -> {
            TaskEditorSheetHost(
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
        }

        // ── Tags ──────────────────────────────────────────────────────────────
        is TaskEditorSheet.Tags -> {
            TaskEditorSheetHost(
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
        }

        // ── Recurrence ──────────────────────────────────────────────────────
        //
        // The picker brings its own chrome — the title, the × and the ✓ that commits
        // the rule — because the rule it commits is a *pending* choice: the user picks
        // a type, and the ✓ is what turns that choice into a rule. The host that
        // wraps the other sheets has no ✓ at all, because those sheets apply their
        // choice the moment it is made.
        //
        // Wrapping the picker in that host gave the sheet two headers and two sheets,
        // and left the ✓ untagged: `sheet_confirm` was declared in the tag registry
        // and tapped by the Maestro flow, but no element ever carried it.
        is TaskEditorSheet.Recurrence -> {
            TaskEditorRecurrenceSheet(
                model = model,
                callbacks = callbacks,
                now = now,
                onSheetDismiss = onSheetDismiss,
            )
        }

        // ── Checklist ────────────────────────────────────────────────────────
        is TaskEditorSheet.Checklist -> {
            TaskEditorSheetHost(
                title = "Checklist",
                onClose = onSheetDismiss,
                testTag = TestTags.CHECKLIST_SHEET,
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
        }

        // ── Attachments ───────────────────────────────────────────────────
        is TaskEditorSheet.Attachments -> {
            TaskEditorSheetHost(
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
        }

        // ── Dependencies / related tasks ────────────────────────────────────
        is TaskEditorSheet.Dependencies -> {
            val current =
                remember(model.blockedBy, model.followsUp) {
                    buildMap {
                        model.blockedBy.forEach { put(it, DependencyVerb.BLOCKS) }
                        model.followsUp.forEach { put(it, DependencyVerb.FOLLOWS_UP) }
                    }.toImmutableMap()
                }
            val taskId = model.taskId
            if (taskId != null) {
                RelatedTaskPickerSheet(
                    taskId = taskId,
                    existingRelationIds =
                        remember(current) { current.keys.toImmutableSet() },
                    onAdd = { id, verb ->
                        callbacks.dependencies?.onChange?.invoke(
                            (current + (id to verb)).toImmutableMap(),
                        )
                        onSheetDismiss()
                    },
                    onDismiss = onSheetDismiss,
                )
            } else {
                onSheetDismiss()
            }
        }

        // ── Estimate ────────────────────────────────────────────────────────
        is TaskEditorSheet.Estimate -> {
            TaskEditorSheetHost(
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
        }

        // ── Time Entry ───────────────────────────────────────────────────────
        is TaskEditorSheet.TimeEntry -> {
            TimeEntryEditorSheet(
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
        }

        null -> {
            // no sheet open
        }
    }
}

/**
 * The four date and time sheets of the editor.
 *
 * Extracted from the host's `when` so that the dispatcher stays a dispatcher:
 * the four of them are one family — a date, a time, and the same two for the
 * start of the work — and one family does not need four branches of its own in
 * the dispatcher of every sheet the editor can open.
 */
@Composable
private fun TaskEditorDateSheets(
    model: TaskEditorModel,
    callbacks: TaskEditorCallbacks,
    activeSheet: TaskEditorSheet?,
    now: kotlin.time.Instant,
    onSheetDismiss: () -> Unit,
) {
    when (activeSheet) {
        is TaskEditorSheet.Date -> {
            TaskEditorDateSheet(
                model = model,
                callbacks = callbacks,
                now = now,
                onSheetDismiss = onSheetDismiss,
            )
        }

        is TaskEditorSheet.Time -> {
            TimePickerSheet(
                initialTime = model.dueTime,
                onTimeSelected = { time ->
                    callbacks.dueDate.onChangeTime.invoke(time)
                    onSheetDismiss()
                },
                onDismiss = onSheetDismiss,
            )
        }

        is TaskEditorSheet.StartDate -> {
            DatePickerSheet(
                initialDate = model.startDate,
                onDateSelected = { date ->
                    callbacks.startDate?.onChangeDate?.invoke(date)
                    onSheetDismiss()
                },
                onDismiss = onSheetDismiss,
            )
        }

        is TaskEditorSheet.StartTime -> {
            TimePickerSheet(
                initialTime = model.startTime,
                onTimeSelected = { time ->
                    callbacks.startDate?.onChangeTime?.invoke(time)
                    onSheetDismiss()
                },
                onDismiss = onSheetDismiss,
            )
        }

        else -> {
            // Another sheet: the dispatcher of the host owns it.
        }
    }
}

/**
 * The two shapes of the date sheet.
 *
 * Extracted from the host's `when` so the choice between the multi-date and the
 * ordinary picker does not add a branch to a function that already dispatches on
 * every sheet the editor can open.
 */
@Composable
private fun TaskEditorDateSheet(
    model: TaskEditorModel,
    callbacks: TaskEditorCallbacks,
    now: kotlin.time.Instant,
    onSheetDismiss: () -> Unit,
) {
    if (model.multiDateEnabled) {
        MultiDatePickerSheet(
            initialDates =
                remember(model.dueDates, model.dueDate) {
                    model.dueDates
                        .ifEmpty { model.dueDate?.let { persistentListOf(it) } ?: persistentListOf() }
                        .toImmutableSet()
                },
            onConfirm = { dates ->
                callbacks.dueDate.onChangeDates?.invoke(dates)
                onSheetDismiss()
            },
            onDismiss = onSheetDismiss,
            today = now.toLocalDateTime(TimeZone.currentSystemDefault()).date,
            maxDates = TaskSeriesPlanner.MAX_DATES,
        )
    } else {
        val schedule = callbacks.schedule
        if (schedule != null) {
            TaskSchedulingSheetHost(
                model = model,
                schedule = schedule,
                initialTarget = SchedulingTarget.DUE_DATE,
                now = now,
                onSheetDismiss = onSheetDismiss,
            )
        }
    }
}

/**
 * The planning sheet, and the write it causes.
 *
 * The schedule is written in one go: the facts that changed, and only those. A
 * sheet that wrote them one by one could leave the task halfway between what it
 * was and what the user asked for — and a sheet that wrote all of them would
 * write a fact the user never touched, which is how a deadline disappears
 * because somebody changed a time.
 */
@Composable
private fun TaskSchedulingSheetHost(
    model: TaskEditorModel,
    schedule: ScheduleCallbacks,
    initialTarget: SchedulingTarget,
    now: kotlin.time.Instant,
    onSheetDismiss: () -> Unit,
) {
    TaskSchedulingSheet(
        dueDate = model.dueDate,
        dueTime = model.dueTime,
        deadline = model.deadline,
        someday = model.someday,
        today = now.toLocalDateTime(TimeZone.currentSystemDefault()).date,
        initialTarget = initialTarget,
        onConfirm = { result ->
            val change =
                TaskDetailIntent.Domain.SetSchedule(
                    dueDate = changed(result.dueDate, model.dueDate),
                    dueTime = changed(result.dueTime, model.dueTime),
                    deadline = changed(result.deadline, model.deadline),
                    someday = changed(result.someday, model.someday),
                )
            // Nothing changed means nothing to write: the task already carries
            // what the sheet showed.
            if (change != UNCHANGED_SCHEDULE) {
                schedule.onChange(change)
            }
            onSheetDismiss()
        },
        onDismiss = onSheetDismiss,
    )
}

/**
 * The change of a fact — or [ScheduleChange.Unchanged] when the fact did not
 * change.
 */
private fun <T> changed(
    new: T,
    current: T,
): ScheduleChange<T> = if (new != current) ScheduleChange.Value(new) else ScheduleChange.Unchanged

/**
 * The change that changes nothing.
 *
 * Every fact of the schedule is left as it is, so there is nothing to write.
 */
private val UNCHANGED_SCHEDULE = TaskDetailIntent.Domain.SetSchedule()

/**
 * The recurrence sheet and its anchor date.
 *
 * The anchor falls back to today, and that fallback is a branch; it lives here
 * rather than in the dispatcher so the dispatcher stays a dispatcher.
 */
@Composable
private fun TaskEditorRecurrenceSheet(
    model: TaskEditorModel,
    callbacks: TaskEditorCallbacks,
    now: kotlin.time.Instant,
    onSheetDismiss: () -> Unit,
) {
    RecurrencePickerSheet(
        currentSpec = model.recurrence,
        anchorDate = model.dueDate ?: now.toLocalDateTime(TimeZone.currentSystemDefault()).date,
        onApply = { spec ->
            callbacks.recurrence?.onChange?.invoke(spec)
            onSheetDismiss()
        },
        onDismiss = onSheetDismiss,
    )
}
