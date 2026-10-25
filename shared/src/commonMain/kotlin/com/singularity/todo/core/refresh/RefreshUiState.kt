package com.singularity.todo.core.refresh

/**
 * Whether a refresh is running.
 *
 * The result of a refresh is not state: it is an event, delivered once. State
 * that holds "the last refresh moved three tasks" would have to be cleared by
 * someone, and nobody is — the screen that asked for the refresh is the one
 * that reports it.
 */
sealed interface RefreshUiState {
    /** No refresh is running. */
    data object Idle : RefreshUiState

    /** A refresh is running; the gesture shows its indication. */
    data object Refreshing : RefreshUiState
}
