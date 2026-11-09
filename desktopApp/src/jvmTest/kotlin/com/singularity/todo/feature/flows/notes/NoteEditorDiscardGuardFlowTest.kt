package com.singularity.todo.feature.flows.notes

import androidx.compose.ui.test.ExperimentalTestApi
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.test.helpers.assertTagDisplayed
import com.singularity.todo.test.helpers.assertTagNotExists
import com.singularity.todo.test.helpers.assertTagTextEquals
import com.singularity.todo.test.helpers.awaitTag
import com.singularity.todo.test.helpers.awaitTagGone
import com.singularity.todo.test.helpers.clickTag
import com.singularity.todo.test.helpers.runDesktopAppTest
import com.singularity.todo.test.helpers.submitTag
import com.singularity.todo.test.helpers.tapTab
import com.singularity.todo.test.helpers.typeIntoTag
import org.junit.jupiter.api.Tag
import kotlin.test.Test

/**
 * Leaving the note editor with text it has not stored yet.
 *
 * A back press used to dispatch `Close` and pop at once, so a keystroke that arrived
 * inside the autosave's debounce window was thrown away without a word. The editor now
 * asks first, and these two tests are the two directions of that question: a dirty draft
 * asks, and a draft the repository already holds does not.
 *
 * The dirty case depends on the debounce being real time here — the repository write has
 * not happened yet when the back button is pressed, which is exactly the window the bug
 * lived in. The clean case reaches a stored draft through the editor's own save rather
 * than by waiting out the debounce: the "Saved" pill appears after the write completed,
 * so waiting for it is waiting for the draft to be clean.
 */
@OptIn(ExperimentalTestApi::class)
@Tag("slow")
@Tag("slow:compose")
class NoteEditorDiscardGuardFlowTest {
    @Test
    fun back_with_unstored_text_asks_before_discarding() =
        runDesktopAppTest(checkA11y = true) {
            tapTab("Notes")

            // The quick-add creates the note and opens the editor for it; the body typed
            // here is what the autosave has not written yet.
            typeIntoTag(TestTags.NOTES_QUICK_ADD_INPUT, "Groceries")
            submitTag(TestTags.NOTES_QUICK_ADD_INPUT)
            assertTagDisplayed(TestTags.NOTE_EDITOR_BODY)
            typeIntoTag(TestTags.NOTE_EDITOR_BODY, "Discuss roadmap")

            clickTag(TestTags.NOTE_EDITOR_BACK)

            // The question is asked, and the editor is still there while it is open:
            // nothing is dropped before the user answers.
            assertTagDisplayed(TestTags.Dialog.title("Discard changes?"))
            assertTagDisplayed(TestTags.NOTE_EDITOR_BODY)

            // "Keep editing" leaves the text alone.
            clickTag(TestTags.Dialog.DISMISS)
            assertTagNotExists(TestTags.Dialog.title("Discard changes?"))
            assertTagDisplayed(TestTags.NOTE_EDITOR_BODY)
            assertTagTextEquals(TestTags.NOTE_EDITOR_BODY, "Discuss roadmap")
        }

    @Test
    fun back_with_a_stored_draft_leaves_without_asking() =
        runDesktopAppTest(checkA11y = true) {
            tapTab("Notes")
            typeIntoTag(TestTags.NOTES_QUICK_ADD_INPUT, "Groceries")
            submitTag(TestTags.NOTES_QUICK_ADD_INPUT)
            assertTagDisplayed(TestTags.NOTE_EDITOR_BODY)

            typeIntoTag(TestTags.NOTE_EDITOR_BODY, "Discuss roadmap")
            clickTag(TestTags.NOTE_EDITOR_SAVE)

            // The pill is shown *after* the write, so waiting for it is waiting for the
            // draft to be clean. Without it the back press below races the save and the
            // dialog appears for text that is already on disk.
            awaitTag(TestTags.NOTE_EDITOR_SAVED_INDICATOR)

            clickTag(TestTags.NOTE_EDITOR_BACK)

            assertTagNotExists(TestTags.Dialog.title("Discard changes?"))
            awaitTagGone(TestTags.NOTE_EDITOR_BODY)
        }

    @Test
    fun back_with_unstored_text_discards_when_confirmed() =
        runDesktopAppTest(checkA11y = true) {
            tapTab("Notes")
            typeIntoTag(TestTags.NOTES_QUICK_ADD_INPUT, "Groceries")
            submitTag(TestTags.NOTES_QUICK_ADD_INPUT)
            assertTagDisplayed(TestTags.NOTE_EDITOR_BODY)
            typeIntoTag(TestTags.NOTE_EDITOR_BODY, "Discuss roadmap")

            clickTag(TestTags.NOTE_EDITOR_BACK)
            assertTagDisplayed(TestTags.Dialog.title("Discard changes?"))

            clickTag(TestTags.Dialog.CONFIRM)

            assertTagNotExists(TestTags.Dialog.title("Discard changes?"))
            awaitTagGone(TestTags.NOTE_EDITOR_BODY)
        }
}
