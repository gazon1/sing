@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.singularity.todo.feature.tasks.presentation.components.detail

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.feature.tasks.domain.model.RecurrenceSpec.Interval
import com.singularity.todo.feature.tasks.domain.model.RecurrenceSpec.RecurrenceBase
import com.singularity.todo.test.helpers.runIsolatedComposeTest
import com.singularity.todo.test.helpers.textOfTag
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The recurrence picker's preview, read from the picker itself.
 *
 * The picker and the task detail row are the two places a rule is read, and
 * they are meant to read it the same way. A test that says so by rendering the
 * sentence itself proves only that the sentence function is deterministic — it
 * never touches the picker, so a picker that passed the anchor date instead of
 * the due date would pass that test too. These tests mount the real sheet and
 * read what it actually shows.
 */
@Tag("fast")
class RecurrencePickerPreviewTest {
    private val due = LocalDate(2026, 11, 12)

    /**
     * The picker's own anchor, deliberately not the due date.
     *
     * The anchor is what the next occurrence is computed from; it falls back to
     * today when the task has no due date. A date that is neither the task's
     * nor today's, so a sentence that shows the anchor instead of the due date
     * cannot be mistaken for the right one.
     */
    private val anchor = LocalDate(2026, 12, 24)

    @Test
    fun `the picker preview and the task detail row read the rule the same way`() =
        runIsolatedComposeTest {
            val spec = Interval(RecurrenceBase.FROM_DUE, 3, DateTimeUnit.DAY)
            setContent {
                Column {
                    TaskDetailRecurrenceSection(recurrence = spec, dueDate = due)
                    RecurrencePickerSheet(
                        currentSpec = spec,
                        anchorDate = anchor,
                        dueDate = due,
                        onApply = {},
                        onDismiss = {},
                    )
                }
            }

            assertEquals(
                textOfTag(TestTags.TASK_RECURRENCE_TEXT),
                textOfTag(TestTags.RECURRENCE_PICKER_PREVIEW),
                "the picker and the task detail read the same rule differently",
            )
        }

    @Test
    fun `the picker names the due date and not its anchor`() =
        runIsolatedComposeTest {
            val spec = Interval(RecurrenceBase.FROM_DUE, 3, DateTimeUnit.DAY)
            setContent {
                RecurrencePickerSheet(
                    currentSpec = spec,
                    anchorDate = anchor,
                    dueDate = due,
                    onApply = {},
                    onDismiss = {},
                )
            }

            val preview = textOfTag(TestTags.RECURRENCE_PICKER_PREVIEW)
            assertNotNull(preview, "the picker shows no preview")
            assertTrue(
                preview.contains("12 Nov 2026"),
                "the picker does not name the due date: $preview",
            )
            assertTrue(
                !preview.contains("24 Dec 2026"),
                "the picker names its anchor instead of the due date: $preview",
            )
        }

    @Test
    fun `the picker names no date for a rule measured from completion`() =
        runIsolatedComposeTest {
            val spec = Interval(RecurrenceBase.FROM_COMPLETION, 3, DateTimeUnit.DAY)
            setContent {
                RecurrencePickerSheet(
                    currentSpec = spec,
                    anchorDate = anchor,
                    dueDate = due,
                    onApply = {},
                    onDismiss = {},
                )
            }

            val preview = textOfTag(TestTags.RECURRENCE_PICKER_PREVIEW)
            assertNotNull(preview, "the picker shows no preview")
            assertTrue(
                !preview.contains("24 Dec 2026"),
                "the picker names its anchor for a rule measured from completion: $preview",
            )
        }

    @Test
    fun `the picker names no date when the task has no due date`() =
        runIsolatedComposeTest {
            val spec = Interval(RecurrenceBase.FROM_DUE, 3, DateTimeUnit.DAY)
            setContent {
                RecurrencePickerSheet(
                    currentSpec = spec,
                    anchorDate = anchor,
                    dueDate = null,
                    onApply = {},
                    onDismiss = {},
                )
            }

            val preview = textOfTag(TestTags.RECURRENCE_PICKER_PREVIEW)
            assertNotNull(preview, "the picker shows no preview")
            assertTrue(
                !preview.contains("24 Dec 2026"),
                "the picker names its anchor for a task with no due date: $preview",
            )
        }

    @Test
    fun `a new due date reaches the picker preview`() =
        runIsolatedComposeTest {
            var dueDate by mutableStateOf(due)
            val spec = Interval(RecurrenceBase.FROM_DUE, 3, DateTimeUnit.DAY)
            setContent {
                RecurrencePickerSheet(
                    currentSpec = spec,
                    anchorDate = anchor,
                    dueDate = dueDate,
                    onApply = {},
                    onDismiss = {},
                )
            }

            val before = textOfTag(TestTags.RECURRENCE_PICKER_PREVIEW)
            assertNotNull(before, "the picker shows no preview")
            assertTrue(before.contains("12 Nov 2026"), "the picker does not name the due date: $before")

            runOnIdle { dueDate = LocalDate(2027, 1, 5) }

            val after = textOfTag(TestTags.RECURRENCE_PICKER_PREVIEW)
            assertNotNull(after, "the picker shows no preview")
            assertTrue(
                after.contains("5 Jan 2027"),
                "the picker does not name the new due date: $after",
            )
        }
}
