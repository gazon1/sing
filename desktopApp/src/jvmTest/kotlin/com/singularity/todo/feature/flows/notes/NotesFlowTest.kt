package com.singularity.todo.feature.flows.notes

import androidx.compose.ui.test.ExperimentalTestApi
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.test.helpers.assertButtonEnabled
import com.singularity.todo.test.helpers.assertTagDisplayed
import com.singularity.todo.test.helpers.assertTextDisplayed
import com.singularity.todo.test.helpers.runDesktopAppTest
import com.singularity.todo.test.helpers.tapTab
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import kotlin.test.Test

/**
 * Desktop mirror of `Maestro/flows/notes/create-note.yaml` and
 * `02-filter-chips.yaml`, and the owner of scenario `NEGATIVE-02` (see
 * `scripts/src/traceability/scenarios/negative/NEGATIVE-02.yaml`).
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
@Tag("slow:compose")
class NotesFlowTest {
    @Test
    fun the_notes_list_opens_from_the_drawer() =
        runDesktopAppTest(checkA11y = true) {
            tapTab("Notes")

            assertTagDisplayed(TestTags.NOTES_QUICK_ADD_INPUT)
        }

    /**
     * The owner of `NEGATIVE-02`.
     *
     * The Android flow that used to carry it (`Maestro/flows/negative/02-empty-notes.yaml`)
     * was retired: the empty state is asserted here, and the flow's device-only
     * residue — the menu route to Notes and the quick-add field the flow typed
     * into — is carried by `Maestro/flows/notes/create-note.yaml`, which waits
     * for `notes_quick_add_input` before it types.
     */
    @Test
    @DisplayName("NEGATIVE-02 the notes list shows an empty state and offers to create the first note")
    fun an_empty_notes_list_offers_to_create_the_first_note() =
        runDesktopAppTest(checkA11y = true) {
            tapTab("Notes")

            assertTextDisplayed("No notes yet")
            // The button says what to press ("Create note"); the subtitle answers
            // why. The old label was "Create your first note".
            assertTextDisplayed("Create note")
            // The label alone does not prove the affordance works: a create
            // button that lost its click handler, or that was disabled, would
            // still show its text — and the empty state would keep claiming it
            // offers a way in. The helper selects the control node (the one
            // carrying the click action) and asserts it is enabled.
            assertButtonEnabled("Create note")
        }

    @Test
    fun the_filter_chips_are_offered_on_the_notes_list() =
        runDesktopAppTest(checkA11y = true) {
            tapTab("Notes")

            // The chips carry no testTag on either platform, so they are selected by
            // their visible labels — the same choice the Android flow makes.
            assertTextDisplayed("All")
            assertTextDisplayed("Pinned")
            assertTextDisplayed("Archived")
        }
}
