package com.singularity.todo.feature.auth

import androidx.compose.runtime.Immutable

/**
 * UI state for the authentication screen.
 *
 * [SignedOutWithNoServer] is separate from [Idle] because they call for opposite
 * behaviour. Idle means "nothing has happened yet, offer the form". No server
 * means the form cannot work at all — a sign-in with no project configured fails
 * with a network error that reads like the user's password was wrong — so the
 * screen shows the configuration section first and only then the credentials.
 */
@Immutable
sealed interface AuthUiState {
    data object Idle : AuthUiState
    data object Loading : AuthUiState
    data object Success : AuthUiState

    /** No Supabase project has been configured yet, so there is nothing to sign in to. */
    data object SignedOutWithNoServer : AuthUiState
}
