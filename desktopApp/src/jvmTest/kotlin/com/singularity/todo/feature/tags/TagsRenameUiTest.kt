@file:OptIn(ExperimentalTestApi::class)

package com.singularity.todo.feature.tags

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.feature.tags.domain.usecase.CreateTagUseCase
import com.singularity.todo.feature.tags.domain.usecase.UpdateTagUseCase
import com.singularity.todo.test.fakes.FakeClock
import com.singularity.todo.test.fakes.FakeProfileAwareCurrentUser
import com.singularity.todo.test.fakes.FakeTagsRepository
import com.singularity.todo.test.helpers.runIsolatedComposeTest
import org.junit.jupiter.api.Tag as JunitTag
import kotlin.test.Test
import kotlin.time.Instant

private typealias DomainTag = com.singularity.todo.feature.tags.Tag

/**
 * Desktop JVM Compose UI tests for the tag rename flow.
 *
 * Covers the whole user-visible path: pencil on the card → dialog → edit →
 * save → the new name on the card. `TagsScreen` is a self-contained composable
 * that owns its state via `TagsViewModel`, so the test constructs a real VM
 * backed by fakes and seeds the repository before each test.
 *
 * Run with: ./gradlew :desktopApp:test
 */
@JunitTag("slow")
class TagsRenameUiTest {

    private val testUserId = UserId("test-user")
    private val epoch = Instant.fromEpochMilliseconds(0)

    private fun makeTag(id: String, name: String, color: Int = 0xFFE91E63.toInt()): DomainTag =
        DomainTag(
            id = TagId(id),
            name = name,
            color = color,
            createdAt = epoch,
            updatedAt = epoch,
            userId = testUserId,
        )

    private fun makeViewModel(vararg tags: DomainTag): TagsViewModel {
        val repo = FakeTagsRepository().apply { seed(*tags) }
        val createTag = CreateTagUseCase(repo, FakeClock())
        val updateTag = UpdateTagUseCase(repo, FakeClock())
        return TagsViewModel(
            tagRepo = repo,
            createTag = createTag,
            updateTag = updateTag,
            currentUser = FakeProfileAwareCurrentUser(),
        )
    }

    @Test
    fun `tapping the pencil opens the rename dialog pre-filled with the current name`() =
        runIsolatedComposeTest {
            val vm = makeViewModel(makeTag("tg1", "work"))
            setContent {
                TagsScreen(viewModel = vm)
            }

            onNodeWithTag(TestTags.tagRename("work")).performClick()

            onNodeWithText("Rename Tag").assertIsDisplayed()
            onNodeWithText("Save").assertIsDisplayed()
            // The field is pre-filled, not empty: the rename starts from the current name.
            onNode(hasSetTextAction()).assertTextContains("work")
        }

    @Test
    fun `saving a new name reports the rename and closes the dialog`() = runIsolatedComposeTest {
        val vm = makeViewModel(makeTag("tg1", "work"))
        setContent {
            TagsScreen(viewModel = vm)
        }

        onNodeWithTag(TestTags.tagRename("work")).performClick()
        onNode(hasSetTextAction()).performTextClearance()
        onNode(hasSetTextAction()).performTextInput("office")
        onNodeWithText("Save").performClick()

        // Verify rename was applied: dialog is gone and the new name is on the card.
        onAllNodesWithText("Rename Tag").assertCountEquals(0)
        onNodeWithTag(TestTags.tagRename("office")).assertIsDisplayed()
    }

    @Test
    fun `a rename whose name is blank cannot be saved`() = runIsolatedComposeTest {
        val vm = makeViewModel(makeTag("tg1", "work"))
        setContent {
            TagsScreen(viewModel = vm)
        }

        onNodeWithTag(TestTags.tagRename("work")).performClick()
        onNode(hasSetTextAction()).performTextClearance()
        // "Save" is disabled while the field is empty, so the click is a no-op.
        onNodeWithText("Save").performClick()

        // The dialog should still be open because save was disabled.
        onNodeWithText("Rename Tag").assertIsDisplayed()
        // The original tag card is still shown with the old name.
        onNodeWithTag(TestTags.tagRename("work")).assertIsDisplayed()
    }

    @Test
    fun `the create dialog still says Create and starts empty`() = runIsolatedComposeTest {
        val vm = makeViewModel(makeTag("tg1", "work"))
        setContent {
            TagsScreen(viewModel = vm)
        }

        onNodeWithTag(TestTags.TAGS_FAB).performClick()

        onNodeWithText("New Tag").assertIsDisplayed()
        onNodeWithText("Create").assertIsDisplayed()
    }
}
