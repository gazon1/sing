package com.singularity.todo.feature.tasks.presentation.state

/**
 * Represents the currently open bottom sheet in the task editor.
 */
sealed interface TaskEditorSheet {
    data object Date : TaskEditorSheet
    data object Time : TaskEditorSheet
    data object Priority : TaskEditorSheet
    data object Dependencies : TaskEditorSheet
}
