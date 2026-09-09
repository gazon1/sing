package com.singularity.todo.feature.notes.components

import com.singularity.todo.feature.notes.NoteFilter
import com.singularity.todo.feature.notes.NoteId
import com.singularity.todo.feature.notes.NoteSortOrder

/**
 * All callback actions available in the [NotesScreen][com.singularity.todo.feature.notes.NotesScreen].
 * Packed into a single [JvmInline value class][value class] so the screen composable
 * receives exactly one `actions` parameter instead of 10+ individual lambdas.
 *
 * 10 callbacks grouped as:
 * - **Navigation**: navigate to note detail
 * - **Mutations**: create, delete, toggle pin
 * - **List control**: set filter, set sort order
 * - **Selection mode**: enter, toggle, exit selection, delete selected
 */
@JvmInline
value class NotesActions(
    val block: (Action) -> Unit,
) {
    /** Sealed action hierarchy — enables exhaustive `when` with smart-cast. */
    sealed class Action {
        // ── Navigation ────────────────────────────────────────────────────────
        data class NavigateToNote(val id: NoteId) : Action()

        // ── Mutations ──────────────────────────────────────────────────────────
        data class CreateNote(val title: String) : Action()
        data class Delete(val id: NoteId) : Action()
        data class TogglePin(val id: NoteId) : Action()

        // ── List control ──────────────────────────────────────────────────────
        data class SetFilter(val filter: NoteFilter) : Action()
        data class SetSortOrder(val order: NoteSortOrder) : Action()

        // ── Selection mode ─────────────────────────────────────────────────────
        data class EnterSelection(val id: NoteId) : Action()
        data class ToggleSelection(val id: NoteId) : Action()
        data object ExitSelection : Action()
        data object DeleteSelected : Action()
    }

    // ── Navigation ─────────────────────────────────────────────────────────────

    fun onNavigateToNote(id: NoteId) = block(Action.NavigateToNote(id))
    fun onCreateNote(title: String) = block(Action.CreateNote(title))

    // ── Mutations ──────────────────────────────────────────────────────────────

    fun onDelete(id: NoteId) = block(Action.Delete(id))
    fun onTogglePin(id: NoteId) = block(Action.TogglePin(id))

    // ── List control ──────────────────────────────────────────────────────────

    fun onSetFilter(filter: NoteFilter) = block(Action.SetFilter(filter))
    fun onSetSortOrder(order: NoteSortOrder) = block(Action.SetSortOrder(order))

    // ── Selection mode ─────────────────────────────────────────────────────────

    fun onEnterSelection(id: NoteId) = block(Action.EnterSelection(id))
    fun onToggleSelection(id: NoteId) = block(Action.ToggleSelection(id))
    fun onExitSelection() = block(Action.ExitSelection)
    fun onDeleteSelected() = block(Action.DeleteSelected)

    companion object {
        /** No-op actions — useful for previews and test stubs. */
        val Empty = NotesActions {}
    }
}
