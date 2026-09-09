package com.singularity.todo.feature.notes.components

import com.singularity.todo.feature.notes.NoteId

/**
 * Action callbacks available on a [NoteCard][com.singularity.todo.feature.notes.NoteCard].
 * Packed into a single [JvmInline value class][value class] so the card itself
 * receives one parameter for "what can happen here" instead of 4 separate callbacks.
 *
 * 4 individual callbacks grouped as:
 * - **Navigation**: open note detail
 * - **Mutations**: delete, toggle pin
 * - **Cross-cutting**: open backlinks panel
 */
@JvmInline
value class NoteCardActions(
    val block: (Action) -> Unit,
) {
    /** Sealed action hierarchy — enables exhaustive `when` with smart-cast. */
    sealed class Action {
        /** Navigate to the note's read-only detail view. */
        data class NavigateToNote(val id: NoteId) : Action()
        /** Soft-delete the note. */
        data class Delete(val id: NoteId) : Action()
        /** Toggle the note's pinned flag. */
        data class TogglePin(val id: NoteId) : Action()
        /** Open the backlinks panel for the note. */
        data class OpenBacklinks(val id: NoteId) : Action()
    }

    // ── Convenience dispatchers ─────────────────────────────────────────────

    fun onNavigateToNote(id: NoteId) = block(Action.NavigateToNote(id))
    fun onDelete(id: NoteId) = block(Action.Delete(id))
    fun onTogglePin(id: NoteId) = block(Action.TogglePin(id))
    fun onOpenBacklinks(id: NoteId) = block(Action.OpenBacklinks(id))

    companion object {
        /** No-op actions — useful for previews and test stubs. */
        val Empty = NoteCardActions {}
    }
}
