package com.singularity.todo.feature.projects.presentation.state

import com.singularity.todo.core.ui.components.Notification

/**
 * One-shot events emitted by [com.singularity.todo.feature.projects.presentation.viewmodel.ProjectEditorViewModel].
 */
sealed interface ProjectEditorUiEvent {
    data object NavigateBack : ProjectEditorUiEvent
}

fun ProjectEditorUiEvent.toNotification(): Notification = Notification.None
