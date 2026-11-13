package com.singularity.todo.feature.tasks.presentation.components.detail

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import com.singularity.todo.core.reminders.ReminderOffset
import com.singularity.todo.core.reminders.ReminderOffsetState
import com.singularity.todo.core.text.OwnedText
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.core.ui.components.rememberDialogState
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.tasks.domain.model.TaskPriority
import com.singularity.todo.feature.tasks.domain.model.TaskWorkflowState
import com.singularity.todo.feature.tasks.domain.state.TaskEditorSheet
import com.singularity.todo.test.helpers.assertContentDescriptionNotExists
import com.singularity.todo.test.helpers.assertContentDescriptionReachable
import com.singularity.todo.test.helpers.assertTagDisplayed
import com.singularity.todo.test.helpers.assertTagExists
import com.singularity.todo.test.helpers.assertTagNotExists
import com.singularity.todo.test.helpers.assertTagTextEquals
import com.singularity.todo.test.helpers.awaitTag
import com.singularity.todo.test.helpers.awaitTagGone
import com.singularity.todo.test.helpers.runIsolatedComposeTest
import com.singularity.todo.test.helpers.unclippedBoundsOfTagInUnmergedTree
import kotlinx.datetime.LocalDate
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The hero title, the collapsing bar and the completed state — the three findings the
 * task-detail issue names (UXF-06, UXF-07, UXF-23), asserted on the editor that draws
 * them.
 *
 * The window is 360 dp wide because that is the width a long imported title has to wrap
 * and ellipsise at, and the width the bar has to fit its one-line copy of the title in.
 */
@Tag("fast")
@OptIn(ExperimentalTestApi::class)
class TaskDetailTitleAndCompletionTest {
    @Test
    fun `a short title stays on one line`() =
        runIsolatedComposeTest(width = 360, height = 800) {
            setContent { editor(title = "Buy milk") }

            val title = unclippedBoundsOfTagInUnmergedTree(TestTags.TASK_EDITOR_TITLE_INPUT)
            val height = title.bottom - title.top
            // One 22 sp line plus its leading. Two lines would be past this.
            assertTrue(height < 40.dp, "a short title was drawn taller than one line: $height")
            assertTagTextEquals(TestTags.TASK_EDITOR_TITLE_INPUT, "Buy milk")
        }

    @Test
    fun `a long title is capped at three lines and keeps the whole text`() =
        runIsolatedComposeTest(width = 360, height = 800) {
            setContent { editor(title = LONG_TITLE) }

            // The cap is the claim: three lines, not one clipped line and not an
            // unbounded field. 3 x 22 sp with leading is comfortably inside this band.
            val title = unclippedBoundsOfTagInUnmergedTree(TestTags.TASK_EDITOR_TITLE_INPUT)
            val height = title.bottom - title.top
            assertTrue(
                height > 40.dp && height < 120.dp,
                "a long title was not drawn as a three-line hero: title height $height",
            )

            // The ellipsis is painted, not stored: the node still holds the whole title,
            // which is what a screen reader reads.
            assertTagTextEquals(TestTags.TASK_EDITOR_TITLE_INPUT, LONG_TITLE)
        }

    @Test
    fun `tapping the title turns it into a field holding the whole title`() =
        runIsolatedComposeTest(width = 360, height = 800) {
            setContent { editor(title = LONG_TITLE) }

            // The view state is text; the field is not there until it is tapped.
            assertTagExists(TestTags.TASK_EDITOR_TITLE_INPUT)
            awaitTag(TestTags.TASK_EDITOR_TITLE_INPUT).performClick()

            // Editing shows the whole title — including the part the hero ellipsised —
            // because a person fixing a title has to see what they are fixing.
            assertTagTextEquals(TestTags.TASK_EDITOR_TITLE_INPUT, LONG_TITLE)
        }

    @Test
    fun `the bar names the task only after the hero has scrolled away`() =
        runIsolatedComposeTest(width = 360, height = 800) {
            // Taller than the window on purpose: the claim is about scrolling, and a
            // screen that fits does not scroll however far the test drags.
            setContent {
                editor(
                    title = "Buy milk",
                    extraSections = {
                        Box(modifier = Modifier.height(1400.dp).testTag(FOOT_TAG))
                    },
                )
            }

            // The hero is on screen, so the title is on screen once — in the hero.
            assertTagNotExists(TestTags.TASK_DETAIL_BAR_TITLE)

            awaitTag(FOOT_TAG).performScrollTo()

            assertTagDisplayed(TestTags.TASK_DETAIL_BAR_TITLE)
            assertTagTextEquals(TestTags.TASK_DETAIL_BAR_TITLE, "Buy milk")

            // Back to the top: the hero is on screen again, so the bar gives the name
            // back. A bar that kept it would print the title twice — the defect this
            // rule exists to avoid, in the other direction.
            awaitTag(TestTags.TASK_EDITOR_TITLE_INPUT).performScrollTo()
            awaitTagGone(TestTags.TASK_DETAIL_BAR_TITLE)
        }

    @Test
    fun `an empty title is never the bar's name`() =
        runIsolatedComposeTest(width = 360, height = 800) {
            setContent {
                editor(
                    title = "",
                    extraSections = {
                        Box(modifier = Modifier.height(1400.dp).testTag(FOOT_TAG))
                    },
                )
            }

            awaitTag(FOOT_TAG).performScrollTo()

            // Nothing to name, so the bar says nothing rather than showing a blank.
            assertTagNotExists(TestTags.TASK_DETAIL_BAR_TITLE)
        }

    @Test
    fun `a completed task shows its completion date and hides every clear affordance`() =
        runIsolatedComposeTest(width = 360, height = 800) {
            setContent {
                editor(
                    title = "Buy milk",
                    isCompleted = true,
                    completedLabel = "Done 12 Nov 2026",
                    model = populatedModel(),
                )
            }

            assertTagDisplayed(TestTags.TASK_DETAIL_COMPLETED_LABEL)

            // Every row that can be cleared loses its ✕, not only the two the first
            // version checked: the gate is in the editor, so all of them follow from it.
            CLEAR_LABELS.forEach { assertContentDescriptionNotExists(it) }
        }

    @Test
    fun `a completed task still shows its rows and lets them be opened`() =
        runIsolatedComposeTest(width = 360, height = 1200) {
            setContent { editor(title = "Buy milk", isCompleted = true, model = populatedModel()) }

            // Values stay on the screen — they are facts about the task — and the rows
            // still open their editors. Only the ✕ is gone.
            assertTagDisplayed(TestTags.TASK_EDITOR_PRIORITY_ROW)
            assertTagDisplayed(TestTags.TASK_EDITOR_DUE_ROW)
            assertTagDisplayed(TestTags.TASK_EDITOR_PROJECT_ROW)
            assertTagDisplayed(TestTags.TASK_EDITOR_TAGS_ROW)
            assertTagDisplayed(TestTags.TASK_EDITOR_REMINDER_ROW)
            assertTagDisplayed(TestTags.TASK_EDITOR_DEADLINE_ROW)
            assertTagDisplayed(TestTags.TASK_EDITOR_ESTIMATE_ROW)

            awaitTag(TestTags.TASK_EDITOR_PRIORITY_ROW).performClick()
            assertTagDisplayed(TestTags.TASK_EDITOR_PRIORITY_ROW)
        }

    @Test
    fun `an open task keeps the clear affordances of every row`() =
        runIsolatedComposeTest(width = 360, height = 1200) {
            setContent { editor(title = "Buy milk", model = populatedModel()) }

            assertTagNotExists(TestTags.TASK_DETAIL_COMPLETED_LABEL)
            CLEAR_LABELS.forEach { assertContentDescriptionReachable(it) }
        }

    @Test
    fun `a completed task keeps pin and workflow state inert-but-present`() =
        runIsolatedComposeTest(width = 360, height = 1200) {
            setContent {
                editor(
                    title = "Buy milk",
                    isCompleted = true,
                    model = populatedModel(workflowState = TaskWorkflowState.InProgress),
                )
            }

            // The two toggles are not clear affordances: pin still toggles, and the
            // workflow state is still readable. Neither was touched by the gate.
            assertTagDisplayed(TestTags.TASK_EDITOR_PIN_ROW)
            assertTagDisplayed(TestTags.TASK_EDITOR_WORKFLOW_STATE_ROW)
        }

    @Test
    fun `pasted line breaks become one space each`() =
        runIsolatedComposeTest(width = 360, height = 800) {
            setContent { editor(title = "") }

            // Create? No: this is the detail, so the title is text until it is tapped.
            awaitTag(TestTags.TASK_EDITOR_TITLE_INPUT).performClick()

            // The three ways a platform writes a break, typed at once. Each must land
            // as a single space, not two.
            awaitTag(TestTags.TASK_EDITOR_TITLE_INPUT).performTextInput("a\r\nb\rc\nd")
            assertTagTextEquals(TestTags.TASK_EDITOR_TITLE_INPUT, "a b c d")
        }

    @Composable
    private fun editor(
        title: String,
        isCompleted: Boolean = false,
        completedLabel: String? = null,
        model: TaskEditorModel = TaskEditorModel(),
        extraSections: (@Composable () -> Unit)? = null,
    ) {
        TaskEditorContent(
            model = model,
            titleSource = OwnedText(text = title, revision = 0),
            descriptionSource = OwnedText(text = "", revision = 0),
            onTitleChange = {},
            onDescriptionChange = {},
            callbacks =
                TaskEditorCallbacks(
                    onBack = {},
                    onCheckToggle = {},
                    startDate =
                        DateRowCallbacks(
                            onChangeDate = {},
                            onChangeTime = {},
                            onClear = {},
                        ),
                    project =
                        RowCallbacks(
                            onChange = {},
                            onClick = {},
                            onClear = {},
                        ),
                    tags =
                        RowCallbacks(
                            onChange = {},
                            onClick = {},
                            onClear = {},
                        ),
                    recurrence = null,
                    pin = ToggleCallbacks(onToggle = {}),
                    dependencies = null,
                    workflowState = RowCallbacks(onChange = {}, onClick = {}),
                    checklist = null,
                    attachments = null,
                    estimate =
                        RowCallbacks(
                            onChange = {},
                            onClick = {},
                            onClear = {},
                        ),
                    onTimeEntryAdd = null,
                    onTimeEntrySave = null,
                    extraSections = extraSections,
                    reminder =
                        ReminderRowCallbacks(
                            onSet = {},
                            onClear = {},
                            onClick = {},
                        ),
                    deadline =
                        DeadlineRowCallbacks(
                            onClick = {},
                            onClear = {},
                        ),
                    dueDate =
                        DateRowCallbacks(
                            onChangeDate = {},
                            onChangeTime = {},
                            onClick = {},
                            onClear = {},
                        ),
                    priority =
                        RowCallbacks(
                            onChange = {},
                            onClick = {},
                            onClear = {},
                        ),
                ),
            isCompleted = isCompleted,
            completedLabel = completedLabel,
            barTitle = title,
            now = NOW,
            sheets = rememberDialogState<TaskEditorSheet>(),
        )
    }

    /**
     * A model with every optional row populated, so each row's ✕ is on the screen and
     * its absence for a completed task means something.
     */
    private fun populatedModel(workflowState: TaskWorkflowState = TaskWorkflowState.Open) =
        TaskEditorModel(
            priority = TaskPriority.High,
            dueDate = LocalDate(2026, 11, 12),
            startDate = LocalDate(2026, 11, 10),
            deadline = LocalDate(2026, 11, 20),
            reminderState = ReminderOffsetState.Known(ReminderOffset.FIFTEEN_MIN),
            project = ProjectId("p1"),
            projectName = "Home",
            tags =
                kotlinx.collections.immutable.persistentListOf(
                    com.singularity.todo.feature.tags
                        .TagId("t1"),
                ),
            estimateMinutes = 30,
            workflowState = workflowState,
        )

    private companion object {
        val NOW: Instant = Instant.parse("2026-11-12T12:00:00Z")

        /** The child section's own tag — a slot's content is the caller's, so the tag is too. */
        const val FOOT_TAG = "title_test_foot"

        /** 90 characters, the length the finding measured (`ux-findings.md`, UXF-06). */
        const val LONG_TITLE =
            "Отмамамыларамливаю кухню и переписываю длинное название задачи для проверки обрезки"

        /** Every ✕ the editor can draw, in the locale the harness mounts in. */
        val CLEAR_LABELS =
            listOf(
                "Reset date",
                "Reset priority",
                "Reset estimate",
                "Reset reminder",
                "Reset deadline",
                "Clear project",
            )
    }
}
