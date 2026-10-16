package com.singularity.todo.feature.auth

import com.singularity.todo.core.ui.MviEvent

/**
 * One-shot events emitted by [AuthViewModel].
 *
 * Navigation events are also exposed via [AuthNavigationEvent] so callers that
 * only need navigation can import that narrower interface.
 *
 * [NavigateToHome] is the shared [AuthNavigateHome] object, accessible as
 * [AuthUiEvent.NavigateToHome] for emit calls and as [AuthNavigationEvent.NavigateToHome]
 * for navigation-only callers.
 */
sealed interface AuthUiEvent :
    MviEvent,
    AuthNavigationEvent {
    data object NavigateToHome : AuthUiEvent, AuthNavigationEvent by AuthNavigateHome
    data class Error(val message: String) : AuthUiEvent

    /**
     * Something the user has to act on that is not a failure.
     *
     * Distinct from [Error] because the screen paints that one in the error colour,
     * and telling someone in red that their account was created is its own small
     * wrong: it reads as the sign-up having failed, which is the one thing it did
     * not do, and the user retypes an address that is already registered.
     */
    data class Message(val message: String) : AuthUiEvent
}
