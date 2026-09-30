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
import com.singularity.todo.test.helpers.runDesktopAppTest
import org.junit.Test

/**
 * Desktop mirror of `Maestro/flows/tasks/create-task.yaml`, covering the editor
 * itself.
 *
 * One selector difference from Android: the flow taps `TestTags.TASKS_FAB`,
 * while the desktop shell renders a plain `FloatingActionButton` carrying only
 * the `Add task` contentDescription.
 *
 * ## Why this stops at the editor
 *
 * The flow does **not** assert that the saved task appears in the agenda. It
 * does get created — `TaskRepository.observeAll()` reports it — but a task
 * created without a due date belongs to the Inbox preset's "No Date" bucket,
 * and this build renders no row for it. [TaskRowFlowTest] documents the gap and
 * pins the dated behaviour that is observable. Asserting the undated row here
 * would make this test red for a reason that has nothing to do with the editor.
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

        onNodeWithTag(TestTags.TASK_EDITOR_TITLE_INPUT).assertDoesNotExist()
        onNodeWithContentDescription(DesktopShell.HAMBURGER).assertIsDisplayed()
    }
}
