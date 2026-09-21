package com.singularity.todo.feature.auth

/**
 * One-shot events emitted by [AuthViewModel].
 */
sealed interface AuthUiEvent {
    /** Navigate to the main app screen. */
    data object NavigateToHome : AuthUiEvent
    data class Error(val message: String) : AuthUiEvent
}
