package com.singularity.todo.feature.auth

import com.singularity.todo.core.auth.AuthRepository
import com.singularity.todo.core.auth.Session
import com.singularity.todo.core.auth.SupabaseClientProvider
import com.singularity.todo.core.auth.SupabaseConfig
import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.ui.MviIntent
import com.singularity.todo.core.ui.MviViewModel
import kotlinx.coroutines.flow.StateFlow
import com.singularity.todo.core.observability.CrashReportingPort
import com.singularity.todo.core.observability.NoOpCrashReportingPort
import com.singularity.todo.core.observability.reportingScope
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

    /**
     * Stores the Supabase project to talk to.
     *
     * Carries the URL and the **anon** key. The anon key ships inside every copy
     * of the app and is meant to be readable; the service-role key is not, and
     * asking for it here would be asking the user for a secret that has no place
     * on a device.
     */
    data class SaveServerConfig(val url: String, val anonKey: String) : AuthIntent
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
    private val clients: SupabaseClientProvider,
    /**
     * Runs once after a successful sign-in, to upload whatever the device already
     * held. A lambda rather than a dependency on the planner so this feature does
     * not reach into sync, and so a test can observe the call without standing up
     * six repositories.
     */
    private val onFirstSignIn: suspend () -> Unit = {},
    crashReporter: CrashReportingPort = NoOpCrashReportingPort(),
    scope: AutoCloseableCoroutineScope = reportingScope(crashReporter),
) : MviViewModel<AuthUiState, AuthIntent, AuthUiEvent>(
        initialState = AuthUiState.Idle,
        crashReporter = crashReporter,
        scope = scope,
    ) {
    val session: StateFlow<Session> = authRepository.currentSession

    init {
        // The screen opens on "no server" rather than on the credential form when
        // nothing is configured, because a sign-in with no project cannot work and
        // its failure looks like a rejected password.
        vmScope.launch {
            if (clients.client() == null) {
                updateState { AuthUiState.SignedOutWithNoServer }
            }
        }
    }

    override fun onIntent(intent: AuthIntent) {
        when (intent) {
            is AuthIntent.SignIn -> signIn(intent.email, intent.password)
            is AuthIntent.SignUp -> signUp(intent.email, intent.password)
            AuthIntent.SignInAnonymously -> signInAnonymously()
            AuthIntent.SignOut -> signOut()
            AuthIntent.ResetState -> updateState { AuthUiState.Idle }
            is AuthIntent.SaveServerConfig -> saveServerConfig(intent)
        }
    }

    private fun signIn(email: String, password: String) {
        vmScope.launch {
            updateState { AuthUiState.Loading }
            authRepository.signIn(email, password).fold(
                onSuccess = {
                    runCatching { onFirstSignIn() }
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
                    runCatching { onFirstSignIn() }
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

    /**
     * Stores the project and shows the credential form.
     *
     * A blank half is refused rather than stored, and the screen says which: a
     * stored URL with no key is indistinguishable from "not configured" until the
     * first request fails, and that failure arrives as a network error pointing at
     * the wrong thing entirely.
     */
    private fun saveServerConfig(intent: AuthIntent.SaveServerConfig) {
        vmScope.launch {
            val url = intent.url.trim()
            val key = intent.anonKey.trim()
            if (url.isEmpty() || key.isEmpty()) {
                emit(AuthUiEvent.Error("Both the project URL and the anon key are required"))
                return@launch
            }
            runCatching { clients.configure(SupabaseConfig(url = url, anonKey = key)) }
                .onSuccess { updateState { AuthUiState.Idle } }
                .onFailure { emit(AuthUiEvent.Error(it.message ?: "Could not save the server settings")) }
        }
    }

    private fun signOut() {
        vmScope.launch {
            authRepository.signOut()
        }
    }
}
