package com.singularity.todo.core.ui.components

/**
 * One-shot UI events emitted by ViewModels.
 *
 * Unlike UI state (continuous, observed via StateFlow), events fire once and
 * should not be replayed on configuration change. The screen collects them via
 * [CollectEvents] and translates into transient UI (dialog, navigation, snackbar).
 */
sealed interface UiEvent {
    data class ShowDialog(val title: String, val text: String) : UiEvent
    data class ShowError(val message: String) : UiEvent
    data object NavigateBack : UiEvent
}
