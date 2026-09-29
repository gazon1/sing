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

    /**
     * Non-blocking toast offering to reverse a destructive action.
     *
     * Deliberately not [Text]: a delete confirmation belongs in a snackbar, not a modal.
     * A dialog forces the user to acknowledge the deletion before doing anything else and
     * offers no way out of the choice they just made — which is exactly what "Undo" exists
     * to give back.
     *
     * [onAction] runs when the user taps [actionLabel]. The host clears the toast either
     * way, so [onAction] must be a dispatch rather than a suspension.
     */
    data class Undo(val title: String, val actionLabel: String = "Undo", val onAction: () -> Unit) : Notification

    /** Request to navigate back. */
    data object NavigateBack : Notification

    /** Request to dismiss / clear any active notification. */
    data object Dismiss : Notification

    /** No notification — event is handled via other UI (e.g. animation). */
    data object None : Notification
}
