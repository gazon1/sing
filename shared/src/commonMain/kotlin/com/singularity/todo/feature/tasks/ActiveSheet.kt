package com.singularity.todo.feature.tasks

/**
 * Marks which bottom sheet / dialog is currently open on the task detail screen.
 * Produced by [TaskDetailUiEvent.toActiveSheet].
 */
sealed interface ActiveSheet {
    data object Date : ActiveSheet
    data object Time : ActiveSheet
    data object Priority : ActiveSheet
    data object Project : ActiveSheet
    data object Tags : ActiveSheet
    data object Reminder : ActiveSheet
    data object Attachment : ActiveSheet
    data object Kind : ActiveSheet
    data object ConfirmDelete : ActiveSheet
    data object ConfirmArchive : ActiveSheet
}

/**
 * Maps a [TaskDetailUiEvent] to the corresponding [ActiveSheet], or null if no sheet.
 */
fun TaskDetailUiEvent.toActiveSheet(): ActiveSheet? = when {
    this is TaskDetailUiEvent.OpenDatePicker -> ActiveSheet.Date
    this is TaskDetailUiEvent.OpenTimePicker -> ActiveSheet.Time
    this is TaskDetailUiEvent.OpenPrioritySheet -> ActiveSheet.Priority
    this is TaskDetailUiEvent.OpenProjectSheet -> ActiveSheet.Project
    this is TaskDetailUiEvent.OpenTagSheet -> ActiveSheet.Tags
    this is TaskDetailUiEvent.OpenReminderSheet -> ActiveSheet.Reminder
    this is TaskDetailUiEvent.OpenAttachmentSheet -> ActiveSheet.Attachment
    this is TaskDetailUiEvent.OpenKindSheet -> ActiveSheet.Kind
    this is TaskDetailUiEvent.ConfirmDelete -> ActiveSheet.ConfirmDelete
    this is TaskDetailUiEvent.ConfirmArchive -> ActiveSheet.ConfirmArchive
    else -> null
}
