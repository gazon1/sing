package com.singularity.todo.core.sync

/**
 * One-shot events emitted by the sync config ViewModel.
 * Consumed via Channel + repeatOnLifecycle(STARTED).
 */
sealed interface SyncFormEvent {
    data object Saved : SyncFormEvent
    data object AlreadyExists : SyncFormEvent
    data object TestConnection : SyncFormEvent
    data class Error(val cause: Throwable) : SyncFormEvent
}
