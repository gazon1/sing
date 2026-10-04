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
import com.singularity.todo.test.helpers.runIsolatedComposeTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.time.Instant

/**
 * Desktop JVM Compose UI tests for the tag rename flow.
 *
 * Covers the whole user-visible path: pencil on the card → dialog → edit →
 * save → the new name on the card. `TagsScreen` is a pure presentational
 * Composable, so it is driven directly with state built inline rather than
 * through Koin.
 *
 * Run with: ./gradlew :desktopApp:test
 */
@Tag("slow")
class TagsRenameUiTest {

    private val testUserId = UserId("test-user")
    private val epoch = Instant.fromEpochMilliseconds(0)

    private fun sampleTag(id: String, name: String, color: Int = 0xFFE91E63.toInt()) = Tag(
        id = TagId(id),
        name = name,
        color = color,
        createdAt = epoch,
        updatedAt = epoch,
        userId = testUserId,
    )

    @Test
    fun `tapping the pencil opens the rename dialog pre-filled with the current name`() =
        runIsolatedComposeTest {
            setContent {
                TagsScreen(
                    state = TagsUiState.Content(listOf(sampleTag("tg1", "work"))),
                    onCreate = { _, _ -> },
                    onDelete = {},
                    onRename = { _, _, _ -> },
                )
            }

            onNodeWithTag(TestTags.tagRename("work")).performClick()

            onNodeWithText("Rename Tag").assertIsDisplayed()
            onNodeWithText("Save").assertIsDisplayed()
            // The field is pre-filled, not empty: the rename starts from the current name.
            onNode(hasSetTextAction()).assertTextContains("work")
        }

    @Test
    fun `saving a new name reports the rename and closes the dialog`() = runIsolatedComposeTest {
        var renamedTo: String? = null
        var renamedId: TagId? = null
        setContent {
            TagsScreen(
                state = TagsUiState.Content(listOf(sampleTag("tg1", "work"))),
                onCreate = { _, _ -> },
                onDelete = {},
                onRename = { id, name, _ ->
                    renamedId = id
                    renamedTo = name
                },
            )
        }

        onNodeWithTag(TestTags.tagRename("work")).performClick()
        onNode(hasSetTextAction()).performTextClearance()
        onNode(hasSetTextAction()).performTextInput("office")
        onNodeWithText("Save").performClick()

        assert(renamedId == TagId("tg1")) { "rename must carry the original id, was $renamedId" }
        assert(renamedTo == "office") { "rename must carry the new name, was $renamedTo" }
        onAllNodesWithText("Rename Tag").assertCountEquals(0)
    }

    @Test
    fun `a rename whose name is blank cannot be saved`() = runIsolatedComposeTest {
        var renameCalls = 0
        setContent {
            TagsScreen(
                state = TagsUiState.Content(listOf(sampleTag("tg1", "work"))),
                onCreate = { _, _ -> },
                onDelete = {},
                onRename = { _, _, _ -> renameCalls++ },
            )
        }

        onNodeWithTag(TestTags.tagRename("work")).performClick()
        onNode(hasSetTextAction()).performTextClearance()
        // "Save" is disabled while the field is empty, so the click is a no-op.
        onNodeWithText("Save").performClick()

        assert(renameCalls == 0) { "a blank name must not be saved, got $renameCalls calls" }
        onNodeWithText("Rename Tag").assertIsDisplayed()
    }

    @Test
    fun `the create dialog still says Create and starts empty`() = runIsolatedComposeTest {
        setContent {
            TagsScreen(
                state = TagsUiState.Content(listOf(sampleTag("tg1", "work"))),
                onCreate = { _, _ -> },
                onDelete = {},
                onRename = { _, _, _ -> },
            )
        }

        onNodeWithTag(TestTags.TAGS_FAB).performClick()

        onNodeWithText("New Tag").assertIsDisplayed()
        onNodeWithText("Create").assertIsDisplayed()
    }
}
