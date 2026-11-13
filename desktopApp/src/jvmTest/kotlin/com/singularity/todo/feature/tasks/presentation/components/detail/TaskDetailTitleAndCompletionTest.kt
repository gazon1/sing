package com.singularity.todo.feature.tasks.presentation.components.detail

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import com.singularity.todo.core.text.OwnedText
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.core.ui.components.rememberDialogState
import com.singularity.todo.feature.tasks.domain.state.TaskEditorSheet
import com.singularity.todo.test.helpers.assertContentDescriptionNotExists
import com.singularity.todo.test.helpers.assertTagDisplayed
import com.singularity.todo.test.helpers.assertTagNotExists
import com.singularity.todo.test.helpers.assertTagTextEquals
import com.singularity.todo.test.helpers.awaitTag
import com.singularity.todo.test.helpers.runIsolatedComposeTest
import com.singularity.todo.test.helpers.unclippedBoundsOfTagInUnmergedTree
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
 * at, and the width the bar has to fit its ellipsised copy of the title in.
 */
@Tag("fast")
@OptIn(ExperimentalTestApi::class)
class TaskDetailTitleAndCompletionTest {
    @Test
    fun `a long title wraps past one line and keeps the whole text`() =
        runIsolatedComposeTest(width = 360, height = 800) {
            setContent { editor(title = LONG_TITLE) }

            // The visual unit is a line: a title that fits on one has a one-line field,
            // and this one must not. 40 dp is above the 22 sp title's single line and
            // below two of them, so any height past it proves the wrap.
            val titleBounds = unclippedBoundsOfTagInUnmergedTree(TestTags.TASK_EDITOR_TITLE_INPUT)
            val titleHeight = titleBounds.bottom - titleBounds.top
            assertTrue(
                titleHeight > 40.dp,
                "a long title was clipped to one line instead of wrapping: title height $titleHeight",
            )

            // Wrapping is a drawing decision; the value is the whole title, which is what
            // a screen reader reads and what the field holds.
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
        }

    @Test
    fun `a completed task shows when it was completed and offers no clear affordance`() =
        runIsolatedComposeTest(width = 360, height = 800) {
            setContent { editor(title = "Buy milk", isCompleted = true, completedLabel = "Done 12 Nov 2026") }

            assertTagDisplayed(TestTags.TASK_DETAIL_COMPLETED_LABEL)

            // The ✕ that erases a value is not an affordance of finished work. Gated by
            // the editor, so no row draws one however its own callbacks were built.
            assertContentDescriptionNotExists("Reset date")
            assertContentDescriptionNotExists("Reset priority")
        }

    @Test
    fun `an open task keeps its clear affordances`() =
        runIsolatedComposeTest(width = 360, height = 800) {
            val model =
                TaskEditorModel(
                    priority = com.singularity.todo.feature.tasks.domain.model.TaskPriority.High,
                    dueDate = kotlinx.datetime.LocalDate(2026, 11, 12),
                )
            setContent { editor(title = "Buy milk", model = model) }

            assertTagNotExists(TestTags.TASK_DETAIL_COMPLETED_LABEL)
            assertTagDisplayed(TestTags.TASK_EDITOR_DUE_ROW)
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
                    startDate = null,
                    project = null,
                    tags = null,
                    recurrence = null,
                    pin = ToggleCallbacks(onToggle = {}),
                    dependencies = null,
                    checklist = null,
                    attachments = null,
                    estimate = null,
                    onTimeEntryAdd = null,
                    onTimeEntrySave = null,
                    extraSections = extraSections,
                    dueDate =
                        DateRowCallbacks(
                            onChangeDate = {},
                            onChangeTime = {},
                            onClear = {},
                        ),
                    priority =
                        RowCallbacks(
                            onChange = {},
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

    private companion object {
        val NOW: Instant = Instant.parse("2026-11-12T12:00:00Z")

        /** The child section's own tag — a slot's content is the caller's, so the tag is too. */
        const val FOOT_TAG = "title_test_foot"

        /** 90 characters, the length the finding measured (`ux-findings.md`, UXF-06). */
        const val LONG_TITLE =
            "Отмамамыларамливаю кухню и переписываю длинное название задачи для проверки обрезки"
    }
}
