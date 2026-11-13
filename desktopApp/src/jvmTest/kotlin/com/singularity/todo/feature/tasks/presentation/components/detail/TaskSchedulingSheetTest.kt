@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.singularity.todo.feature.tasks.presentation.components.detail

import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.test.helpers.assertTagDisplayed
import com.singularity.todo.test.helpers.assertTagExists
import com.singularity.todo.test.helpers.assertTagNotExists
import com.singularity.todo.test.helpers.assertTagSelected
import com.singularity.todo.test.helpers.assertTextDisplayed
import com.singularity.todo.test.helpers.assertTextNotExists
import com.singularity.todo.test.helpers.clickTag
import com.singularity.todo.test.helpers.clickTagScrolled
import com.singularity.todo.test.helpers.runIsolatedComposeTest
import com.singularity.todo.test.helpers.unclippedBoundsOfTag
import com.singularity.todo.test.helpers.windowBoundsOfTag
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The planning sheet of the task detail, driven the way a user drives it.
 *
 * The window is 360×800 on purpose: that is the narrowest phone the app claims
 * to work on, and it is the size at which a sheet with a calendar in it grows
 * taller than the screen. The sheet's own answer is the action bar pinned to
 * the bottom — the test at the end of this class is the one that says the
 * answer held.
 */
@Tag("fast")
class TaskSchedulingSheetTest {
    private val today = LocalDate(2026, 10, 15)

    @Test
    fun `the date the task already has is on the sheet when it opens`() =
        runIsolatedComposeTest(width = 360, height = 800) {
            setContent {
                TaskSchedulingSheet(
                    dueDate = LocalDate(2026, 10, 20),
                    dueTime = null,
                    deadline = null,
                    someday = false,
                    today = today,
                    initialTarget = SchedulingTarget.DUE_DATE,
                    onConfirm = {},
                    onDismiss = {},
                )
            }

            assertTextDisplayed("Tue, 20 Oct 2026")
        }

    @Test
    fun `a tapped day is the date the sheet hands over`() =
        runIsolatedComposeTest(width = 360, height = 800) {
            var result: TaskSchedulingResult? = null
            setContent {
                TaskSchedulingSheet(
                    dueDate = null,
                    dueTime = null,
                    deadline = null,
                    someday = false,
                    today = today,
                    initialTarget = SchedulingTarget.DUE_DATE,
                    onConfirm = { result = it },
                    onDismiss = {},
                )
            }

            clickTagScrolled(TestTags.taskSchedulingDay("2026-10-21"))
            clickTag(TestTags.TASK_SCHEDULING_CONFIRM)

            assertEquals(LocalDate(2026, 10, 21), result?.dueDate)
        }

    @Test
    fun `cancelling writes nothing at all`() =
        runIsolatedComposeTest(width = 360, height = 800) {
            var written = false
            var dismissed = false
            setContent {
                TaskSchedulingSheet(
                    dueDate = LocalDate(2026, 10, 20),
                    dueTime = null,
                    deadline = null,
                    someday = false,
                    today = today,
                    initialTarget = SchedulingTarget.DUE_DATE,
                    onConfirm = { written = true },
                    onDismiss = { dismissed = true },
                )
            }

            clickTagScrolled(TestTags.taskSchedulingDay("2026-10-21"))
            clickTag(TestTags.TASK_SCHEDULING_CANCEL)

            assertFalse(written, "a cancelled sheet writes nothing")
            assertTrue(dismissed, "a cancelled sheet dismisses itself")
        }

    @Test
    fun `clearing the date takes the time with it and leaves the deadline alone`() =
        runIsolatedComposeTest(width = 360, height = 800) {
            var result: TaskSchedulingResult? = null
            setContent {
                TaskSchedulingSheet(
                    dueDate = LocalDate(2026, 10, 20),
                    dueTime = LocalTime(9, 30),
                    deadline = LocalDate(2026, 10, 30),
                    someday = false,
                    today = today,
                    initialTarget = SchedulingTarget.DUE_DATE,
                    onConfirm = { result = it },
                    onDismiss = {},
                )
            }

            clickTag(TestTags.TASK_SCHEDULING_CLEAR)
            clickTag(TestTags.TASK_SCHEDULING_CONFIRM)

            assertNull(result?.dueDate, "the date is cleared")
            assertNull(result?.dueTime, "the time goes with the date it belonged to")
            assertEquals(
                LocalDate(2026, 10, 30),
                result?.deadline,
                "the deadline is a fact of its own and survives",
            )
        }

    @Test
    fun `the deadline is edited on its own`() =
        runIsolatedComposeTest(width = 360, height = 800) {
            var result: TaskSchedulingResult? = null
            setContent {
                TaskSchedulingSheet(
                    dueDate = LocalDate(2026, 10, 20),
                    dueTime = null,
                    deadline = null,
                    someday = false,
                    today = today,
                    initialTarget = SchedulingTarget.DEADLINE,
                    onConfirm = { result = it },
                    onDismiss = {},
                )
            }

            clickTagScrolled(TestTags.taskSchedulingDay("2026-11-03"))
            clickTag(TestTags.TASK_SCHEDULING_CONFIRM)

            assertEquals(
                LocalDate(2026, 11, 3),
                result?.deadline,
                "the day the user tapped is the deadline",
            )
            assertEquals(
                LocalDate(2026, 10, 20),
                result?.dueDate,
                "the due date is not the deadline and is left alone",
            )
        }

    @Test
    fun `the someday preset takes the date away and says so`() =
        runIsolatedComposeTest(width = 360, height = 800) {
            var result: TaskSchedulingResult? = null
            setContent {
                TaskSchedulingSheet(
                    dueDate = LocalDate(2026, 10, 20),
                    dueTime = null,
                    deadline = null,
                    someday = false,
                    today = today,
                    initialTarget = SchedulingTarget.DUE_DATE,
                    onConfirm = { result = it },
                    onDismiss = {},
                )
            }

            clickTag(TestTags.TASK_SCHEDULING_SOMEDAY_CHIP)

            // The chip is the state, and the date the task had is gone from the
            // row — the row reads «Someday» now. The word itself is not
            // asserted: the chip carries it too, and two nodes with the same
            // text are two nodes a text assertion cannot choose between.
            assertTagSelected(TestTags.TASK_SCHEDULING_SOMEDAY_CHIP)
            assertTextNotExists("Tue, 20 Oct 2026")

            clickTag(TestTags.TASK_SCHEDULING_CONFIRM)

            assertTrue(result?.someday == true, "the task is a someday one")
            assertNull(result?.dueDate, "someday means no date")
        }

    /**
     * The calendar opens on the month of the fact the sheet is editing.
     *
     * It used to open on the due date's month whatever the sheet was editing,
     * so a task with a January due date and a June deadline showed January when
     * the user came for the deadline — and the user had to page forward to find
     * the fact they came for.
     */
    @Test
    fun `the deadline sheet opens on the month of the deadline`() =
        runIsolatedComposeTest(width = 360, height = 800) {
            setContent {
                TaskSchedulingSheet(
                    dueDate = LocalDate(2026, 1, 20),
                    dueTime = null,
                    deadline = LocalDate(2026, 6, 15),
                    someday = false,
                    today = LocalDate(2026, 1, 15),
                    initialTarget = SchedulingTarget.DEADLINE,
                    onConfirm = {},
                    onDismiss = {},
                )
            }

            // The day cells are addressed by their date, so the assertion says
            // which month is on the screen without naming a month in a language.
            assertTagExists(TestTags.taskSchedulingDay("2026-06-15"))
            assertTagNotExists(TestTags.taskSchedulingDay("2026-01-20"))
        }

    /**
     * The grid edits the focused fact, so the calendar follows the focus: the
     * deadline row brings the deadline's month.
     */
    @Test
    fun `tapping the deadline row brings the deadline's month`() =
        runIsolatedComposeTest(width = 360, height = 800) {
            setContent {
                TaskSchedulingSheet(
                    dueDate = LocalDate(2026, 1, 20),
                    dueTime = null,
                    deadline = LocalDate(2026, 6, 15),
                    someday = false,
                    today = LocalDate(2026, 1, 15),
                    initialTarget = SchedulingTarget.DUE_DATE,
                    onConfirm = {},
                    onDismiss = {},
                )
            }

            clickTag(TestTags.TASK_SCHEDULING_DEADLINE_ROW)

            assertTagExists(TestTags.taskSchedulingDay("2026-06-15"))
        }

    @Test
    fun `the primary actions are on the screen at 360 by 800`() =
        runIsolatedComposeTest(width = 360, height = 800) {
            setContent {
                TaskSchedulingSheet(
                    dueDate = LocalDate(2026, 10, 20),
                    dueTime = LocalTime(9, 30),
                    deadline = LocalDate(2026, 10, 30),
                    someday = false,
                    today = today,
                    initialTarget = SchedulingTarget.DUE_DATE,
                    onConfirm = {},
                    onDismiss = {},
                )
            }

            // The calendar makes the sheet taller than the screen, so the action
            // bar has to be pinned rather than scrolled: an action row that
            // scrolls away is an action row nobody can reach.
            listOf(
                TestTags.TASK_SCHEDULING_CLEAR,
                TestTags.TASK_SCHEDULING_CANCEL,
                TestTags.TASK_SCHEDULING_CONFIRM,
            ).forEach { tag ->
                assertTagDisplayed(tag)
                val button = unclippedBoundsOfTag(tag)
                val window = windowBoundsOfTag(tag)
                assertTrue(
                    button.bottom <= window.bottom,
                    "the action '$tag' sits at ${button.bottom} dp, below the " +
                        "window's ${window.bottom} dp",
                )
            }
        }
}
