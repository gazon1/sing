@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.singularity.todo.feature.tasks.presentation.components.detail

import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.feature.tasks.domain.model.RecurrenceSpec.Interval
import com.singularity.todo.feature.tasks.domain.model.RecurrenceSpec.Monthly
import com.singularity.todo.feature.tasks.domain.model.RecurrenceSpec.RecurrenceBase
import com.singularity.todo.feature.tasks.domain.model.RecurrenceSpec.Weekly
import com.singularity.todo.feature.tasks.domain.model.RecurrenceSpec.Yearly
import com.singularity.todo.feature.tasks.domain.model.RecurrenceTermination
import com.singularity.todo.feature.tasks.presentation.components.RecurrenceSentence
import com.singularity.todo.test.helpers.assertTagTextEquals
import com.singularity.todo.test.helpers.runIsolatedComposeTest
import com.singularity.todo.test.helpers.textOfTag
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

/**
 * The task detail row that shows the recurrence rule.
 *
 * The row used to print the rule's own `toString()` — "Interval(base=FROM_DUE,
 * amount=3, unit=DAY, termination=null)" — which is the rule as the code writes
 * it down, not as a person reads it. These tests pin what the row says instead,
 * and — the part that was wrong — which date it names as the start of the
 * series.
 */
@Tag("fast")
class TaskDetailRecurrenceSectionTest {
    private val due = LocalDate(2026, 11, 12)

    // ─── The rule ──────────────────────────────────────────────────────────────

    @Test
    fun `a three-day interval reads as a sentence`() =
        runIsolatedComposeTest {
            setContent {
                TaskDetailRecurrenceSection(
                    recurrence = Interval(RecurrenceBase.FROM_DUE, 3, DateTimeUnit.DAY),
                    dueDate = due,
                )
            }

            assertTagTextEquals(
                TestTags.TASK_RECURRENCE_TEXT,
                "Every 3 days, from the due date, starting 12 Nov 2026",
            )
        }

    @Test
    fun `the row shows no implementation terms`() =
        runIsolatedComposeTest {
            setContent {
                TaskDetailRecurrenceSection(
                    recurrence = Interval(RecurrenceBase.FROM_DUE, 3, DateTimeUnit.DAY),
                    dueDate = due,
                )
            }

            val text = textOfTag(TestTags.TASK_RECURRENCE_TEXT)
            assertNotNull(text, "the recurrence row shows no text")
            assertFalse(
                text.contains("Interval("),
                "the recurrence row shows the rule's own toString(): $text",
            )
        }

    @Test
    fun `a weekly rule names its weekdays`() =
        runIsolatedComposeTest {
            setContent {
                TaskDetailRecurrenceSection(
                    recurrence = Weekly(RecurrenceBase.FROM_DUE, setOf(1, 3, 5)),
                )
            }

            assertTagTextEquals(
                TestTags.TASK_RECURRENCE_TEXT,
                "Weekly on Mon, Wed, Fri, from the due date",
            )
        }

    @Test
    fun `a monthly rule names its day`() =
        runIsolatedComposeTest {
            setContent {
                TaskDetailRecurrenceSection(
                    recurrence = Monthly(RecurrenceBase.FROM_DUE, 15),
                )
            }

            assertTagTextEquals(
                TestTags.TASK_RECURRENCE_TEXT,
                "Monthly on day 15, from the due date",
            )
        }

    @Test
    fun `a yearly rule names its date`() =
        runIsolatedComposeTest {
            setContent {
                TaskDetailRecurrenceSection(
                    recurrence = Yearly(RecurrenceBase.FROM_DUE, 12, 25),
                )
            }

            assertTagTextEquals(
                TestTags.TASK_RECURRENCE_TEXT,
                "Yearly on Dec 25, from the due date",
            )
        }

    // ─── The base says which date the series starts on ────────────────────────

    @Test
    fun `a rule measured from the due date starts on it`() =
        runIsolatedComposeTest {
            setContent {
                TaskDetailRecurrenceSection(
                    recurrence = Interval(RecurrenceBase.FROM_DUE, 3, DateTimeUnit.DAY),
                    dueDate = due,
                )
            }

            assertTagTextEquals(
                TestTags.TASK_RECURRENCE_TEXT,
                "Every 3 days, from the due date, starting 12 Nov 2026",
            )
        }

    @Test
    fun `a rule measured from the due date has no start without one`() =
        runIsolatedComposeTest {
            setContent {
                TaskDetailRecurrenceSection(
                    recurrence = Interval(RecurrenceBase.FROM_DUE, 3, DateTimeUnit.DAY),
                )
            }

            assertTagTextEquals(
                TestTags.TASK_RECURRENCE_TEXT,
                "Every 3 days, from the due date",
            )
        }

    @Test
    fun `a rule measured from completion does not borrow the due date`() =
        runIsolatedComposeTest {
            setContent {
                TaskDetailRecurrenceSection(
                    recurrence = Interval(RecurrenceBase.FROM_COMPLETION, 3, DateTimeUnit.DAY),
                    dueDate = due,
                )
            }

            assertTagTextEquals(
                TestTags.TASK_RECURRENCE_TEXT,
                "Every 3 days, after completion",
            )
        }

    @Test
    fun `a catch-up rule is measured from the due date`() =
        runIsolatedComposeTest {
            setContent {
                TaskDetailRecurrenceSection(
                    recurrence = Interval(RecurrenceBase.CATCH_UP, 3, DateTimeUnit.DAY),
                    dueDate = due,
                )
            }

            assertTagTextEquals(
                TestTags.TASK_RECURRENCE_TEXT,
                "Every 3 days, catch-up, filling the gaps, starting 12 Nov 2026",
            )
        }

    @Test
    fun `a catch-up rule has no start without a due date`() =
        runIsolatedComposeTest {
            setContent {
                TaskDetailRecurrenceSection(
                    recurrence = Interval(RecurrenceBase.CATCH_UP, 3, DateTimeUnit.DAY),
                )
            }

            assertTagTextEquals(
                TestTags.TASK_RECURRENCE_TEXT,
                "Every 3 days, catch-up, filling the gaps",
            )
        }

    // ─── The end date ──────────────────────────────────────────────────────────

    @Test
    fun `the rule says when the series stops`() =
        runIsolatedComposeTest {
            setContent {
                TaskDetailRecurrenceSection(
                    recurrence =
                        Interval(RecurrenceBase.FROM_DUE, 3, DateTimeUnit.DAY)
                            .copy(termination = RecurrenceTermination(LocalDate(2027, 1, 29))),
                    dueDate = due,
                )
            }

            assertTagTextEquals(
                TestTags.TASK_RECURRENCE_TEXT,
                "Every 3 days, from the due date, starting 12 Nov 2026, until 29 Jan 2027",
            )
        }

    @Test
    fun `the end date does not depend on the base`() =
        runIsolatedComposeTest {
            setContent {
                TaskDetailRecurrenceSection(
                    recurrence =
                        Interval(RecurrenceBase.FROM_COMPLETION, 3, DateTimeUnit.DAY)
                            .copy(termination = RecurrenceTermination(LocalDate(2027, 1, 29))),
                    dueDate = due,
                )
            }

            assertTagTextEquals(
                TestTags.TASK_RECURRENCE_TEXT,
                "Every 3 days, after completion, until 29 Jan 2027",
            )
        }

    @Test
    fun `an end date without a start date reads on its own`() =
        runIsolatedComposeTest {
            setContent {
                TaskDetailRecurrenceSection(
                    recurrence =
                        Interval(RecurrenceBase.FROM_DUE, 3, DateTimeUnit.DAY)
                            .copy(termination = RecurrenceTermination(LocalDate(2027, 1, 29))),
                )
            }

            assertTagTextEquals(
                TestTags.TASK_RECURRENCE_TEXT,
                "Every 3 days, from the due date, until 29 Jan 2027",
            )
        }

    // ─── The picker reads the same sentence ───────────────────────────────────

    @Test
    fun `the picker and the task detail read the rule the same way`() =
        runIsolatedComposeTest {
            val spec = Interval(RecurrenceBase.FROM_DUE, 3, DateTimeUnit.DAY)
            setContent {
                TaskDetailRecurrenceSection(recurrence = spec, dueDate = due)
                // What the picker's preview shows: the same sentence, from the
                // same function, for the same rule and the same task.
                Text(
                    text = RecurrenceSentence(spec, due),
                    modifier = Modifier.testTag(PICKER_PREVIEW),
                )
            }

            assertEquals(
                textOfTag(PICKER_PREVIEW),
                textOfTag(TestTags.TASK_RECURRENCE_TEXT),
                "the picker and the task detail read the same rule differently",
            )
        }

    private companion object {
        /** Where the picker's sentence is put in this test's composition. */
        const val PICKER_PREVIEW = "recurrence_picker_preview"
    }
}
