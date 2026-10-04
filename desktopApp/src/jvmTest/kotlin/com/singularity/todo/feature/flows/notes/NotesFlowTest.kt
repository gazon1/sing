package com.singularity.todo.feature.flows.notes

import androidx.compose.ui.test.ExperimentalTestApi
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.test.helpers.assertTagDisplayed
import com.singularity.todo.test.helpers.assertTextDisplayed
import com.singularity.todo.test.helpers.runDesktopAppTest
import com.singularity.todo.test.helpers.tapTab
import org.junit.jupiter.api.Tag
import kotlin.test.Test

/**
 * Desktop mirror of `Maestro/flows/notes/create-note.yaml` and
 * `02-filter-chips.yaml`.
 *
 * Notes live behind the drawer's second group on desktop rather than a bottom-bar
 * tab on Android, and the list has no FAB on either platform — a note is created
 * through the quick-add field, which submits on the IME "Done" action.
 *
 * The list is reached through [tapTab] rather than the Android `nav_menu_button`
 * sheet. Its `TestTags.NOTES_LIST` tag sits on the LazyColumn, which does not
 * surface through the semantics layer on either platform, so the quick-add field
 * is the node these flows actually assert on.
 */
@OptIn(ExperimentalTestApi::class)
@Tag("slow")
class NotesFlowTest {

    @Test
    fun the_notes_list_opens_from_the_drawer() = runDesktopAppTest(checkA11y = true) {
        tapTab("Notes")

        assertTagDisplayed(TestTags.NOTES_QUICK_ADD_INPUT)
    }

    @Test
    fun an_empty_notes_list_offers_to_create_the_first_note() = runDesktopAppTest(checkA11y = true) {
        tapTab("Notes")

        assertTextDisplayed("No notes yet")
        assertTextDisplayed("Create your first note")
    }

    @Test
    fun the_filter_chips_are_offered_on_the_notes_list() = runDesktopAppTest(checkA11y = true) {
        tapTab("Notes")

        // The chips carry no testTag on either platform, so they are selected by
        // their visible labels — the same choice the Android flow makes.
        assertTextDisplayed("All")
        assertTextDisplayed("Pinned")
        assertTextDisplayed("Archived")
    }
}
