package com.singularity.todo.feature.flows.tasks

import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.test.helpers.DesktopShell
import com.singularity.todo.test.helpers.awaitTag
import com.singularity.todo.test.helpers.awaitTagGone
import com.singularity.todo.test.helpers.runDesktopAppTest
import com.singularity.todo.test.helpers.tapTab
import org.junit.Test

/**
 * Desktop mirror of `Maestro/flows/tasks/create-task.yaml`, covering the editor
 * itself.
 *
 * One selector difference from Android: the flow taps `TestTags.TASKS_FAB`,
 * while the desktop shell renders a plain `FloatingActionButton` carrying only
 * the `Add task` contentDescription.
 *
 * ## The round trip
 *
 * Most of this file covers the editor in isolation, but the last test carries a
 * task all the way into the agenda. That was previously impossible: a task saved
 * without a due date belongs to the Inbox preset's "No Date" bucket, and the tabs
 * shared one `AgendaViewModel`, so no tab ever evaluated its own definition and the
 * row could not appear anywhere. With that fixed, an undated task is the strongest
 * available assertion — a stale ViewModel would render Today's sections instead and
 * the "No Date" header would simply be missing. See [AgendaTabDefinitionFlowTest].
 */
@OptIn(ExperimentalTestApi::class)
class CreateTaskFlowTest {

    @Test
    fun the_fab_opens_the_task_editor() = runDesktopAppTest {
        onNodeWithContentDescription(DesktopShell.FAB_ADD_TASK).performClick()

        onNodeWithTag(TestTags.TASK_EDITOR_TITLE_INPUT).assertIsDisplayed()
        onNodeWithTag(TestTags.TASK_EDITOR_SAVE).assertIsDisplayed()
    }

    @Test
    fun save_stays_disabled_until_the_title_is_entered() = runDesktopAppTest {
        onNodeWithContentDescription(DesktopShell.FAB_ADD_TASK).performClick()

        // DraftMviViewModel derives this from validate(draft), which rejects a
        // blank title — so an initially disabled button is the contract, and a
        // click that silently did nothing would be indistinguishable from a
        // broken editor if this were not asserted.
        onNodeWithTag(TestTags.TASK_EDITOR_SAVE).assertIsNotEnabled()
    }

    @Test
    fun typing_a_title_enables_save() = runDesktopAppTest {
        onNodeWithContentDescription(DesktopShell.FAB_ADD_TASK).performClick()

        onNodeWithTag(TestTags.TASK_EDITOR_TITLE_INPUT).performTextReplacement("Buy milk")

        // Assert the field before saving: a BasicTextField can drop a
        // composition, and without this the failure would resurface later as a
        // missing row, which reads as a save-button bug rather than an input one.
        onNodeWithTag(TestTags.TASK_EDITOR_TITLE_INPUT).assertTextEquals("Buy milk")
        onNodeWithTag(TestTags.TASK_EDITOR_SAVE).assertIsEnabled()
    }

    @Test
    fun the_editor_can_be_left_with_the_shell_back_arrow() = runDesktopAppTest {
        onNodeWithContentDescription(DesktopShell.FAB_ADD_TASK).performClick()
        onNodeWithTag(TestTags.TASK_EDITOR_TITLE_INPUT).performTextReplacement("Buy milk")

        // Pushing the editor turns the shell's hamburger into a back arrow, so
        // the drawer is unreachable until the push is popped. This is desktop
        // behaviour with no Android counterpart, since the bottom bar stays put.
        onNodeWithContentDescription(DesktopShell.BACK).assertIsDisplayed()
        onNodeWithContentDescription(DesktopShell.BACK).performClick()

        // The pop is asynchronous: the editor stays composed until the shell has
        // navigated back, so a one-shot "does not exist" right after the click
        // races the transition and fails intermittently under machine load.
        // The hamburger has no testTag, so wait on the editor leaving instead.
        awaitTagGone(TestTags.TASK_EDITOR_TITLE_INPUT)
        onNodeWithContentDescription(DesktopShell.HAMBURGER).assertIsDisplayed()
    }

    /**
     * Save, then find the task in the agenda.
     *
     * No due date is entered, so the task can only surface under Inbox's "No Date"
     * section — a header that the Today preset does not define at all. That makes
     * this the one assertion in the desktop suite that fails if the agenda ever
     * falls back to a stale ViewModel again.
     */
    @Test
    fun a_saved_task_without_a_due_date_appears_under_inbox_no_date() = runDesktopAppTest {
        onNodeWithContentDescription(DesktopShell.FAB_ADD_TASK).performClick()
        onNodeWithTag(TestTags.TASK_EDITOR_TITLE_INPUT).performTextReplacement("Call the dentist")
        onNodeWithTag(TestTags.TASK_EDITOR_SAVE).performClick()

        // Saving pops the editor back to the agenda, so the drawer is reachable
        // again and no explicit goBack() is needed before changing tab.
        tapTab("Inbox")

        awaitTag(TestTags.agendaSection("No Date")).assertIsDisplayed()
        awaitTag(TestTags.taskItem("Call the dentist")).assertIsDisplayed()
    }

    /**
     * Positive control: proves that a missing tag produces an actionable error
     * listing the nearest available tags rather than a bare "not found" message.
     *
     * The explainer fires when `awaitTag` times out — the exception message contains
     * the wanted tag and a sample of what tags were found.
     *
     * Run with:
     * ```bash
     * ./gradlew :desktopApp:test --tests '*CreateTaskFlowTest*a_missing_tag_emits_nearby_tags*'
     * ```
     * To also verify the failure bundle is written, add `--info` and grep for
     * `FailureBundle\|diagnostics`.
     */
    @Test
    fun a_missing_tag_emits_nearby_tags_in_the_failure_message() {
        val thrown = runCatching {
            runDesktopAppTest {
                // A tag that definitely does not exist in any screen.
                awaitTag("nonexistent-tag-for-positive-control").assertIsDisplayed()
            }
        }.exceptionOrNull()

        // The harness re-throws after adding the bundle as suppressed.
        val message = (thrown as? Throwable)?.message ?: ""

        // Tag-explainer fires on timeout
        assert(message.contains("nonexistent-tag-for-positive-control")) {
            "Error message should name the wanted tag, got: $message"
        }
        assert(message.contains("Nearby tags:") || message.contains("All available tags")) {
            "Error message should list nearby or available tags, got: $message"
        }
    }
}
