package com.singularity.todo.feature.auth

import com.singularity.todo.core.ui.MviEvent

/**
 * One-shot events emitted by [AuthViewModel].
 */
sealed interface AuthUiEvent : MviEvent {
    /** Navigate to the main app screen. */
    data object NavigateToHome : AuthUiEvent
    data class Error(val message: String) : AuthUiEvent
}
