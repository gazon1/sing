@file:OptIn(ExperimentalTestApi::class)

package com.singularity.todo.feature.search

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import com.singularity.todo.feature.search.presentation.RenameSearchDialog
import com.singularity.todo.feature.search.presentation.SaveSearchDialog
import com.singularity.todo.test.helpers.runIsolatedComposeTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test

/**
 * Desktop JVM Compose UI tests for the search save/rename dialogs.
 * These are pure presentational tests — no ViewModel, no database, no Koin.
 *
 * Run with: ./gradlew :desktopApp:test --tests "*SearchPresentationUiTest"
 */
@OptIn(ExperimentalTestApi::class)
@Tag("slow")
class SearchPresentationUiTest {

    // ─── SaveSearchDialog ───────────────────────────────────────────────────────

    @Test
    fun `SaveSearchDialog pre-fills the name field with the initial value`() =
        runIsolatedComposeTest {
            setContent {
                SaveSearchDialog(
                    initialName = "my saved query",
                    onDismiss = {},
                    onSave = {},
                )
            }

            onNode(hasSetTextAction()).assertIsEnabled()
            onNodeWithText("my saved query").assertIsEnabled()
        }

    @Test
    fun `SaveSearchDialog disabled Save when name is blank`() = runIsolatedComposeTest {
        setContent {
            SaveSearchDialog(
                initialName = "some query",
                onDismiss = {},
                onSave = {},
            )
        }

        // Clear the pre-filled name
        onNode(hasSetTextAction()).performTextClearance()

        // Save button must be disabled for blank input
        onNodeWithText("Save").assertIsNotEnabled()
    }

    @Test
    fun `SaveSearchDialog calls onSave with trimmed name on confirm`() =
        runIsolatedComposeTest {
            var capturedName: String? = null
            setContent {
                SaveSearchDialog(
                    initialName = "  my query  ",
                    onDismiss = {},
                    onSave = { capturedName = it },
                )
            }

            onNodeWithText("Save").performClick()

            // onSave receives the trimmed value, not the original
            assert(capturedName == "my query") { "expected trimmed 'my query', got '$capturedName'" }
        }

    @Test
    fun `SaveSearchDialog calls onDismiss when Cancel is clicked`() =
        runIsolatedComposeTest {
            var dismissed = false
            setContent {
                SaveSearchDialog(
                    initialName = "query",
                    onDismiss = { dismissed = true },
                    onSave = {},
                )
            }

            onNodeWithText("Cancel").performClick()

            assert(dismissed) { "expected onDismiss to be called" }
        }

    // ─── RenameSearchDialog ────────────────────────────────────────────────────

    @Test
    fun `RenameSearchDialog pre-fills the name field with the current name`() =
        runIsolatedComposeTest {
            setContent {
                RenameSearchDialog(
                    currentName = "existing name",
                    onDismiss = {},
                    onRename = {},
                )
            }

            onNodeWithText("existing name").assertIsEnabled()
        }

    @Test
    fun `RenameSearchDialog disables Rename when name is blank`() =
        runIsolatedComposeTest {
            setContent {
                RenameSearchDialog(
                    currentName = "existing name",
                    onDismiss = {},
                    onRename = {},
                )
            }

            onNode(hasSetTextAction()).performTextClearance()

            onNodeWithText("Rename").assertIsNotEnabled()
        }

    @Test
    fun `RenameSearchDialog calls onRename with trimmed name on confirm`() =
        runIsolatedComposeTest {
            var capturedName: String? = null
            setContent {
                RenameSearchDialog(
                    currentName = "old name",
                    onDismiss = {},
                    onRename = { capturedName = it },
                )
            }

            onNode(hasSetTextAction()).performTextClearance()
            onNode(hasSetTextAction()).performTextInput("  new name  ")
            onNodeWithText("Rename").performClick()

            assert(capturedName == "new name") { "expected trimmed 'new name', got '$capturedName'" }
        }

    @Test
    fun `RenameSearchDialog calls onDismiss when Cancel is clicked`() =
        runIsolatedComposeTest {
            var dismissed = false
            setContent {
                RenameSearchDialog(
                    currentName = "some name",
                    onDismiss = { dismissed = true },
                    onRename = {},
                )
            }

            onNodeWithText("Cancel").performClick()

            assert(dismissed) { "expected onDismiss to be called" }
        }

    @Test
    fun `SaveSearchDialog trims whitespace before calling onSave`() =
        runIsolatedComposeTest {
            var capturedName: String? = null
            setContent {
                SaveSearchDialog(
                    initialName = "  spaced query  ",
                    onDismiss = {},
                    onSave = { capturedName = it },
                )
            }

            // User edits the pre-filled value
            onNode(hasSetTextAction()).performTextClearance()
            onNode(hasSetTextAction()).performTextInput("  another query  ")
            onNodeWithText("Save").performClick()

            assert(capturedName == "another query") {
                "expected 'another query', got '$capturedName'"
            }
        }
}
