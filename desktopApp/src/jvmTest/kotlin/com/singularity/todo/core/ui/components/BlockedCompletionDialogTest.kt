@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.singularity.todo.core.ui.components

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import com.singularity.todo.test.helpers.assertNodeDisplayed
import com.singularity.todo.test.helpers.assertTextDisplayed
import com.singularity.todo.test.helpers.runIsolatedComposeTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The confirmation shown when a task with unfinished prerequisites is closed.
 *
 * The dialog's whole job is to name what is in the way. A confirmation that says
 * "this task is blocked" without saying by what is a question the user cannot
 * answer, so the names are asserted here — a dialog that stopped naming them
 * would still look right.
 */
@Tag("fast")
class BlockedCompletionDialogTest {

    @Test
    fun `the dialog names the open prerequisites`() = runIsolatedComposeTest {
        setContent {
            BlockedCompletionDialog(
                taskTitle = "Ship the release",
                blockerTitles = listOf("Write the changelog", "Run the tests"),
                onConfirm = {},
                onDismiss = {},
            )
        }

        // The body is one text node with the names on separate lines, so the
        // match has to be a substring match — an exact one would look for a node
        // whose whole text is the name, and there is no such node.
        assertNodeDisplayed(hasText("Write the changelog", substring = true), "the first prerequisite")
        assertNodeDisplayed(hasText("Run the tests", substring = true), "the second prerequisite")
        assertNodeDisplayed(hasText("Ship the release", substring = true), "the task")
    }

    @Test
    fun `the dialog offers both answers`() = runIsolatedComposeTest {
        setContent {
            BlockedCompletionDialog(
                taskTitle = "Ship the release",
                blockerTitles = listOf("Write the changelog"),
                onConfirm = {},
                onDismiss = {},
            )
        }

        assertTextDisplayed(BLOCKED_COMPLETION_CONFIRM)
        assertTextDisplayed(BLOCKED_COMPLETION_DISMISS)
    }

    @Test
    fun `the wording names every prerequisite`() {
        val text = blockedCompletionText("Ship the release", listOf("Write the changelog", "Run the tests"))

        assertTrue("Write the changelog" in text, "the first prerequisite must be named: $text")
        assertTrue("Run the tests" in text, "the second prerequisite must be named: $text")
        assertTrue("Ship the release" in text, "the task must be named: $text")
    }

    @Test
    fun `the wording survives a task with no title`() {
        val text = blockedCompletionText("", listOf("Write the changelog"))

        assertTrue("Write the changelog" in text, "the prerequisite must be named: $text")
        assertTrue("This task" in text, "a task with no title still has to be referred to: $text")
    }
}
