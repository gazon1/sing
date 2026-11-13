package com.singularity.todo.feature.tasks.domain.state

/**
 * Represents the currently open bottom sheet in the task editor.
 */
sealed interface TaskEditorSheet {
    // ── Date/Time ───────────────────────────────────────────────────────────────
    data object Date : TaskEditorSheet

    data object Time : TaskEditorSheet

    data object StartDate : TaskEditorSheet

    data object StartTime : TaskEditorSheet

    /**
     * The deadline of the task.
     *
     * The same sheet as [Date], opened on the deadline instead of on the due
     * date: the two are independent facts, and one sheet that edits both of
     * them would need two calendars.
     */
    data object Deadline : TaskEditorSheet

    /**
     * The reminder of the task.
     *
     * A sheet of its own: a reminder is an offset before the due moment, not a
     * date, and it needs the notification permission — none of which the date
     * sheet has any business knowing about.
     */
    data object Reminder : TaskEditorSheet

    // ── Attribute pickers ──────────────────────────────────────────────────────
    data object Priority : TaskEditorSheet

    data object WorkflowState : TaskEditorSheet

    data object Project : TaskEditorSheet

    data object Tags : TaskEditorSheet

    data object Recurrence : TaskEditorSheet

    // ── Complex sections ───────────────────────────────────────────────────────
    data object Checklist : TaskEditorSheet

    data object Attachments : TaskEditorSheet

    data object Dependencies : TaskEditorSheet

    data object Estimate : TaskEditorSheet

    data object TimeEntry : TaskEditorSheet

    /**
     * The `+ Ещё` sheet — the list of the properties that are not on the screen.
     *
     * A sheet of its own rather than a menu: the list is derived from the task's
     * state, and a row that has just been picked leaves the list.
     */
    data object MoreParameters : TaskEditorSheet
}
