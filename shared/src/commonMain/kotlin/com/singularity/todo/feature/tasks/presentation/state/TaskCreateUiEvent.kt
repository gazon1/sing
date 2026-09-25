package com.singularity.todo.feature.tasks.presentation.state

import com.singularity.todo.core.ui.MviEvent

/**
 * One-shot events emitted by [com.singularity.todo.feature.tasks.presentation.viewmodel.TaskCreateViewModel].
 */
sealed interface TaskCreateUiEvent : MviEvent {
    /** Save succeeded — navigate back. */
    data object Saved : TaskCreateUiEvent

    /** Save failed with error message. */
    data class Error(val message: String) : TaskCreateUiEvent
}
