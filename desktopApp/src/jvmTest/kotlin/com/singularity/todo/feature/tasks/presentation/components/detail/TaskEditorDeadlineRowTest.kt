package com.singularity.todo.feature.tasks.presentation.components.detail

import androidx.compose.ui.test.ExperimentalTestApi
import com.singularity.todo.test.helpers.assertTextDisplayed
import com.singularity.todo.test.helpers.runIsolatedComposeTest
import kotlinx.datetime.LocalDate
import org.junit.jupiter.api.Tag
import kotlin.test.Test

/**
 * The deadline row of the task detail.
 *
 * The overdue word is part of the label and not a colour, because a colour is
 * not a fact a screen reader reads out. And a task that is already done is
 * never overdue — the row of a finished task says when the deadline was, not
 * that it was missed.
 */
@Tag("fast")
@OptIn(ExperimentalTestApi::class)
class TaskEditorDeadlineRowTest {
    /** A Wednesday: the label names the weekday. */
    private val yesterday = LocalDate(2026, 9, 30)

    /** The label of that date, in the locale the desktop tests run in. */
    private val dateLabel = "Wed, 30 Sep 2026"

    @Test
    fun `an overdue deadline says so in words`() =
        runIsolatedComposeTest(width = 360, height = 800) {
            setContent {
                TaskEditorDeadlineRow(
                    deadline = yesterday,
                    isOverdue = true,
                    onDeadlineClick = {},
                    onDeadlineClear = {},
                )
            }

            assertTextDisplayed("$dateLabel · Overdue")
        }

    @Test
    fun `the deadline of a finished task is not overdue`() =
        runIsolatedComposeTest(width = 360, height = 800) {
            setContent {
                TaskEditorDeadlineRow(
                    deadline = yesterday,
                    // The value the model computes for a completed task.
                    isOverdue =
                        isDeadlineOverdue(
                            deadline = yesterday,
                            today = LocalDate(2026, 10, 1),
                            isCompleted = true,
                        ),
                    onDeadlineClick = {},
                    onDeadlineClear = {},
                )
            }

            // The date and nothing else: the work is finished, so the deadline
            // cannot have been missed.
            assertTextDisplayed(dateLabel)
        }
}
