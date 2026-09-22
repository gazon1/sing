package com.singularity.todo.feature.tasks.presentation.components

import androidx.compose.runtime.Stable

/**
 * Action callbacks available on a [TaskCard]. Each callback is nullable so the
 * card conditionally renders only the buttons the caller wires up — the
 * canonical Kotlin/Compose Slot API pattern.
 *
 * Pass `TaskCardActions()` for read-only cards (e.g. archived tasks in
 * [com.singularity.todo.feature.archive.ArchiveScreen], where restore is a
 * separate flow rather than a per-card action).
 *
 * @param onToggle  Called when the user taps the completion checkbox.
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
)
