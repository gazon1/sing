package com.singularity.todo.feature.auth

import com.singularity.todo.core.auth.AuthRepository
import com.singularity.todo.core.auth.Session
import com.singularity.todo.core.auth.SupabaseClientProvider
import com.singularity.todo.core.auth.SupabaseConfig
import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.error.AppError
import com.singularity.todo.core.error.runCatchingCancellable
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.ui.MviIntent
import com.singularity.todo.core.ui.MviViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import com.singularity.todo.core.observability.CrashReportingPort
import com.singularity.todo.core.observability.NoOpCrashReportingPort
import com.singularity.todo.core.observability.reportingScope

/**
 * Intents for the auth screen.
 */
sealed interface AuthIntent : MviIntent {
    data class SignIn(val email: String, val password: String) : AuthIntent
    data class SignUp(val email: String, val password: String) : AuthIntent

    /**
     * Starts the local, offline session. Contacts nothing.
     *
     * Named for the button rather than for a provider's operation, because that is what
     * it is: "Continue Offline" is the whole promise, and the method behind it makes no
     * network call. See ADR
     * `2026-10-05-anonymous-is-local-only-and-signing-in-offers-a-choice`.
     */
    data object ContinueOffline : AuthIntent

    data object SignOut : AuthIntent
    data object ResetState : AuthIntent

    /**
     * Attaches an email/password account to the current local session.
     *
     * Only valid when [Session.Anonymous]; calling it in any other session is a no-op
     * that returns failure (guarded by the UI — the button is only shown for local
     * sessions).
     *
     * [disposition] is not optional and has no default: the data created without an
     * account is the user's, and what happens to it is theirs to decide.
     */
    data class AttachAccount(val email: String, val password: String, val disposition: LocalDataDisposition) :
        AuthIntent

    /**
     * Stores the Supabase project to talk to.
     *
     * Carries the URL and the **anon** key. The anon key ships inside every copy
     * of the app and is meant to be readable; the service-role key is not, and
     * asking for it here would be asking the user for a secret that has no place
     * on a device.
     */
    data class SaveServerConfig(val url: String, val anonKey: String) : AuthIntent

    /**
     * Verifies the [token] from a magic link sent to [email].
     *
     * Completes sign-in for an unconfirmed account by exchanging the token from
     * the magic link URL for a live session.
     */
    data class VerifyMagicLink(val email: String, val token: String) : AuthIntent

    /**
     * Resends a new magic link to [email].
     *
     * Used on the [com.singularity.todo.feature.auth.AuthUiState.AwaitingEmailConfirmation]
     * screen when the original confirmation email was lost (spam filter, etc.).
     */
    data class SendMagicLink(val email: String) : AuthIntent
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
    /**
     * Signs an account in and applies [LocalDataDisposition] to the local rows.
     *
     * Returns a [Flow] of [ClaimStep] so every intermediate state — including
     * [ClaimStep.SignOutFailed] — is available to the UI without a separate observer.
     *
     * A lambda for the same reason as [onFirstSignIn]: the claimer holds three
     * storage collaborators, and a ViewModel that injected them would be untestable
     * without a database. Optional, because the login and offline paths — the ones a
     * unit test exercises — do not need it.
     */
    private val claimAccount: suspend (
        email: String,
        password: String,
        disposition: LocalDataDisposition,
    ) -> Flow<ClaimStep> = { _, _, _ ->
        kotlinx.coroutines.flow.flowOf(
            ClaimStep.Refused(
                AppError.Validation(
                    "Attaching an account is not available here",
                    code = "auth.claim_unavailable",
                ),
            ),
        )
    },
    /**
     * Counts what the local owner holds, for the attach screen to show *before* anyone
     * is asked to choose. Same reason as [claimAccount] — a count of somebody's tasks
     * has to come from the same place the tasks live.
     */
    private val summariseLocalData: suspend (UserId) -> LocalDataDisposition =
        { LocalDataDisposition.Keep(ReassignCounts(), LocalDataSummary(0, 0)) },
    crashReporter: CrashReportingPort = NoOpCrashReportingPort(),
    scope: AutoCloseableCoroutineScope = reportingScope(crashReporter),
) : MviViewModel<AuthUiState, AuthIntent, AuthUiEvent>(
        initialState = AuthUiState.Idle,
        crashReporter = crashReporter,
        scope = scope,
    ) {
    val session: StateFlow<Session> = authRepository.currentSession

    /**
     * What the local session holds, published so the attach screen can name the number
     * it is about to risk.
     *
     * A separate flow rather than a field on [AuthUiState] because it is not a screen
     * state — it does not change what the login screen draws, and putting it on the
     * state would mean a count re-rendering the whole auth screen whenever a task is
     * created.
     */
    private val _localData = MutableStateFlow<LocalDataDisposition>(
        LocalDataDisposition.Keep(ReassignCounts(), LocalDataSummary(0, 0)),
    )
    val localData: StateFlow<LocalDataDisposition> = _localData.asStateFlow()

    init {
        // The screen opens on "no server" rather than on the credential form when
        // nothing is configured, because a sign-in with no project cannot work and
        // its failure looks like a rejected password. The offline path is unaffected
        // either way — it needs no project — and `LoginScreen` offers it on both.
        vmScope.launch {
            if (clients.client() == null) {
                updateState { AuthUiState.SignedOutWithNoServer }
            }
        }
        vmScope.launch { refreshLocalDataSummary() }

        // A restore that could not be completed is not the same as having no session, and
        // the login screen is the only place that can say so. Without this the user who was
        // signed in yesterday and cannot be today sees a login screen identical to a fresh
        // install's, and has no way to tell that something was lost rather than never there.
        //
        // A [AuthUiEvent.Message] rather than an [AuthUiEvent.Error]: nothing failed that the
        // user did or can act on — their data is intact and "Continue Offline" still works.
        // Painting it in the error colour would report a problem that has no remedy, which
        // is the same mistake REQ-UA-014 calls out for the confirmation notice.
        //
        // `firstOrNull()` rather than `collect`: there is one restore per launch, so one
        // failure per launch, and taking the single event lets the coroutine finish. A
        // `collect` here would be a collector that never completes — harmless in production,
        // where it lives on a background scope, and a test failure everywhere else, because
        // `runTest` waits for foreground coroutines and reports the wait as
        // `UncompletedCoroutinesError` whatever the assertions underneath said. It also
        // returns immediately on the empty flow the interface defaults to.
        vmScope.launch {
            if (authRepository.restoreFailures.firstOrNull() != null) {
                emit(AuthUiEvent.Message(RESTORE_FAILED_MESSAGE))
            }
        }
    }

    override fun onIntent(intent: AuthIntent) {
        when (intent) {
            is AuthIntent.SignIn -> signIn(intent.email, intent.password)
            is AuthIntent.SignUp -> signUp(intent.email, intent.password)
            AuthIntent.ContinueOffline -> continueOffline()
            AuthIntent.SignOut -> signOut()
            AuthIntent.ResetState -> updateState { AuthUiState.Idle }
            is AuthIntent.AttachAccount -> attachAccount(intent)
            is AuthIntent.SaveServerConfig -> saveServerConfig(intent)
            is AuthIntent.VerifyMagicLink -> verifyMagicLink(intent.email, intent.token)
            is AuthIntent.SendMagicLink -> sendMagicLink(intent.email)
        }
    }

    /**
     * Recounts the local rows, quietly.
     *
     * A failure here is not reported: the count is information for a choice, and a
     * screen that refuses to open because a counter could not run is worse than one
     * that says "nothing yet" about data the user can still see.
     */
    private suspend fun refreshLocalDataSummary() {
        val owner = (authRepository.currentSession.value as? Session.Anonymous)?.userId ?: return
        runCatchingCancellable { summariseLocalData(owner) }
            .onSuccess { _localData.value = it }
            .onFailure { _localData.value = LocalDataDisposition.Keep(ReassignCounts(), LocalDataSummary(0, 0)) }
    }

    private fun signIn(email: String, password: String) {
        vmScope.launch {
            if (_state.value is AuthUiState.Loading) return@launch
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
            if (_state.value is AuthUiState.Loading) return@launch
            updateState { AuthUiState.Loading }
            authRepository.signUp(email, password).fold(
                onSuccess = {
                    // A success with no session is the "account created, address must
                    // be confirmed" case, and it is not a completed sign-in. The
                    // repository reports it this way deliberately — a failure here
                    // would answer "that address is already registered" on the retry —
                    // so the distinction has to be carried this far rather than
                    // re-decided. Navigating on it put the user inside the app with no
                    // session and no explanation.
                    if (authRepository.currentSession.value is Session.SignedOut) {
                        updateState { AuthUiState.AwaitingEmailConfirmation(email) }
                        emit(
                            AuthUiEvent.Message(
                                "Account created. Check your email to confirm the address, then sign in.",
                            ),
                        )
                    } else {
                        runCatching { onFirstSignIn() }
                        updateState { AuthUiState.Success }
                        emit(AuthUiEvent.NavigateToHome)
                    }
                },
                onFailure = {
                    updateState { AuthUiState.Idle }
                    emit(AuthUiEvent.Error(it.message ?: "Sign up failed"))
                },
            )
        }
    }

    private fun continueOffline() {
        vmScope.launch {
            if (_state.value is AuthUiState.Loading) return@launch
            updateState { AuthUiState.Loading }
            authRepository.startLocalSession().fold(
                onSuccess = {
                    updateState { AuthUiState.Success }
                    emit(AuthUiEvent.NavigateToHome)
                    refreshLocalDataSummary()
                },
                onFailure = {
                    updateState { AuthUiState.Idle }
                    emit(AuthUiEvent.Error(it.message ?: "Could not continue offline"))
                },
            )
        }
    }

    /**
     * Signs an account in, then does what the user chose to do with their offline data.
     *
     * The choice rides in the intent rather than in [AuthUiState] because it is a
     * property of this submission, not a screen the user can be left sitting on: the
     * screen holds the two options, and once the form is submitted there is nothing
     * to go back to. Refusals come back as [AuthUiEvent.Error] with the session
     * untouched, which is what `LocalAccountClaimer` guarantees by signing in before
     * it moves anything.
     */
    private fun attachAccount(intent: AuthIntent.AttachAccount) {
        vmScope.launch {
            if (_state.value is AuthUiState.Loading) return@launch
            updateState { AuthUiState.Loading }

            // Collect every step from the claimer — including SignOutFailed which occurs
            // after the account is signed in and the local session is being restored.
            // The flow terminates with Done (success) or Refused (failure).
            var result: Result<Unit> = Result.success(Unit) // collected on terminal step
            claimAccount(intent.email, intent.password, intent.disposition).collect { step ->
                when (step) {
                    is ClaimStep.SignOutFailed -> {
                        emit(
                            AuthUiEvent.Message(
                                "The account credentials could not be cleared from this device. " +
                                    "Your offline data is restored, but you may need to sign out " +
                                    "manually on the next launch.",
                            ),
                        )
                    }

                    is ClaimStep.Done -> {
                        result = Result.success(Unit)
                        emit(AuthUiEvent.Message(step.disposition.toastMessage))
                    }

                    is ClaimStep.Refused -> {
                        result = Result.failure(step.reason)
                    }

                    else -> {
                        // SigningIn, MovingData, RemovingData, Restoring — no UI event needed.
                    }
                }
            }

            result.fold(
                onSuccess = {
                    runCatching { onFirstSignIn() }
                    updateState { AuthUiState.Success }
                    emit(AuthUiEvent.NavigateToHome)
                },
                onFailure = {
                    updateState { AuthUiState.Idle }
                    emit(AuthUiEvent.Error(it.message ?: "Could not attach the account"))
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

    private fun verifyMagicLink(email: String, token: String) {
        vmScope.launch {
            if (_state.value is AuthUiState.Loading) return@launch
            updateState { AuthUiState.Loading }
            authRepository.verifyMagicLink(email, token).fold(
                onSuccess = {
                    updateState { AuthUiState.Success }
                    emit(AuthUiEvent.NavigateToHome)
                },
                onFailure = {
                    updateState { AuthUiState.Idle }
                    emit(AuthUiEvent.Error(it.message ?: "Verification failed"))
                },
            )
        }
    }

    private fun sendMagicLink(email: String) {
        vmScope.launch {
            if (_state.value is AuthUiState.Loading) return@launch
            updateState { AuthUiState.Loading }
            authRepository.sendMagicLink(email).fold(
                onSuccess = {
                    updateState { AuthUiState.AwaitingEmailConfirmation(email) }
                    emit(AuthUiEvent.Message("A new sign-in link was sent to $email"))
                },
                onFailure = {
                    updateState { AuthUiState.AwaitingEmailConfirmation(email) }
                    emit(AuthUiEvent.Error(it.message ?: "Could not resend the link"))
                },
            )
        }
    }

    /**
     * Signs out, and says so when it does not.
     *
     * The failure used to be dropped, and dropping it is worse here than anywhere else:
     * the user leaves the auth screen believing the account is closed while the
     * session it was supposed to end is still on the device. Nothing about the screen
     * says otherwise, and the next thing they learn is that their data was still there.
     *
     * `emitError` rather than a hand-written `fold` because the siblings above report to
     * the user but never to the crash reporter, so a sign-out that fails the same way a
     * sign-in fails is currently the one path that leaves no trace anywhere. The seam
     * already does both.
     */
    private fun signOut() {
        emitError("Sign out failed", { msg -> AuthUiEvent.Error("Sign out failed: $msg") }) {
            authRepository.signOut()
        }
    }
}

/**
 * What the login screen says when the previous session could not be read back.
 *
 * Names what happened rather than what the user should feel, and stops short of promising
 * the data is safe — that is a claim about the store, and this message is emitted before
 * anything has verified it. "Your data is still on this device" is the part that is true
 * and the part the user needs in order to press "Continue Offline" without hesitation.
 */
internal const val RESTORE_FAILED_MESSAGE =
    "Could not read your saved session. Your data is still on this device — " +
        "continue offline or sign in again."
