package com.singularity.todo.feature.projects.presentation.state

import com.singularity.todo.core.ui.components.Notification

/**
 * One-shot events emitted by [com.singularity.todo.feature.projects.presentation.viewmodel.ProjectsViewModel].
 */
sealed interface ProjectsUiEvent {
    /** AI project review returned a result. */
    data class ProjectReviewResult(val text: String) : ProjectsUiEvent

    /** A generic error. */
    data class Error(val message: String) : ProjectsUiEvent
}

fun ProjectsUiEvent.toNotification(): Notification = when (this) {
    is ProjectsUiEvent.ProjectReviewResult -> Notification.Text(title = "Project Review", text = text)
    is ProjectsUiEvent.Error -> Notification.Error(message)
}
