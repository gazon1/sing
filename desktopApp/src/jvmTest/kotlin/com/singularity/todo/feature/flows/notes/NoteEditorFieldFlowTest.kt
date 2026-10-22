package com.singularity.todo.feature.flows.notes

import androidx.compose.ui.test.ExperimentalTestApi
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.feature.notes.domain.port.NotesRepository
import com.singularity.todo.test.helpers.assertTagDisplayed
import com.singularity.todo.test.helpers.assertTagTextEquals
import com.singularity.todo.test.helpers.clickTag
import com.singularity.todo.test.helpers.runDesktopAppTest
import com.singularity.todo.test.helpers.submitTag
import com.singularity.todo.test.helpers.tapTab
import com.singularity.todo.test.helpers.typeIntoTag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The note editor's title field, the body, and the direction a field-level test
 * cannot reach.
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
@Tag("slow:compose")
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

    @Test
    fun the_body_typed_in_the_editor_reaches_the_note() = runDesktopAppTest(checkA11y = true) { koin ->
        // The body is a rich-text editor, not a text field: the text lives *inside* the
        // editor's state object, and that object is the same instance for the whole
        // session. A dispatch keyed on the object rather than on its contents therefore
        // never fires — and a body that is never dispatched is a body that is never
        // saved. The user types, the editor shows the text, and the note stays empty:
        // the loss is silent, and nothing on the screen says so.
        //
        // This is the regression test for that: the text has to reach the *note*,
        // not just the editor's buffer.
        tapTab("Notes")
        typeIntoTag(TestTags.NOTES_QUICK_ADD_INPUT, "Groceries")
        submitTag(TestTags.NOTES_QUICK_ADD_INPUT)

        assertTagDisplayed(TestTags.NOTE_EDITOR_BODY)
        typeIntoTag(TestTags.NOTE_EDITOR_BODY, "Discuss roadmap")
        assertTagTextEquals(TestTags.NOTE_EDITOR_BODY, "Discuss roadmap")

        // The save button flushes the editor before the ViewModel reads the draft:
        // the dispatch of the last keystroke is asynchronous, and a save that ran
        // before it would persist the previous body.
        clickTag(TestTags.NOTE_EDITOR_SAVE)

        val repository = koin.get<NotesRepository>()
        // The write happens on the ViewModel's own background scope, and the debounce
        // in front of it is real time — a suspending timeout on the test's virtual
        // clock fires long before the write could happen. The wait therefore runs on
        // a real dispatcher.
        var lastSeen: String? = null
        val body = withContext(Dispatchers.Default) {
            withTimeoutOrNull(10.seconds) {
                repository.watchRootNotes()
                    .map { notes ->
                        notes.firstOrNull { it.title == "Groceries" }?.bodyHtml.also { html ->
                            lastSeen = html
                        }
                    }
                    .firstOrNull { it?.contains("Discuss roadmap") == true }
            }
        }
        assertTrue(
            body != null,
            "the body did not reach the note; the note's body was: $lastSeen",
        )
    }
}
