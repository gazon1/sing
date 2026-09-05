package com.singularity.todo.feature.notes

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.singularity.todo.core.auth.Session
import com.singularity.todo.core.ids.SequenceIdGenerator
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.feature.tasks.UserId
import com.singularity.todo.test.fakes.FakeAuthRepository
import com.singularity.todo.test.fakes.FakeCurrentUser
import com.singularity.todo.test.fakes.FakeNotesRepository
import com.singularity.todo.test.fakes.FakeSettingsRepository
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Widget tests for [NoteEditorScreen] + [NotesViewModel].
 *
 * Creates ViewModel directly with all-fake dependencies — no Koin, no real DB, no network.
 */
@RunWith(AndroidJUnit4::class)
class NoteEditorScreenWidgetTest {

    private val fakeAuthRepo = FakeAuthRepository(Session.Anonymous(UserId.anonymous))
    private val fakeCurrentUser = FakeCurrentUser(fakeAuthRepo)
    private val fakeNotesRepo = FakeNotesRepository()
    private val fakeSettingsRepo = FakeSettingsRepository()

    private val htmlPort = object : MarkdownHtmlPort {
        override fun toHtml(markdown: String) = "<p>$markdown</p>"
        override fun toMarkdown(html: String) = html.removePrefix("<p>").removeSuffix("</p>")
    }

    @get:Rule
    val composeRule = createComposeRule()

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `note editor shows title input after create`() {
        val viewModel = NotesViewModel(
            repo = fakeNotesRepo,
            htmlPort = htmlPort,
            currentUser = fakeCurrentUser,
            idGen = SequenceIdGenerator("note"),
            improveNote = null,
            scopeOverride = null,
        )

        // Trigger note creation before setting content
        viewModel.createNote()

        composeRule.setContent {
            NoteEditorScreen(
                noteId = null,
                onBack = {},
                viewModel = viewModel
            )
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(TestTags.NOTE_EDITOR_TITLE_INPUT).assertIsDisplayed()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `note editor shows save button after create`() {
        val viewModel = NotesViewModel(
            repo = fakeNotesRepo,
            htmlPort = htmlPort,
            currentUser = fakeCurrentUser,
            idGen = SequenceIdGenerator("note"),
            improveNote = null,
            scopeOverride = null,
        )

        // Trigger note creation before setting content
        viewModel.createNote()

        composeRule.setContent {
            NoteEditorScreen(
                noteId = null,
                onBack = {},
                viewModel = viewModel
            )
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(TestTags.NOTE_EDITOR_SAVE).assertIsDisplayed()
    }
}
