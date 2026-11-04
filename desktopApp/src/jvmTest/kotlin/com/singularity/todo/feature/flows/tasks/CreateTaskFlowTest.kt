package com.singularity.todo.feature.flows.tasks

import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import com.singularity.todo.core.error.AppError
import com.singularity.todo.core.error.Either
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.state.TaskDraft
import com.singularity.todo.feature.tasks.domain.usecase.CreateTaskFromDraft
import com.singularity.todo.test.helpers.DesktopShell
import com.singularity.todo.test.helpers.assertContentDescriptionDisplayed
import com.singularity.todo.test.helpers.assertTagDisplayed
import com.singularity.todo.test.helpers.assertTagEnabled
import com.singularity.todo.test.helpers.assertTagNotEnabled
import com.singularity.todo.test.helpers.assertTagTextEquals
import com.singularity.todo.test.helpers.assertTextDisplayed
import com.singularity.todo.test.helpers.assertTextNotExists
import com.singularity.todo.test.helpers.awaitTag
import com.singularity.todo.test.helpers.awaitTagGone
import com.singularity.todo.test.helpers.awaitText
import com.singularity.todo.test.helpers.clearTag
import com.singularity.todo.test.helpers.clickContentDescription
import com.singularity.todo.test.helpers.clickSheetClose
import com.singularity.todo.test.helpers.clickTag
import com.singularity.todo.test.helpers.clickTagScrolled
import com.singularity.todo.test.helpers.runDesktopAppTest
import com.singularity.todo.test.helpers.scrollToTag
import com.singularity.todo.test.helpers.tapTab
import com.singularity.todo.test.helpers.typeIntoTag
import kotlinx.coroutines.flow.first
import org.junit.jupiter.api.Tag
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals

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
@Tag("slow")
@Tag("slow:compose")
class CreateTaskFlowTest {
    @Test
    fun the_fab_opens_the_task_editor() =
        runDesktopAppTest(checkA11y = true) {
            clickContentDescription(DesktopShell.FAB_ADD_TASK)

            assertTagDisplayed(TestTags.TASK_EDITOR_TITLE_INPUT)
            assertTagDisplayed(TestTags.TASK_EDITOR_SAVE)
        }

    @Test
    fun save_stays_disabled_until_the_title_is_entered() =
        runDesktopAppTest(checkA11y = true) {
            clickContentDescription(DesktopShell.FAB_ADD_TASK)

            // DraftMviViewModel derives this from validate(draft), which rejects a
            // blank title — so an initially disabled button is the contract, and a
            // click that silently did nothing would be indistinguishable from a
            // broken editor if this were not asserted.
            assertTagNotEnabled(TestTags.TASK_EDITOR_SAVE)
        }

    /**
     * The message reaches the first frame.
     *
     * The editor is opened with nothing in it, which is the moment the person most
     * needs to know what is missing. The message is derived from the draft's
     * validity, not from the last validation result the base class withholds on its
     * first pass, so it is on screen before the first keystroke — and the disabled
     * button and the message tell one story rather than two.
     */
    @Test
    fun a_freshly_opened_editor_says_the_title_is_required() =
        runDesktopAppTest(checkA11y = true) {
            clickContentDescription(DesktopShell.FAB_ADD_TASK)

            assertTagDisplayed(TestTags.TASK_EDITOR_TITLE_ERROR)
            assertTagTextEquals(TestTags.TASK_EDITOR_TITLE_ERROR, "Title is required")
            assertTagNotEnabled(TestTags.TASK_EDITOR_SAVE)
            assertEquals(
                1,
                onAllNodesWithText("Title is required").fetchSemanticsNodes().size,
                "the message is under the field, not repeated in a snackbar",
            )
        }

    @Test
    fun typing_a_title_enables_save() =
        runDesktopAppTest(checkA11y = true) {
            clickContentDescription(DesktopShell.FAB_ADD_TASK)

            typeIntoTag(TestTags.TASK_EDITOR_TITLE_INPUT, "Buy milk")

            // Assert the field before saving: a BasicTextField can drop a
            // composition, and without this the failure would resurface later as a
            // missing row, which reads as a save-button bug rather than an input one.
            assertTagTextEquals(TestTags.TASK_EDITOR_TITLE_INPUT, "Buy milk")
            assertTagEnabled(TestTags.TASK_EDITOR_SAVE)
        }

    /**
     * The validation message belongs under the field, not in a snackbar.
     *
     * It used to be reachable only as a transient toast, gone before it can be
     * read, and the empty title that produced it left the save button grey with
     * nothing on screen saying why. The words now appear exactly once: a snackbar
     * repeating them is the duplicate this pins out of existence.
     */
    @Test
    fun clearing_the_title_shows_the_validation_message_under_the_field() =
        runDesktopAppTest(checkA11y = true) {
            clickContentDescription(DesktopShell.FAB_ADD_TASK)
            typeIntoTag(TestTags.TASK_EDITOR_TITLE_INPUT, "Buy milk")
            assertTagEnabled(TestTags.TASK_EDITOR_SAVE)

            clearTag(TestTags.TASK_EDITOR_TITLE_INPUT)

            // The bridge is asynchronous — the ViewModel hears about the clear a
            // beat after the keystroke — so wait for the message rather than
            // reading the tree in the same frame.
            awaitTag(TestTags.TASK_EDITOR_TITLE_ERROR)
            assertTagTextEquals(TestTags.TASK_EDITOR_TITLE_ERROR, "Title is required")
            assertTagNotEnabled(TestTags.TASK_EDITOR_SAVE)
            assertEquals(
                1,
                onAllNodesWithText("Title is required").fetchSemanticsNodes().size,
                "the message is under the field, not repeated in a snackbar",
            )
        }

    @Test
    fun the_editor_can_be_left_with_the_shell_back_arrow() =
        runDesktopAppTest(checkA11y = true) {
            clickContentDescription(DesktopShell.FAB_ADD_TASK)
            typeIntoTag(TestTags.TASK_EDITOR_TITLE_INPUT, "Buy milk")

            // Pushing the editor turns the shell's hamburger into a back arrow, so
            // the drawer is unreachable until the push is popped. This is desktop
            // behaviour with no Android counterpart, since the bottom bar stays put.
            assertContentDescriptionDisplayed(DesktopShell.BACK)
            clickContentDescription(DesktopShell.BACK)

            // The pop is asynchronous: the editor stays composed until the shell has
            // navigated back, so a one-shot "does not exist" right after the click
            // races the transition and fails intermittently under machine load.
            // The hamburger has no testTag, so wait on the editor leaving instead.
            awaitTagGone(TestTags.TASK_EDITOR_TITLE_INPUT)
            assertContentDescriptionDisplayed(DesktopShell.HAMBURGER)
        }

    /**
     * The create editor's sheets open.
     *
     * The screen opened its sheets through a state of its own, and the editor rendered
     * the sheets from a state of its own — two states, so a tap on the priority row
     * opened a sheet nobody was watching and the row did nothing at all.
     *
     * The row and the host must look at the same state. Without this assertion the
     * editor's rows were inert in the create flow and no test said so.
     */
    @Test
    fun tapping_the_priority_row_opens_the_priority_sheet() =
        runDesktopAppTest(checkA11y = true) {
            clickContentDescription(DesktopShell.FAB_ADD_TASK)

            clickTagScrolled(TestTags.TASK_EDITOR_PRIORITY_ROW)

            assertTagDisplayed(TestTags.PRIORITY_OPTION_HIGH)
        }

    /**
     * The sheet writes back to the row that opened it.
     *
     * The other half of the same contract: a sheet that appears but writes nowhere is
     * as broken as a sheet that never appears, and only the value on the row tells the
     * two apart.
     */
    @Test
    fun the_priority_sheet_writes_the_value_back_to_the_row() =
        runDesktopAppTest(checkA11y = true) {
            clickContentDescription(DesktopShell.FAB_ADD_TASK)

            clickTagScrolled(TestTags.TASK_EDITOR_PRIORITY_ROW)
            clickTag(TestTags.PRIORITY_OPTION_HIGH)

            assertTextDisplayed("High priority")
        }

    /**
     * The create editor's checklist row opens the checklist.
     *
     * The create screen wired a checklist to the editor — the callbacks, the intents, the
     * draft's items — but nothing drew a row that opened the sheet. The wiring was there
     * and the user had nothing to press.
     *
     * The second half matters as much: an item added in the sheet has to show up in the
     * row that opened it, or the sheet opens onto a list that never changes.
     */
    @Test
    fun the_checklist_row_opens_the_checklist_and_shows_what_was_added() =
        runDesktopAppTest(checkA11y = true) {
            clickContentDescription(DesktopShell.FAB_ADD_TASK)

            clickTagScrolled(TestTags.TASK_EDITOR_CHECKLIST_ROW)
            typeIntoTag(TestTags.CHECKLIST_ADD_INPUT, "Buy milk")
            clickTag(TestTags.CHECKLIST_ADD_BUTTON)

            assertTagDisplayed(TestTags.checklistItem("", "Buy milk"))

            clickSheetClose()

            assertTextDisplayed("Checklist (1)")
        }

    /**
     * The editor shows the rows it was given.
     *
     * A bundle the editor was handed but never drew is a feature the user cannot reach,
     * and nothing else said so.
     */
    @Test
    fun the_editor_shows_the_checklist_and_the_attachments_rows() =
        runDesktopAppTest(checkA11y = true) {
            clickContentDescription(DesktopShell.FAB_ADD_TASK)

            scrollToTag(TestTags.TASK_EDITOR_CHECKLIST_ROW)
            assertTagDisplayed(TestTags.TASK_EDITOR_CHECKLIST_ROW)

            scrollToTag(TestTags.TASK_EDITOR_ATTACHMENTS_ROW)
            assertTagDisplayed(TestTags.TASK_EDITOR_ATTACHMENTS_ROW)
        }

    /**
     * Save, then find the task in the agenda.
     *
     * No due date is entered, so the task can only surface under Inbox's "No Date"
     * section — a header that the Today preset does not define at all. That makes
     * this the one assertion in the desktop suite that fails if the agenda ever
     * falls back to a stale ViewModel again.
     *
     * The FAB is opened from the **Inbox** tab on purpose: the resolver pre-fills
     * `dueDate = Today` when creating from the Today tab (MR-13 source-tab prefill),
     * which would put the task in the Today bucket instead.
     */
    @Test
    fun a_saved_task_without_a_due_date_appears_under_inbox_no_date() =
        runDesktopAppTest(checkA11y = true) {
            tapTab("Inbox")
            clickContentDescription(DesktopShell.FAB_ADD_TASK)
            typeIntoTag(TestTags.TASK_EDITOR_TITLE_INPUT, "Call the dentist")
            clickTag(TestTags.TASK_EDITOR_SAVE)

            // Saving pops the editor back to the Inbox agenda it was opened from.
            assertTagDisplayed(TestTags.agendaSection("No Date"))
            assertTagDisplayed(TestTags.taskItem("Call the dentist"))
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
        val thrown =
            runCatching {
                runDesktopAppTest {
                    // A tag that definitely does not exist in any screen.
                    awaitTag("nonexistent-tag-for-positive-control").assertIsDisplayed()
                }
            }.exceptionOrNull()

        // The harness re-throws after adding the bundle as suppressed.
        val message = thrown?.message ?: ""

        // Tag-explainer fires on timeout
        assert(message.contains("nonexistent-tag-for-positive-control")) {
            "Error message should name the wanted tag, got: $message"
        }
        assert(message.contains("Nearby tags:") || message.contains("All available tags")) {
            "Error message should list nearby or available tags, got: $message"
        }
    }

    /**
     * A create-mode failure that is not a validation failure reaches the snackbar.
     *
     * The title rule's message belongs under the field; a persistence failure has no
     * field to sit under, so it is the one thing the snackbar is for. The fake is bound
     * in place of the real use case through `TaskCreateDeps`' `createFromDraft` seam —
     * the only place a create can be made to fail for a reason the title rule did not
     * produce. No sleep: [awaitText] settles the composition until the host shows it.
     */
    @Test
    fun a_persistence_failure_reaches_the_snackbar() =
        runDesktopAppTest(
            checkA11y = true,
            overrides =
                module {
                    factory<CreateTaskFromDraft> { FailingCreateTaskFromDraft() }
                },
        ) {
            clickContentDescription(DesktopShell.FAB_ADD_TASK)
            typeIntoTag(TestTags.TASK_EDITOR_TITLE_INPUT, "Buy milk")
            assertTagEnabled(TestTags.TASK_EDITOR_SAVE)
            clickTag(TestTags.TASK_EDITOR_SAVE)

            awaitText(FAILURE_MESSAGE)
            // The title is valid, so the only message on screen is the failure: the
            // inline slot must not borrow the persistence message.
            assertTextNotExists("Title is required")
        }

    /** Fails every create for a reason the title rule cannot produce. */
    private class FailingCreateTaskFromDraft : CreateTaskFromDraft {
        override suspend fun invoke(draft: TaskDraft): Either<AppError, TaskId> =
            Either.Left(AppError.Persistence(FAILURE_MESSAGE))
    }

    private companion object {
        const val FAILURE_MESSAGE = "Could not save the task"
    }
}
