package com.singularity.todo.feature.flows.notes

import androidx.compose.ui.test.ExperimentalTestApi
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.feature.notes.domain.port.NotesRepository
import com.singularity.todo.test.helpers.assertTagDisplayed
import com.singularity.todo.test.helpers.awaitTagGone
import com.singularity.todo.test.helpers.clickTag
import com.singularity.todo.test.helpers.runDesktopAppTest
import com.singularity.todo.test.helpers.tapTab
import org.junit.jupiter.api.Tag
import kotlin.test.Test

/**
 * The notes screen above and below the wide-layout breakpoint.
 *
 * Above it the list and the open note are two panes: opening a note fills the
 * right one and leaves the list where it was. Below it the note replaces the
 * list, which is the behaviour every other notes flow already relies on.
 *
 * The breakpoint is 840 dp (`com.singularity.todo.core.ui.WideLayoutMinWidth`),
 * the harness window is 1024×768 unless a test says otherwise, and `awaitTagGone`
 * is the assertion that a pane *appeared*: a container keeps the previous frame
 * composed across the selection, so `assertDoesNotExist` races it.
 */
@OptIn(ExperimentalTestApi::class)
@Tag("slow")
@Tag("slow:compose")
class NotesTwoPaneFlowTest {
    @Test
    fun a_wide_window_opens_the_note_beside_the_list_it_came_from() =
        runDesktopAppTest(checkA11y = true) { koin ->
            koin.get<NotesRepository>().createNoteWithTitle("Groceries").getOrThrow()

            tapTab("Notes")

            // Nothing is selected yet, so the pane says so rather than being blank.
            assertTagDisplayed(TestTags.NOTES_DETAIL_EMPTY)

            clickTag(TestTags.noteItemByTitle("Groceries"))

            // The preview's own action is the proof the pane holds the note …
            awaitTagGone(TestTags.NOTES_DETAIL_EMPTY)
            assertTagDisplayed(TestTags.noteAction("edit"))

            // … and the quick-add field is the proof the list is still there. In
            // the single-pane layout opening a note replaces the list and takes
            // that field with it, so this assertion is what tells the two layouts
            // apart.
            assertTagDisplayed(TestTags.NOTES_QUICK_ADD_INPUT)
        }

    @Test
    fun a_narrow_window_still_opens_the_note_in_place_of_the_list() =
        runDesktopAppTest(width = 800, height = 768, checkA11y = true) { koin ->
            koin.get<NotesRepository>().createNoteWithTitle("Groceries").getOrThrow()

            tapTab("Notes")
            clickTag(TestTags.noteItemByTitle("Groceries"))

            assertTagDisplayed(TestTags.noteAction("edit"))

            // The list is gone, not kept: below the breakpoint a note is a screen,
            // not a pane. Waiting for the departure is the point — `assertDoesNotExist`
            // would race the outgoing entry that is still composed.
            awaitTagGone(TestTags.NOTES_QUICK_ADD_INPUT)
        }
}
