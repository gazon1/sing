package com.singularity.todo.feature.auth

import androidx.compose.runtime.Immutable

/**
 * UI state for the authentication screen.
 */
@Immutable
sealed interface AuthUiState {
    data object Idle : AuthUiState
    data object Loading : AuthUiState
    data object Success : AuthUiState
}
