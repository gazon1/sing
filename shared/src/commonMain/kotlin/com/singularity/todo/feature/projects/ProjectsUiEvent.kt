package com.singularity.todo.feature.projects

/**
 * One-shot events emitted by [ProjectsViewModel].
 */
sealed interface ProjectsUiEvent {
    /** AI project review returned a result. */
    data class ProjectReviewResult(val text: String) : ProjectsUiEvent

    /** A generic error. */
    data class Error(val message: String) : ProjectsUiEvent
}
