package com.singularity.todo.feature.flows.notes

import androidx.compose.ui.test.ExperimentalTestApi
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.test.helpers.assertTagDisplayed
import com.singularity.todo.test.helpers.assertTagTextEquals
import com.singularity.todo.test.helpers.runDesktopAppTest
import com.singularity.todo.test.helpers.submitTag
import com.singularity.todo.test.helpers.tapTab
import com.singularity.todo.test.helpers.typeIntoTag
import org.junit.jupiter.api.Tag
import kotlin.test.Test

/**
 * The note editor's title field, and the direction a field-level test cannot reach.
 *
 * Creating a note from the quick-add field opens the editor for it, and the editor
 * shows the title the ViewModel already holds. That is the *app state → field* direction:
 * the ViewModel created the note and knows its title, and the field has to show it. A
 * field that started empty would show an empty title for a note that has one, and the
 * autosave would write the empty title back over it.
 *
 * The title tag is on the field itself, and the field is not merged into anything that
 * would swallow it — the note *card*'s title tag is a different story, and the card is
 * addressed by its visible text for that reason.
 */
@OptIn(ExperimentalTestApi::class)
@Tag("slow")
class NoteEditorFieldFlowTest {

    @Test
    fun the_note_editor_shows_the_title_of_the_note() = runDesktopAppTest(checkA11y = true) {
        tapTab("Notes")

        // The quick-add creates the note and opens the editor for it.
        typeIntoTag(TestTags.NOTES_QUICK_ADD_INPUT, "Groceries")
        submitTag(TestTags.NOTES_QUICK_ADD_INPUT)

        assertTagDisplayed(TestTags.NOTE_EDITOR_TITLE_INPUT)
        assertTagTextEquals(TestTags.NOTE_EDITOR_TITLE_INPUT, "Groceries")
    }
}
