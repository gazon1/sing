package com.singularity.todo.feature.tasks

/**
 * One-shot events emitted by [TasksViewModel].
 *
 * Replaces the global [com.singularity.todo.core.ui.components.UiEvent] variants
 * that previously carried "AI Result" text with typed, self-documenting events.
 */
sealed interface TasksUiEvent {
    /** AI action returned a result to show the user. */
    data class AiResult(val text: String) : TasksUiEvent

    /** A generic error occurred. */
    data class Error(val message: String) : TasksUiEvent

    /** Navigate back to the previous screen. */
    data object NavigateBack : TasksUiEvent
}
