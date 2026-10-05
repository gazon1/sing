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

    /**
     * The account was created, but its address has to be confirmed before it can be
     * used — so there is no session, and the user has something to do about it.
     *
     * This is a success and not a failure, which is why it is a state rather than an
     * [com.singularity.todo.feature.auth.AuthUiEvent.Error]: the account exists, and a
     * user told the sign-up failed will retype an address that is already registered.
     * It is also not [AuthUiState.Success], because Success means the user is in, and
     * navigating them into the application with no session leaves them looking at a task
     * list that cannot be saved and cannot say why.
     */
    data object AwaitingEmailConfirmation : AuthUiState
}
