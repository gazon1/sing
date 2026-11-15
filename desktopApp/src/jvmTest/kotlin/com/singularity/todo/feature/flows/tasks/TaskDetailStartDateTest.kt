package com.singularity.todo.feature.flows.tasks

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.performClick
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.feature.tasks.domain.port.TaskRepository
import com.singularity.todo.test.helpers.assertTagDisplayed
import com.singularity.todo.test.helpers.awaitTag
import com.singularity.todo.test.helpers.clickTag
import com.singularity.todo.test.helpers.runDesktopAppTest
import com.singularity.todo.test.helpers.tapTab
import com.singularity.todo.test.helpers.tasks
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The detail draws the start date, and writing it does not touch the due date.
 *
 * The row was hidden on the detail while Create drew it, so a task created with a
 * start date could not be corrected afterwards. The two facts are independent: the
 * due date is when the work is planned, the start date is when it becomes active,
 * and writing one must not move the other.
 *
 * The claim is read back from the task, not from the screen: "the due date did not
 * move" is a statement about the write, and a row's label is localized copy.
 */
@OptIn(ExperimentalTestApi::class)
@Tag("slow")
@Tag("slow:compose")
class TaskDetailStartDateTest {
    @Test
    fun the_detail_draws_the_start_date_row_a_task_can_carry() =
        runDesktopAppTest(clock = CLOCK, checkA11y = true) { koin ->
            tasks(koin).given(
                due = LocalDate(2026, 11, 14),
                title = "Windowed task",
                overrides = { copy(startDate = OTHER, startTime = LocalTime(9, 0)) },
            )

            tapTab("Today")
            awaitTag(TestTags.taskItem("Windowed task")).performClick()
            awaitTag(TestTags.TASK_EDITOR_TITLE_INPUT)

            assertTagDisplayed(TestTags.TASK_EDITOR_START_DATE_ROW)
        }

    @Test
    fun setting_the_start_date_leaves_the_due_date_alone() =
        runDesktopAppTest(clock = CLOCK, checkA11y = true) { koin ->
            val robot = tasks(koin)
            // The due date is the *other* day on purpose: the sheet's chip writes
            // today, so a write that landed on the wrong field would be invisible if
            // both dates were the same.
            robot.given(due = OTHER, title = "Dated task")

            tapTab("Today")
            awaitTag(TestTags.taskItem("Dated task")).performClick()
            awaitTag(TestTags.TASK_EDITOR_TITLE_INPUT)

            // The start-date row opens the start-date sheet — its own target, not the
            // planning sheet the due date row opens.
            clickTag(TestTags.TASK_EDITOR_START_DATE_ROW)
            assertTagDisplayed(TestTags.DatePicker.TODAY)
            clickTag(TestTags.DatePicker.TODAY)
            clickTag(TestTags.DatePicker.OK)

            val repo = koin.get<TaskRepository>()
            val taskId = robot.lastTaskId!!
            // The detail autosaves through a debounced write, so the row lands after
            // the sheet closes, and the day it writes is the sheet's own clock's rather
            // than this test's to predict. The body is suspend, so the wait is a real
            // one over the repository: it ends when a start date appears.
            val saved =
                kotlinx.coroutines.withTimeout(10_000) {
                    var task = repo.get(taskId)
                    while (task?.startDate == null) {
                        kotlinx.coroutines.delay(50)
                        task = repo.get(taskId)
                    }
                    task
                }

            assertTrue(
                saved.startDate != OTHER,
                "the start date the sheet confirmed was not written: still ${saved.startDate}",
            )
            assertEquals(
                OTHER,
                saved.dueDate,
                "writing the start date moved the due date",
            )
        }

    private companion object {
        val CLOCK =
            com.singularity.todo.test.fakes
                .FakeClock(kotlin.time.Instant.parse("2026-11-14T09:00:00Z"))

        /** A day the task is seeded with on purpose; the picker never writes it. */
        val OTHER = LocalDate(2026, 11, 10)
    }
}
