package com.singularity.todo.feature.projects.presentation.state

/**
 * What the project brief is doing, for the sheet that shows it.
 *
 * The brief is generated on demand and shown in a sheet, so the sheet has three
 * states to draw: waiting for the model, the review itself, and the reason there
 * is no review.
 */
sealed interface ProjectBriefUiState {
    /** No brief was asked for. */
    data object Hidden : ProjectBriefUiState

    /** The brief is being generated. */
    data object Loading : ProjectBriefUiState

    /** The brief, as the model wrote it. */
    data class Ready(val text: String) : ProjectBriefUiState

    /** There is no brief: the model failed, or AI is not configured. */
    data class Failed(val message: String) : ProjectBriefUiState
}
