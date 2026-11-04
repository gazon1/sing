package com.singularity.todo.feature.notes.presentation.screen

import com.singularity.todo.feature.notes.NoteId

/**
 * The note the detail pane may keep showing, given what the list still offers.
 *
 * The pane's selection is state of the list screen, so it outlives the rows it
 * was made from: a swipe on a row, a bulk delete, or a delete made in the
 * editor route the pane itself opened all remove the note without telling the
 * pane. Reconciling against the list, in one place, is what keeps the two panes
 * from disagreeing — without it the pane keeps showing a note that no longer
 * exists, the empty state never appears, and the pane's edit affordance opens
 * the editor for a deleted id.
 *
 * A filter or a search that hides the selected note closes the pane for the
 * same reason: the pane shows what the list selected, and a list that no longer
 * offers the note has nothing to point the pane at.
 *
 * @param selected the id the last selection made, or `null` when nothing is
 *   selected.
 * @param shownNoteIds every note the list is currently rendering, or `null`
 *   when the list has not loaded. An unloaded list is not evidence of a
 *   deletion, so it must not clear anything.
 * @param isWide whether the screen is currently wide enough for a pane. Below
 *   the breakpoint a selection has nowhere to be shown, so it is dropped rather
 *   than kept invisibly until the window widens again.
 *
 * Public for the same reason [com.singularity.todo.core.ui.isWideLayout] is: the
 * decision is pure, and the test that pins it lives in `shared`'s `commonTest`
 * next to the breakpoint's, not behind a Compose runtime this module's tests do
 * not have.
 */
fun reconciledSelection(
    selected: String?,
    shownNoteIds: Set<NoteId>?,
    isWide: Boolean,
): String? =
    when {
        !isWide -> null
        selected == null -> null
        shownNoteIds == null -> selected
        NoteId.fromString(selected) in shownNoteIds -> selected
        else -> null
    }
