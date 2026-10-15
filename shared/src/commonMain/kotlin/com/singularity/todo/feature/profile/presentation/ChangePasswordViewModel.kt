package com.singularity.todo.feature.profile.presentation

import com.singularity.todo.core.auth.AuthRepository
import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.error.AppError
import com.singularity.todo.core.observability.CrashReportingPort
import com.singularity.todo.core.observability.NoOpCrashReportingPort
import com.singularity.todo.core.observability.reportingScope
import com.singularity.todo.core.ui.MviEvent
import com.singularity.todo.core.ui.MviIntent
import com.singularity.todo.core.ui.MviViewModel
import kotlinx.coroutines.launch

/**
 * UI state for the change-password screen.
 */
sealed interface ChangePasswordUiState {
    data object Idle : ChangePasswordUiState
    data object Loading : ChangePasswordUiState
    data object Success : ChangePasswordUiState
}

/**
 * One-shot events emitted by [ChangePasswordViewModel].
 */
sealed interface ChangePasswordEvent : MviEvent {
    data object Success : ChangePasswordEvent
    data class Error(val message: String) : ChangePasswordEvent
}

/**
 * Intents for the change-password screen.
 */
sealed interface ChangePasswordIntent : MviIntent {
    data class Submit(val password: String) : ChangePasswordIntent
    data object Reset : ChangePasswordIntent
}

/**
 * ViewModel for the change-password screen.
 *
 * @param authRepository The auth repository, injected to call [AuthRepository.updatePassword].
 */
class ChangePasswordViewModel(
    private val authRepository: AuthRepository,
    crashReporter: CrashReportingPort = NoOpCrashReportingPort(),
    scope: AutoCloseableCoroutineScope = reportingScope(crashReporter),
) : MviViewModel<ChangePasswordUiState, ChangePasswordIntent, ChangePasswordEvent>(
        initialState = ChangePasswordUiState.Idle,
        crashReporter = crashReporter,
        scope = scope,
    ) {

    override fun onIntent(intent: ChangePasswordIntent) {
        when (intent) {
            is ChangePasswordIntent.Submit -> submit(intent.password)
            ChangePasswordIntent.Reset -> updateState { ChangePasswordUiState.Idle }
        }
    }

    private fun submit(password: String) {
        vmScope.launch {
            if (_state.value is ChangePasswordUiState.Loading) return@launch
            updateState { ChangePasswordUiState.Loading }
            authRepository.updatePassword(password).fold(
                onSuccess = {
                    updateState { ChangePasswordUiState.Success }
                    emit(ChangePasswordEvent.Success)
                },
                onFailure = { error ->
                    updateState { ChangePasswordUiState.Idle }
                    emit(
                        ChangePasswordEvent.Error(
                            error.message ?: (error as? AppError)?.message ?: "Could not change password",
                        ),
                    )
                },
            )
        }
    }
}
