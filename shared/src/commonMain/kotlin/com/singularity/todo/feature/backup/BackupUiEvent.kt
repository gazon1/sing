package com.singularity.todo.feature.backup

/**
 * One-shot events emitted by [BackupViewModel].
 *
 * [Error] replaces the `showError = true` / `error = message` pattern on [BackupUiState].
 * [clearError] is no longer needed — events are one-shot by nature.
 */
sealed interface BackupUiEvent {
    data class Error(val message: String) : BackupUiEvent
    data class ShowSnackbar(val message: String) : BackupUiEvent
}
