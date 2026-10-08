package com.singularity.todo.feature.notes.presentation.screen

import com.singularity.todo.core.ui.components.Notification
import com.singularity.todo.feature.notes.NotesUiEvent

/**
 * Maps [NotesUiEvent] to [Notification] for [NotificationHost].
 * Events that have no user-visible notification map to [Notification.None].
 */
fun NotesUiEvent.toNotification(): Notification = when (this) {
    is NotesUiEvent.AiResult -> Notification.Text(title = "AI Result", text = text)
    is NotesUiEvent.SaveFailed -> Notification.Error(message)
    is NotesUiEvent.Error -> Notification.Error(message)
    is NotesUiEvent.NavigateToEditor -> Notification.None
    NotesUiEvent.NavigateBack -> Notification.None
    NotesUiEvent.SavedPulse -> Notification.None
    is NotesUiEvent.UndoDelete -> Notification.None
}
