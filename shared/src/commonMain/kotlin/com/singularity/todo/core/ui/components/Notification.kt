package com.singularity.todo.core.ui.components

/**
 * Widget-level notification — the rendering target of [NotificationHost].
 *
 * Unlike [UiEvent] (which is feature-specific and lives in each feature package),
 * Notification is the canonical set of things a screen can show transiently.
 * [NotificationHost] maps each feature event to one of these variants.
 */
sealed interface Notification {
    /** Generic informational dialog. */
    data class Text(val title: String, val text: String) : Notification

    /** Error dialog. */
    data class Error(val message: String) : Notification

    /**
     * Non-blocking notice that appears and disappears on its own.
     *
     * Deliberately not [Text]: a report about an action the user just asked for
     * does not need an answer, and a dialog makes the user dismiss it before
     * they can do anything else. The refresh report is the case this exists
     * for — it confirms what happened and gets out of the way.
     *
     * One string rather than a title and a text: a notice that disappears on
     * its own has room for a sentence, not for a heading above it.
     */
    data class Toast(val message: String) : Notification

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
     *
     * [onDeclined] runs when the toast goes away *without* the action being taken — the
     * user let it time out or swiped it away. It is what lets a screen that has to leave
     * wait for the offer before it leaves: a toast that is destroyed by the navigation it
     * was supposed to accompany is no offer at all.
     */
    data class Undo(
        val title: String,
        val actionLabel: String = "Undo",
        val onAction: () -> Unit,
        val onDeclined: () -> Unit = {},
    ) : Notification

    /** Request to navigate back. */
    data object NavigateBack : Notification

    /** Request to dismiss / clear any active notification. */
    data object Dismiss : Notification

    /** No notification — event is handled via other UI (e.g. animation). */
    data object None : Notification
}
