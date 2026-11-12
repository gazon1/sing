@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.singularity.todo.feature.tasks.presentation.components.detail

import androidx.compose.ui.test.ExperimentalTestApi
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.feature.tasks.domain.model.RecurrenceSpec.Interval
import com.singularity.todo.feature.tasks.domain.model.RecurrenceSpec.Monthly
import com.singularity.todo.feature.tasks.domain.model.RecurrenceSpec.RecurrenceBase
import com.singularity.todo.feature.tasks.domain.model.RecurrenceSpec.Weekly
import com.singularity.todo.feature.tasks.domain.model.RecurrenceSpec.Yearly
import com.singularity.todo.feature.tasks.domain.model.RecurrenceTermination
import com.singularity.todo.test.helpers.assertTagTextEquals
import com.singularity.todo.test.helpers.runIsolatedComposeTest
import com.singularity.todo.test.helpers.textOfTag
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

/**
 * The task detail row that shows the recurrence rule.
 *
 * The row used to print the rule's own `toString()` — "Interval(base=FROM_DUE,
 * amount=3, unit=DAY, termination=null)" — which is the rule as the code writes
 * it down, not as a person reads it. These tests pin what the row says instead.
 */
@Tag("fast")
class TaskDetailRecurrenceSectionTest {
    @Test
    fun `a three-day interval reads as a sentence`() =
        runIsolatedComposeTest {
            setContent {
                TaskDetailRecurrenceSection(
                    recurrence = Interval(RecurrenceBase.FROM_DUE, 3, DateTimeUnit.DAY),
                    anchorDate = LocalDate(2026, 11, 12),
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
                    anchorDate = LocalDate(2026, 11, 12),
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

    @Test
    fun `the rule says when the series stops`() =
        runIsolatedComposeTest {
            setContent {
                TaskDetailRecurrenceSection(
                    recurrence =
                        Interval(RecurrenceBase.FROM_DUE, 3, DateTimeUnit.DAY)
                            .copy(termination = RecurrenceTermination(LocalDate(2027, 1, 29))),
                    anchorDate = LocalDate(2026, 11, 12),
                )
            }

            assertTagTextEquals(
                TestTags.TASK_RECURRENCE_TEXT,
                "Every 3 days, from the due date, starting 12 Nov 2026, until 29 Jan 2027",
            )
        }
}
