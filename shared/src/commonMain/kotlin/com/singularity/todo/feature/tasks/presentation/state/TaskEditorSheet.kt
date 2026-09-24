package com.singularity.todo.feature.tasks.presentation.state

/**
 * Represents the currently open bottom sheet in the task editor.
 */
sealed interface TaskEditorSheet {
    // ── Date/Time ───────────────────────────────────────────────────────────────
    data object Date : TaskEditorSheet
    data object Time : TaskEditorSheet
    data object StartDate : TaskEditorSheet
    data object StartTime : TaskEditorSheet

    // ── Attribute pickers ──────────────────────────────────────────────────────
    data object Priority : TaskEditorSheet
    data object Project : TaskEditorSheet
    data object Tags : TaskEditorSheet
    data object Recurrence : TaskEditorSheet

    // ── Complex sections ───────────────────────────────────────────────────────
    data object Checklist : TaskEditorSheet
    data object Attachments : TaskEditorSheet
    data object Dependencies : TaskEditorSheet
}
