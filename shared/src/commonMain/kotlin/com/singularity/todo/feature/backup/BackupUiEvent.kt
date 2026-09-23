package com.singularity.todo.feature.backup

/**
 * One-shot events emitted by [BackupViewModel].
 *
 * Success notifications (snackbars) are emitted via [BackupViewModel.snackbar] instead.
 *
 * [Error] replaces the `showError = true` / `error = message` pattern on [BackupUiState].
 */
sealed interface BackupUiEvent {
    data class Error(val message: String) : BackupUiEvent

    /**
     * Settings snapshot JSON is ready to be shared.
     * [json] — the full settings snapshot as a JSON string.
     * The shell should present this to the user (e.g. via system share sheet).
     */
    data class SettingsSnapshotExported(val json: String) : BackupUiEvent
}
