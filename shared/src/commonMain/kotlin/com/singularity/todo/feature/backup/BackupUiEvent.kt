package com.singularity.todo.feature.backup

/**
 * One-shot error events emitted by [BackupViewModel].
 *
 * Success notifications (snackbars) are emitted via [BackupViewModel.snackbar] instead.
 *
 * [Error] replaces the `showError = true` / `error = message` pattern on [BackupUiState].
 */
sealed interface BackupUiEvent {
    data class Error(val message: String) : BackupUiEvent
}
