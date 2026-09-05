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
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Widget tests for [NotesScreen] + [NotesViewModel].
 *
 * Creates ViewModel directly with all-fake dependencies — no Koin, no real DB, no network.
 */
@RunWith(AndroidJUnit4::class)
class NotesScreenWidgetTest {

    private val testDispatcher = StandardTestDispatcher()

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
    fun `notes screen renders without crashing`() {
        val viewModel = NotesViewModel(
            repo = fakeNotesRepo,
            htmlPort = htmlPort,
            currentUser = fakeCurrentUser,
            idGen = SequenceIdGenerator("note"),
            improveNote = null,
            scopeOverride = null,
        )
        composeRule.setContent {
            NotesScreen(
                onNavigateToNote = {},
                onNavigateToCreateNote = {},
                viewModel = viewModel
            )
        }
        composeRule.waitForIdle()
        // NotesScreen with empty repo shows EmptyState — FAB is always visible
        composeRule.onNodeWithTag(TestTags.NOTES_FAB).assertIsDisplayed()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `FAB is visible`() {
        val viewModel = NotesViewModel(
            repo = fakeNotesRepo,
            htmlPort = htmlPort,
            currentUser = fakeCurrentUser,
            idGen = SequenceIdGenerator("note"),
            improveNote = null,
            scopeOverride = null,
        )
        composeRule.setContent {
            NotesScreen(
                onNavigateToNote = {},
                onNavigateToCreateNote = {},
                viewModel = viewModel
            )
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(TestTags.NOTES_FAB).assertIsDisplayed()
    }
}
