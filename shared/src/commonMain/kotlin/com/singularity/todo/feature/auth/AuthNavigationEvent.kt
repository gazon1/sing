package com.singularity.todo.feature.auth

import com.singularity.todo.core.ui.MviEvent

/**
 * Navigation events emitted by [AuthViewModel].
 *
 * These events signal a destination change that the composable handles by calling
 * the navigator. They are separated from [AuthUiEvent] so callers that only need
 * navigation can import this interface without pulling in the full event set.
 *
 * The [NavigateToHome] object is shared with [AuthUiEvent] — both interfaces
 * reference the same implementation. At runtime the instance is one object that
 * satisfies both types.
 */
sealed interface AuthNavigationEvent : MviEvent {
    data object NavigateToHome : AuthNavigationEvent
}

/** Shared implementation referenced by both [AuthNavigationEvent] and [AuthUiEvent]. */
internal object AuthNavigateHome : AuthNavigationEvent
