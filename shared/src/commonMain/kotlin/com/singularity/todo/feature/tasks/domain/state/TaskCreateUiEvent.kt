package com.singularity.todo.feature.tasks.domain.state

import com.singularity.todo.core.ui.MviEvent

/**
 * One-shot events emitted by [com.singularity.todo.feature.tasks.presentation.viewmodel.TaskCreateViewModel].
 *
 * Failures are not events here. A validation failure belongs to the field that is
 * invalid, and a persistence failure belongs to `DraftUiState.error`, where the
 * screen already renders it — an event carrying the same news was a second channel
 * for one message, and only one of the two ever learned about a new failure kind.
 */
sealed interface TaskCreateUiEvent : MviEvent {
    /** Save succeeded — navigate back. */
    data object Saved : TaskCreateUiEvent
}
