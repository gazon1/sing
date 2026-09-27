package com.singularity.todo.feature.backup

import com.singularity.todo.core.ui.MviEvent

/**
 * One-shot events emitted by [BackupViewModel].
 *
 * Success notifications used to travel on a second `SharedFlow<String>` next to this
 * one. They are now [ShowSnackbar] on the same channel, so the screen collects a
 * single event stream and the ViewModel's `onCleared` closes all of it.
 *
 * [Error] replaces the `showError = true` / `error = message` pattern on [BackupUiState].
 */
sealed interface BackupUiEvent : MviEvent {
    data class Error(val message: String) : BackupUiEvent

    /** Transient success message — rendered as a plain snackbar. */
    data class ShowSnackbar(val message: String) : BackupUiEvent

    /**
     * Settings snapshot JSON is ready to be shared.
     * [json] — the full settings snapshot as a JSON string.
     * The shell should present this to the user (e.g. via system share sheet).
     */
    data class SettingsSnapshotExported(val json: String) : BackupUiEvent
}
