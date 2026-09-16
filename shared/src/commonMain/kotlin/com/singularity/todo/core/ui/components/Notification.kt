package com.singularity.todo.core.ui.components

/**
 * Widget-level notification — the rendering target of [NotificationHost].
 *
 * Unlike [UiEvent] (which is feature-specific and lives in each feature package),
 * Notification is the canonical set of things a screen can show transiently.
 * [NotificationHost] maps each feature event to one of these variants.
 */
sealed interface Notification {
    /** Generic informational dialog with optional text. */
    data class Text(val title: String, val text: String?) : Notification

    /** Error dialog. */
    data class Error(val message: String) : Notification

    /** Request to navigate back. */
    data object NavigateBack : Notification

    /** Request to dismiss / clear any active notification. */
    data object Dismiss : Notification

    /** No notification — event is handled via other UI (e.g. animation). */
    data object None : Notification
}
