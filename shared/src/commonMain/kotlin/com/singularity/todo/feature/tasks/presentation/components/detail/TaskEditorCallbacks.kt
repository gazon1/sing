package com.singularity.todo.feature.tasks.presentation.components.detail

import androidx.compose.runtime.Composable
import com.singularity.todo.core.attachments.AttachmentId
import com.singularity.todo.core.reminders.ReminderOffset
import com.singularity.todo.feature.checklist.ChecklistItemId
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.tags.TagId
import com.singularity.todo.feature.tasks.domain.model.DependencyVerb
import com.singularity.todo.feature.tasks.domain.model.RecurrenceSpec
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.model.TaskPriority
import com.singularity.todo.feature.tasks.domain.model.TaskWorkflowState
import com.singularity.todo.feature.tasks.domain.state.TaskDetailIntent
import com.singularity.todo.feature.timetracking.domain.TimeEntryKind
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime

/**
 * Base callback bundle for a single-value attribute row (priority, project, tags, recurrence, dependencies).
 * `null` means the row is hidden entirely.
 *
 * @param onChange called when the value changes (e.g. priority selected, project set)
 * @param onClick overrides the row's own behaviour (e.g. opening a picker). Null — the
 *        default — lets the row open its own sheet, which is what almost every call
 *        site wants. It must NOT default to an empty lambda: a no-op lambda is a
 *        supplied value, so the row's `onClick ?: openOwnSheet` fallback never
 *        fires and the row silently does nothing.
 * @param onClear called when the X/clear button is tapped. Null means no clear button shown.
 */
data class RowCallbacks<T>(
    val onChange: (T) -> Unit,
    val onClick: (() -> Unit)? = null,
    val onClear: (() -> Unit)? = null,
)

/**
 * Callback bundle for date+time rows (Due Date, Start Date).
 * `null` for the whole bundle means the row is hidden entirely.
 *
 * [onClick] is nullable for the same reason as in [RowCallbacks]: a no-op default would
 * suppress the row's own date/time picker.
 */
data class DateRowCallbacks(
    val onChangeDate: (LocalDate?) -> Unit,
    val onChangeTime: (LocalTime?) -> Unit,
    val onClick: (() -> Unit)? = null,
    val onClear: (() -> Unit)? = null,
    /**
     * Sets several dates at once.
     *
     * `null` means the row cannot take a set of dates — the single-date picker
     * is the only thing it opens. Not defaulted to a no-op lambda on purpose: a
     * no-op would be a *supplied* value, and the sheet host would then believe
     * the multi-date path is wired while the picker wrote nowhere.
     */
    val onChangeDates: ((Set<LocalDate>) -> Unit)? = null,
)

/**
 * Callback bundle for the reminder row.
 *
 * The reminder is not a date: it is an offset before the due moment, it has
 * its own editor, and it can only be written where the platform has a
 * notification scheduler. `null` for the whole bundle means the row is hidden.
 */
data class ReminderRowCallbacks(
    /** Sets (or replaces) the task's reminder. */
    val onSet: (ReminderOffset) -> Unit,
    /**
     * Removes the task's reminder.
     *
     * Null means the row draws no ✕ — the same meaning it has in every other bundle
     * here. A completed task is the caller that uses it: a finished task keeps its
     * reminder, and erasing one is not an action it offers.
     */
    val onClear: (() -> Unit)? = null,
    /**
     * Opens the reminder editor.
     *
     * `null` lets the row open it itself, which is what almost every call site
     * wants. It must NOT default to an empty lambda: a no-op lambda is a
     * supplied value, so the row's own fallback never fires.
     */
    val onClick: (() -> Unit)? = null,
)

/** Callback bundle for a toggle (checkbox) attribute. */
data class ToggleCallbacks(val onToggle: () -> Unit)

// ─── Completed tasks offer no clear affordance ───────────────────────────────
//
// A completed task is shown, not re-planned: its rows keep their values, but the ✕
// that erases one is not an action a finished task offers. Each `withoutClearWhen`
// returns the same bundle with its clear callback dropped, so the row draws no ✕ —
// the same thing null already means for these rows at their own call sites.

/** This bundle's clear dropped when [completed]; the row then draws no ✕. */
fun <T> RowCallbacks<T>.withoutClearWhen(completed: Boolean): RowCallbacks<T> =
    if (completed) copy(onClear = null) else this

/** This bundle's clear dropped when [completed]. */
fun DateRowCallbacks.withoutClearWhen(completed: Boolean): DateRowCallbacks =
    if (completed) copy(onClear = null) else this

/** This bundle's clear dropped when [completed]. */
fun ReminderRowCallbacks.withoutClearWhen(completed: Boolean): ReminderRowCallbacks =
    if (completed) copy(onClear = null) else this

/** This bundle's clear dropped when [completed]. */
fun DeadlineRowCallbacks.withoutClearWhen(completed: Boolean): DeadlineRowCallbacks =
    if (completed) copy(onClear = null) else this

/**
 * Callback bundle for the deadline row.
 *
 * The deadline is one of the schedule's facts, so it is *written* through
 * [ScheduleCallbacks]. What is left to the row is what is not a write:
 * opening the sheet that edits it, and clearing it.
 */
data class DeadlineRowCallbacks(
    /**
     * Opens the deadline sheet.
     *
     * `null` — the default — lets the row open its own sheet, which is what
     * almost every call site wants.
     */
    val onClick: (() -> Unit)? = null,
    /** Clears the deadline. Null means no clear button is shown. */
    val onClear: (() -> Unit)? = null,
)

/**
 * Callback bundle for the schedule — the due date and its time, the deadline and
 * the `someday` bucket.
 *
 * The four facts are one decision, so they are written in one go: [onChange]
 * carries the facts that changed *and* the facts that did not, and the write
 * lands on one copy of the task. A save that wrote them one by one could leave
 * the task halfway between what it was and what the user asked for.
 *
 * `null` for the whole bundle means the surface does not offer the schedule.
 */
data class ScheduleCallbacks(
    /** Writes the change of the schedule. */
    val onChange: (TaskDetailIntent.Domain.SetSchedule) -> Unit,
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
    /** `null` hides the "Add file" button — Create mode has no task to attach to yet. */
    val onAttachFile: (() -> Unit)? = null,
    val onDelete: (AttachmentId) -> Unit,
)

/**
 * A dropdown menu item for archive/delete actions in View mode.
 *
 * @param label   User-visible text. **Localised** — the app runs in Russian on
 *                the test device, so this is not a stable selector.
 * @param testTag Stable, non-localised id ([TestTags.EditorOverflow.*]) so UI
 *                automation can address the row regardless of the translated
 *                [label]. This, not [label], is what the row's testTag is built
 *                from: a selector derived from text a user can translate passes on
 *                a developer machine and fails on a device set to anything else.
 * @param onClick Invoked when the row is tapped.
 */
data class TaskEditorMenuItem(val label: String, val onClick: () -> Unit, val testTag: String? = null)

/**
 * All callbacks for [TaskEditorContent], grouped by attribute.
 * `null` for a bundle = that row is not shown.
 *
 * Reduces 17+ flat parameters to 10 focused bundles.
 */
data class TaskEditorCallbacks(
    val onBack: () -> Unit,
    /** Null hides the completion checkbox — Create mode has no task to complete yet. */
    val onCheckToggle: (() -> Unit)?,
    /** Priority row — null = hidden */
    @Suppress("NoEmptyOnClickLambda")
    val priority: RowCallbacks<TaskPriority> = RowCallbacks(onChange = {}),
    /** Due date + time row — null = hidden */
    @Suppress("NoEmptyOnClickLambda")
    val dueDate: DateRowCallbacks = DateRowCallbacks(onChangeDate = {}, onChangeTime = {}),
    /** Reminder row — null = hidden. */
    val reminder: ReminderRowCallbacks? = null,
    /** Deadline row — null = hidden. */
    val deadline: DeadlineRowCallbacks? = null,
    /**
     * The schedule of the task — null = the surface does not offer it.
     *
     * The due date and its time, the deadline and the `someday` bucket are one
     * decision, so they are written through this one bundle: a save that wrote
     * them one by one could leave the task halfway between what it was and what
     * the user asked for.
     */
    val schedule: ScheduleCallbacks? = null,
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
    /**
     * Dependencies row — null = hidden.
     *
     * The value is the whole outgoing relation map, keyed by the other task and
     * carrying the verb that says what the edge means. A bare `Set<TaskId>` could
     * not express "follows up on", so the picker would have to invent the verb at
     * the write site — which is where the two verbs overwrote each other.
     */
    val dependencies: RowCallbacks<Map<TaskId, DependencyVerb>>?,
    /** Workflow-state row — null = hidden. */
    val workflowState: RowCallbacks<TaskWorkflowState>? = null,
    /** Checklist row — null = hidden */
    val checklist: ChecklistCallbacks?,
    /** Attachments row — null = hidden */
    val attachments: AttachmentsCallbacks?,
    /** Estimate row — null = hidden. */
    val estimate: RowCallbacks<Int?>?,
    /**
     * Time entry — null means time entry row is hidden.
     * Called to open the time entry editor sheet. The sheet's onSave is wired separately
     * via [onTimeEntrySave].
     */
    val onTimeEntryAdd: (() -> Unit)?,
    /**
     * Called when the user saves a manual time entry from [TimeEntryEditorSheet].
     * Parameters: (startedAtMs, endedAtMs, kind, note).
     */
    val onTimeEntrySave: ((startedAtMs: Long, endedAtMs: Long, kind: TimeEntryKind, note: String?) -> Unit)?,
    /**
     * The offers the screen makes to the person: the first-run banner and the stack of
     * pending AI proposals.
     *
     * They have a slot of their own because their place on the screen is a decision, not
     * a side effect of what else the caller passes. Riding the same slot as the
     * checklist, the subtasks and the attachments put an offer a person is meant to act
     * on wherever that list happened to end, and the offer moved whenever the list did.
     *
     * The editor draws this slot directly under the title and the description, above the
     * attribute rows, for every task. Null means the screen makes no offer.
     */
    val bannerSections: (@Composable () -> Unit)? = null,
    /**
     * Sections that show the task's own state — the tags, the recurrence, the
     * checklist, the subtasks, the attachments, the backlinks, the series card, the
     * timer and the logbook — rendered after the attribute rows. Null means the editor
     * shows nothing else.
     *
     * After the rows, not under the title: the due date, the priority and the project
     * are what a person fills in, and content drawn above them pushes those fields
     * below the fold.
     */
    val extraSections: (@Composable () -> Unit)? = null,
    /** Bottom bar content — null means no bottom bar */
    val bottomBar: (@Composable () -> Unit)? = null,
    /** Overflow menu items — empty list = no menu shown */
    val menuItems: ImmutableList<TaskEditorMenuItem> = persistentListOf(),
    /** AI action button — null means AI button is hidden */
    val onAiClick: (() -> Unit)? = null,
    /**
     * Opens the app's notification settings.
     *
     * `null` means the platform has no such screen — the desktop app, where
     * the scheduler is missing anyway. The reminder editor offers this action
     * when the notification permission was refused, because a refusal with no
     * way out is a dead end.
     */
    val onOpenNotificationSettings: (() -> Unit)? = null,
    /**
     * Asks the system for the notification permission.
     *
     * `null` means the platform has no runtime permission to ask for.
     */
    val onRequestNotificationPermission: (() -> Unit)? = null,
)
