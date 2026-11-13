@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.singularity.todo.feature.tasks.presentation.components.detail

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import com.singularity.todo.core.text.OwnedText
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.core.ui.components.rememberDialogState
import com.singularity.todo.core.ui.theme.SingularityTheme
import com.singularity.todo.feature.tasks.domain.model.TaskPriority
import com.singularity.todo.feature.tasks.domain.state.TaskEditorSheet
import com.singularity.todo.test.helpers.runIsolatedComposeTest
import com.singularity.todo.test.helpers.unclippedBoundsOfContentDescription
import com.singularity.todo.test.helpers.unclippedBoundsOfTag
import com.singularity.todo.test.helpers.unclippedBoundsOfText
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The Task Detail's text answers the user's font scale.
 *
 * The scale is a setting in Settings → Interface, and every slot in the app's
 * typography is scaled by it. A screen that writes its own `16.sp` instead of
 * reading a slot ignores the setting: the text stays the same size while
 * everything around it grows.
 *
 * The title, the description and the attribute rows are the three places where
 * that used to happen, so they are the three measured here.
 *
 * Two things are asserted for each:
 *
 * - **It responds.** The measured text grows with the scale — the text is drawn
 *   with the scaled style, not with a fixed one.
 * - **It is not clipped.** The row that carries the text is at least as tall as
 *   the text itself, so a larger setting does not cut the last line off.
 */
@Tag("fast")
@OptIn(ExperimentalTestApi::class)
class TaskDetailFontScaleTest {
    @Test
    fun `the title, the description and the priority label grow with the font scale`() {
        val normal = heightsAt(scale = 1f)
        val large = heightsAt(scale = 1.5f)

        assertTrue(
            large.title > normal.title,
            "the title did not grow with the font scale: ${normal.title} → ${large.title}",
        )
        assertTrue(
            large.description > normal.description,
            "the description did not grow with the font scale: " +
                "${normal.description} → ${large.description}",
        )
        assertTrue(
            large.priorityLabel > normal.priorityLabel,
            "the priority label did not grow with the font scale: " +
                "${normal.priorityLabel} → ${large.priorityLabel}",
        )
    }

    @Test
    fun `the scaled text is not clipped`() {
        val large = heightsAt(scale = 1.5f)

        // The row wears the label, so it cannot be shorter than the label itself.
        // The row's own height is a touch target (48dp) and does not have to grow
        // with the text — the text inside it has to fit.
        assertTrue(
            large.priorityRow >= large.priorityLabel,
            "the priority row clips its label at the large font scale: " +
                "row ${large.priorityRow}, label ${large.priorityLabel}",
        )
        // `titleLarge` is 28sp of line height at the baseline; at 1.5 it is 42sp.
        // A field shorter than that is cutting the line off.
        assertTrue(
            large.title >= 40.dp,
            "the title is clipped at the large font scale: it measures ${large.title}",
        )
        // `bodyLarge` is 24sp of line height at the baseline; at 1.5 it is 36sp.
        assertTrue(
            large.description >= 30.dp,
            "the description is clipped at the large font scale: it measures ${large.description}",
        )
    }

    /**
     * The measured heights of the detail's text at one font scale.
     */
    private data class Heights(val title: Dp, val description: Dp, val priorityRow: Dp, val priorityLabel: Dp)

    private fun heightsAt(scale: Float): Heights {
        var measured: Heights? = null
        runIsolatedComposeTest(width = 360, height = 900) {
            setContent {
                SingularityTheme(fontSizeScale = scale) {
                    TaskEditorContent(
                        model =
                            TaskEditorModel(
                                priority = TaskPriority.Medium,
                            ),
                        titleSource = OwnedText(text = "Buy the oat milk", revision = 0),
                        descriptionSource = OwnedText(text = "Pick up the oat milk", revision = 0),
                        onTitleChange = {},
                        onDescriptionChange = {},
                        callbacks =
                            TaskEditorCallbacks(
                                onBack = {},
                                onCheckToggle = null,
                                priority =
                                    RowCallbacks(
                                        onChange = {},
                                        onClick = { },
                                        onClear = { },
                                    ),
                                startDate = null,
                                project = null,
                                tags = null,
                                recurrence = null,
                                pin = null,
                                dependencies = null,
                                checklist = null,
                                attachments = null,
                                estimate = null,
                                onTimeEntryAdd = null,
                                onTimeEntrySave = null,
                            ),
                        now = NOW,
                        sheets = rememberDialogState<TaskEditorSheet>(),
                    )
                }
            }

            measured =
                Heights(
                    title = unclippedBoundsOfTag(TestTags.TASK_EDITOR_TITLE_INPUT).height,
                    description = unclippedBoundsOfContentDescription("Task description").height,
                    priorityRow = unclippedBoundsOfTag(TestTags.TASK_EDITOR_PRIORITY_ROW).height,
                    priorityLabel = unclippedBoundsOfText("Medium priority").height,
                )
        }
        return checkNotNull(measured) { "the editor was never mounted" }
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-03-14T12:00:00Z")
    }
}
