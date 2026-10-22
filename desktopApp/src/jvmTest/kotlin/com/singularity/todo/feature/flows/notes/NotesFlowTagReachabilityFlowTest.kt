package com.singularity.todo.feature.flows.notes

import androidx.compose.ui.test.ExperimentalTestApi
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.test.helpers.assertTagInMergedTree
import com.singularity.todo.test.helpers.clickTag
import com.singularity.todo.test.helpers.goBack
import com.singularity.todo.test.helpers.runDesktopAppTest
import com.singularity.todo.test.helpers.submitTag
import com.singularity.todo.test.helpers.tapTab
import com.singularity.todo.test.helpers.typeIntoTag
import org.junit.jupiter.api.Tag
import kotlin.test.Test

/**
 * The tags the notes Maestro flows address must exist in the **merged** tree.
 *
 * ## Why the merged tree
 *
 * Maestro drives the app through the accessibility tree, and the accessibility
 * tree is the *merged* one: a node that Compose folded into an ancestor is not
 * announced separately, and `TestTag`'s merge policy keeps the parent's value —
 * so a tag that sits below a merging node (a Material3 `Card`/`Surface` merges
 * its children) does not survive the merge at all.
 *
 * Such a tag exists in the unmerged tree and is unreachable for the flow: the
 * flow says `tapOn: { id: note_item_by_title_… }` and the id is not there. The
 * title tag was moved to the item's outer node for exactly that reason, and
 * `NotesScreenTest` pins the single card. This test generalises it to the
 * screens the flows actually drive.
 *
 * ## The tags, and where they come from
 *
 * From the notes flows under `Maestro/flows/notes`:
 *
 * - notes list — `notes_quick_add_input`, `note_item_by_title_<title>`,
 *   `note_filter_chip_all` / `_pinned` / `_archived`;
 * - note editor — `note_editor_title_input`, `note_editor_body`,
 *   `note_editor_save`;
 * - note preview — `note_action_edit`.
 *
 * Not covered here, and deliberately so:
 *
 * - `nav_menu_button` and `menu_notes` are the *Android* shell's ids. On
 *   desktop the drawer entry carries a `contentDescription` ("Notes"), not a
 *   `testTag`, so there is no tag to assert on this platform.
 * - `notes_list` (the list container's tag) is deliberately not asserted: a
 *   `LazyColumn`'s tag does not surface through the semantics layer on either
 *   platform, which the notes flows themselves note ("The list container's
 *   testTag is not surfaced by the accessibility layer"). Asserting it would
 *   assert something that cannot hold.
 */
@OptIn(ExperimentalTestApi::class)
@Tag("slow")
@Tag("slow:compose")
class NotesFlowTagReachabilityFlowTest {

    /**
     * The notes list carries every id the flows tap on it.
     *
     * The filter chips are the ones a flow switches the visible set with
     * (`tapOn: { id: note_filter_chip_pinned }`), and the quick-add field is
     * the control a flow types a title into.
     */
    @Test
    fun the_notes_list_exposes_every_tag_the_flows_address() = runDesktopAppTest(checkA11y = true) {
        tapTab("Notes")

        assertTagInMergedTree(TestTags.NOTES_QUICK_ADD_INPUT, "notes list")
        assertTagInMergedTree(TestTags.noteFilterChip("All"), "notes list")
        assertTagInMergedTree(TestTags.noteFilterChip("Pinned"), "notes list")
        assertTagInMergedTree(TestTags.noteFilterChip("Archived"), "notes list")
    }

    /**
     * The note editor carries every id the flows type into and tap on it.
     *
     * The editor is reached the way the flow reaches it: the quick-add field
     * submits, which creates the note and opens it in the editor.
     */
    @Test
    fun the_note_editor_exposes_every_tag_the_flows_address() = runDesktopAppTest(checkA11y = true) {
        tapTab("Notes")
        typeIntoTag(TestTags.NOTES_QUICK_ADD_INPUT, "Groceries")
        submitTag(TestTags.NOTES_QUICK_ADD_INPUT)

        assertTagInMergedTree(TestTags.NOTE_EDITOR_TITLE_INPUT, "note editor")
        assertTagInMergedTree(TestTags.NOTE_EDITOR_BODY, "note editor")
        assertTagInMergedTree(TestTags.NOTE_EDITOR_SAVE, "note editor")
    }

    /**
     * A note row is addressable by the title the flow typed.
     *
     * The flow never learns the generated ULID, so the title is the only handle
     * it has — and the title tag has to be on the item's *outer* node, because
     * the card below it merges its descendants and would swallow the tag.
     */
    @Test
    fun a_note_row_is_addressable_by_its_title_in_the_merged_tree() =
        runDesktopAppTest(checkA11y = true) {
            tapTab("Notes")
            typeIntoTag(TestTags.NOTES_QUICK_ADD_INPUT, "Groceries")
            submitTag(TestTags.NOTES_QUICK_ADD_INPUT)
            // The editor autosaves; saving does not leave it, so the flow
            // leaves explicitly.
            clickTag(TestTags.NOTE_EDITOR_SAVE)
            goBack()

            assertTagInMergedTree(TestTags.noteItemByTitle("Groceries"), "notes list")
        }

    /**
     * The note preview carries the id the flow waits for before it edits.
     *
     * `01-edit-body.yaml` taps the row, waits for `note_action_edit` and then
     * asserts the body in the *preview* — the preview is the third notes screen
     * the flows drive, and its Edit action is the way into the editor.
     */
    @Test
    fun the_note_preview_exposes_the_edit_action_the_flows_wait_for() =
        runDesktopAppTest(checkA11y = true) {
            tapTab("Notes")
            typeIntoTag(TestTags.NOTES_QUICK_ADD_INPUT, "Groceries")
            submitTag(TestTags.NOTES_QUICK_ADD_INPUT)
            clickTag(TestTags.NOTE_EDITOR_SAVE)
            goBack()

            clickTag(TestTags.noteItemByTitle("Groceries"))

            assertTagInMergedTree(TestTags.noteAction("edit"), "note preview")
        }
}
