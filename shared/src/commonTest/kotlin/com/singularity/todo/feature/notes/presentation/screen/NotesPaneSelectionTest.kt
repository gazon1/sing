package com.singularity.todo.feature.notes.presentation.screen

import com.singularity.todo.feature.notes.NoteId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The only non-trivial new state of the two-pane notes screen: what the pane is
 * allowed to keep showing.
 *
 * Every case below is a spec scenario that would otherwise have no automated
 * test at all — a note deleted from the list while the pane shows it, and a wide
 * window narrowed below the breakpoint.
 */
class NotesPaneSelectionTest {
    private val open = NoteId.fromString("open")

    @Test
    fun a_selection_the_list_still_shows_survives_a_wide_window() {
        assertEquals("open", reconciledSelection("open", setOf(open), isWide = true))
    }

    @Test
    fun a_note_deleted_from_the_list_empties_the_pane() {
        // Swiped away, bulk-deleted or deleted from the editor route: either way the
        // list no longer renders it, and the pane must not keep reading it.
        assertNull(reconciledSelection("open", emptySet(), isWide = true))
        assertNull(reconciledSelection("open", setOf(NoteId.fromString("other")), isWide = true))
    }

    @Test
    fun narrowing_below_the_breakpoint_drops_the_selection() {
        assertNull(reconciledSelection("open", setOf(open), isWide = false))
    }

    @Test
    fun a_list_that_has_not_loaded_yet_clears_nothing() {
        assertEquals("open", reconciledSelection("open", shownNoteIds = null, isWide = true))
    }

    @Test
    fun nothing_selected_stays_nothing_selected() {
        assertNull(reconciledSelection(null, setOf(open), isWide = true))
    }
}
