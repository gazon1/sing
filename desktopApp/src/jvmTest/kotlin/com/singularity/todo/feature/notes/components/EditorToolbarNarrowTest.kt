@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.singularity.todo.feature.notes.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import com.mohamedrejeb.richeditor.model.HeadingStyle
import com.mohamedrejeb.richeditor.model.RichTextState
import com.singularity.todo.test.helpers.assertContentDescriptionDisplayed
import com.singularity.todo.test.helpers.assertTextDisplayed
import com.singularity.todo.test.helpers.runIsolatedComposeTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The formatting bar at a phone width.
 *
 * The bar is the editor's `Scaffold` bottom bar. It used to draw eleven 48 dp
 * buttons in one non-scrolling `Row` — 528 dp in a 360 dp window — so Redo, the
 * overflow menu and the AI action were outside the window, and the menu is the
 * only way to a heading, an alignment or a link. It also drew Undo and Redo in
 * the enabled colour on a fresh note, where `undo()` returns false and nothing
 * happens.
 *
 * These tests say what is on screen, not what is in the tree: a control only a
 * test can reach is a control the user cannot reach.
 */
@Tag("fast")
class EditorToolbarNarrowTest {
    @Composable
    private fun Bar(
        state: RichTextState,
        onLinkClick: () -> Unit = {},
        onInternalLinkClick: () -> Unit = {},
    ) {
        MaterialTheme {
            EditorToolbar(
                richTextState = state,
                onHtmlChange = {},
                onAiClick = {},
                onLinkClick = onLinkClick,
                onInternalLinkClick = onInternalLinkClick,
            )
        }
    }

    @Test
    fun `every bar control is on screen at a phone width`() =
        runIsolatedComposeTest(width = 360, height = 240) {
            val state = RichTextState()

            setContent { Bar(state = state) }

            listOf(
                "Bold",
                "Italic",
                "Underline",
                "Undo",
                "Redo",
                "More formatting options",
                "AI improve",
            ).forEach { assertContentDescriptionDisplayed(it) }
        }

    @Test
    fun `the overflow menu still reaches the headings and both links`() =
        runIsolatedComposeTest(width = 360, height = 800) {
            val state = RichTextState()
            var externalLinks = 0
            var internalLinks = 0

            setContent {
                Bar(
                    state = state,
                    onLinkClick = { externalLinks++ },
                    onInternalLinkClick = { internalLinks++ },
                )
            }

            onNodeWithContentDescription("More formatting options").performClick()

            listOf(
                "Heading 1",
                "Heading 2",
                "Heading 3",
                "Inline code",
                "Align left",
                "Align center",
                "Align right",
                "External link",
                "Internal link",
            ).forEach { assertTextDisplayed(it) }

            onNodeWithText("External link").performClick()

            assertEquals(1, externalLinks)
            assertEquals(0, internalLinks)
        }

    @Test
    fun `undo and redo are unavailable on a fresh note`() =
        runIsolatedComposeTest(width = 360, height = 240) {
            setContent { Bar(state = RichTextState()) }

            onNodeWithContentDescription("Undo").assertIsNotEnabled()
            onNodeWithContentDescription("Redo").assertIsNotEnabled()
        }

    @Test
    fun `undo and redo follow the history`() =
        runIsolatedComposeTest(width = 360, height = 240) {
            // A heading change records a history entry, so Undo has something to
            // undo before the bar is ever composed.
            val state = RichTextState().apply { setHtml("<p>hello</p>") }
            state.setHeadingStyle(HeadingStyle.H1)

            setContent { Bar(state = state) }

            onNodeWithContentDescription("Undo").assertIsEnabled()
            onNodeWithContentDescription("Redo").assertIsNotEnabled()

            onNodeWithContentDescription("Undo").performClick()

            onNodeWithContentDescription("Redo").assertIsEnabled()
        }
}
