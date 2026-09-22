package com.singularity.todo.feature.tasks.presentation.components

import androidx.compose.runtime.Stable

/**
 * Action callbacks available on a [TaskCard]. Each callback is nullable so the
 * card conditionally renders only the buttons the caller cares about — the
 * canonical Kotlin/Compose Slot API pattern instead of a sentinel `Empty`.
 *
 * @param onToggle  Called when the user taps the completion checkbox. Always
 *                  rendered — pass `null` when the card is in a read-only state.
 * @param onPin    Called when the user taps the pin button.
 * @param onAiClick Called when the user taps the AI action button.
 * @param onDelete Called when the user taps the delete button.
 */
@Stable
class TaskCardActions(
    val onToggle: (() -> Unit)? = null,
    val onPin: (() -> Unit)? = null,
    val onAiClick: (() -> Unit)? = null,
    val onDelete: (() -> Unit)? = null,
) {
    companion object {
        /** All callbacks are null — useful for read-only cards such as archived tasks. */
        val Empty = TaskCardActions()
    }
}
