package com.singularity.todo.feature.auth

import com.singularity.todo.core.auth.AuthRepository
import com.singularity.todo.core.auth.Session
import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.ui.MviIntent
import com.singularity.todo.core.ui.MviViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Intents for the auth screen.
 */
sealed interface AuthIntent : MviIntent {
    data class SignIn(val email: String, val password: String) : AuthIntent
    data class SignUp(val email: String, val password: String) : AuthIntent
    data object SignInAnonymously : AuthIntent
    data object SignOut : AuthIntent
    data object ResetState : AuthIntent
}

/**
 * Auth screen ViewModel (sign in / sign up / anonymous).
 *
 * Owns: sign-in/sign-up form state, authentication session.
 * Triggers: sign-in, sign-up, sign-out anonymous.
 * One-shot events: [AuthUiEvent.NavigateToHome], [AuthUiEvent.Error].
 *
 * @see AuthUiState
 */
class AuthViewModel(
    private val authRepository: AuthRepository,
    scope: AutoCloseableCoroutineScope = AutoCloseableCoroutineScope(),
) : MviViewModel<AuthUiState, AuthIntent, AuthUiEvent>(
        initialState = AuthUiState.Idle,
        scope = scope,
    ) {
    val session: StateFlow<Session> = authRepository.currentSession

    override fun onIntent(intent: AuthIntent) {
        when (intent) {
            is AuthIntent.SignIn -> signIn(intent.email, intent.password)
            is AuthIntent.SignUp -> signUp(intent.email, intent.password)
            AuthIntent.SignInAnonymously -> signInAnonymously()
            AuthIntent.SignOut -> signOut()
            AuthIntent.ResetState -> updateState { AuthUiState.Idle }
        }
    }

    private fun signIn(email: String, password: String) {
        vmScope.launch {
            updateState { AuthUiState.Loading }
            authRepository.signIn(email, password).fold(
                onSuccess = {
                    updateState { AuthUiState.Success }
                    emit(AuthUiEvent.NavigateToHome)
                },
                onFailure = {
                    updateState { AuthUiState.Idle }
                    emit(AuthUiEvent.Error(it.message ?: "Sign in failed"))
                },
            )
        }
    }

    private fun signUp(email: String, password: String) {
        vmScope.launch {
            updateState { AuthUiState.Loading }
            authRepository.signUp(email, password).fold(
                onSuccess = {
                    updateState { AuthUiState.Success }
                    emit(AuthUiEvent.NavigateToHome)
                },
                onFailure = {
                    updateState { AuthUiState.Idle }
                    emit(AuthUiEvent.Error(it.message ?: "Sign up failed"))
                },
            )
        }
    }

    private fun signInAnonymously() {
        vmScope.launch {
            updateState { AuthUiState.Loading }
            authRepository.signInAnonymously().fold(
                onSuccess = {
                    updateState { AuthUiState.Success }
                    emit(AuthUiEvent.NavigateToHome)
                },
                onFailure = {
                    updateState { AuthUiState.Idle }
                    emit(AuthUiEvent.Error(it.message ?: "Failed"))
                },
            )
        }
    }

    private fun signOut() {
        vmScope.launch {
            authRepository.signOut()
        }
    }
}
