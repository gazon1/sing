package com.singularity.todo.core.sync

/**
 * One-shot events emitted by [SyncConfigScreen]'s ViewModel.
 * Consumed via Channel + repeatOnLifecycle(STARTED).
 */
sealed interface SyncFormEvent {
    data object Saved : SyncFormEvent
    data object AlreadyExists : SyncFormEvent
    data object TestConnection : SyncFormEvent
    data class Error(val cause: Throwable) : SyncFormEvent
}
