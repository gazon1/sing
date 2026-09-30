package com.singularity.todo.feature.flows.notes

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.test.helpers.runDesktopAppTest
import com.singularity.todo.test.helpers.tapTab
import org.junit.Test

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
class NotesFlowTest {

    @Test
    fun the_notes_list_opens_from_the_drawer() = runDesktopAppTest {
        tapTab("Notes")

        onNodeWithTag(TestTags.NOTES_QUICK_ADD_INPUT).assertIsDisplayed()
    }

    @Test
    fun an_empty_notes_list_offers_to_create_the_first_note() = runDesktopAppTest {
        tapTab("Notes")

        onNodeWithText("No notes yet").assertIsDisplayed()
        onNodeWithText("Create your first note").assertIsDisplayed()
    }

    @Test
    fun the_filter_chips_are_offered_on_the_notes_list() = runDesktopAppTest {
        tapTab("Notes")

        // The chips carry no testTag on either platform, so they are selected by
        // their visible labels — the same choice the Android flow makes.
        onNodeWithText("All").assertIsDisplayed()
        onNodeWithText("Pinned").assertIsDisplayed()
        onNodeWithText("Archived").assertIsDisplayed()
    }
}
