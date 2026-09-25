package com.singularity.todo.feature.notes.components

import androidx.compose.runtime.Stable
import com.singularity.todo.feature.notes.NoteFilter
import com.singularity.todo.feature.notes.NoteId
import com.singularity.todo.feature.notes.NoteSortOrder
import com.singularity.todo.feature.notes.presentation.NotesIntent

/**
 * All callback actions available in the [NotesScreen][com.singularity.todo.feature.notes.NotesScreen].
 * Screen composable receives exactly one `actions` parameter instead of 10+ individual lambdas.
 *
 * 8 callbacks grouped as:
 * - **Mutations**: delete, toggle pin
 * - **List control**: set filter, set sort order
 * - **Selection mode**: enter, toggle, exit selection, delete selected
 */
@Stable
@JvmInline
value class NotesActions(private val dispatch: (NotesIntent) -> Unit) {
    // ── Mutations ──────────────────────────────────────────────────────────────

    fun onDelete(id: NoteId) = dispatch(NotesIntent.Delete(id))
    fun onTogglePin(id: NoteId) = dispatch(NotesIntent.TogglePin(id))

    // ── List control ──────────────────────────────────────────────────────────

    fun onSetFilter(filter: NoteFilter) = dispatch(NotesIntent.SetFilter(filter))
    fun onSetSortOrder(order: NoteSortOrder) = dispatch(NotesIntent.SetSortOrder(order))

    // ── Selection mode ─────────────────────────────────────────────────────────

    fun onEnterSelection(id: NoteId) = dispatch(NotesIntent.EnterSelection(id))
    fun onToggleSelection(id: NoteId) = dispatch(NotesIntent.ToggleSelection(id))
    fun onExitSelection() = dispatch(NotesIntent.ExitSelection)
    fun onDeleteSelected() = dispatch(NotesIntent.DeleteSelected)

    companion object {
        /** No-op actions — useful for previews and test stubs. */
        val Empty = NotesActions {}
    }
}
