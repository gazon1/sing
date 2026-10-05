package com.singularity.todo.feature.auth

import com.singularity.todo.core.ui.MviEvent

/**
 * One-shot events emitted by [AuthViewModel].
 */
sealed interface AuthUiEvent : MviEvent {
    /** Navigate to the main app screen. */
    data object NavigateToHome : AuthUiEvent
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
