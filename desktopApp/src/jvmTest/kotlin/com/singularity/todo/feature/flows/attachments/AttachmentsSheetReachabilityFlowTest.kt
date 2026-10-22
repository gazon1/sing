package com.singularity.todo.feature.flows.attachments

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.performClick
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.test.fakes.FakeClock
import com.singularity.todo.test.helpers.assertTagDisplayed
import com.singularity.todo.test.helpers.awaitTag
import com.singularity.todo.test.helpers.runDesktopAppTest
import com.singularity.todo.test.helpers.scrollToTag
import com.singularity.todo.test.helpers.tapTab
import com.singularity.todo.test.helpers.tasks
import kotlinx.datetime.LocalDate
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.time.Instant

/**
 * The task detail has to offer a way into the attachments sheet.
 *
 * The sheet — with the URL field, the file picker and the list of attachments —
 * was written, wired into the task editor's sheet host, and called by nothing:
 * the task detail passed `attachments = null`, so the sheet had no callbacks, and
 * no row, menu entry or top-bar action ever asked for it. A URL attachment had
 * no way in at all.
 *
 * The id the flows look for, `attachments_add_file_row`, is on the row that
 * opens the sheet; the sheet's own file-picker button has its own id. Both are
 * asserted here, because the two used to share one and the collision is what
 * made the flow ambiguous.
 *
 * ## Coverage
 *
 * - a task detail shows the row that opens the attachments sheet
 * - tapping the row puts the sheet on screen (its URL field is displayed)
 * - the sheet's file picker is a separate node from the row that opens it
 */
@OptIn(ExperimentalTestApi::class)
@Tag("slow")
@Tag("slow:compose")
class AttachmentsSheetReachabilityFlowTest {

    @Test
    fun task_detail_opens_the_attachments_sheet() = runDesktopAppTest(
        clock = CLOCK,
        checkA11y = true,
    ) { koin ->
        tasks(koin).given(due = today, title = "Task with an attachment")

        tapTab("Today")
        awaitTag(TestTags.taskItem("Task with an attachment")).performClick()
        awaitTag(TestTags.TASK_EDITOR_TITLE_INPUT)

        // The affordance: a row on the task that opens the attachments sheet.
        // It sits below the fold of the test window, so it is scrolled to first.
        scrollToTag(TestTags.ATTACHMENTS_ADD_FILE_ROW)
        assertTagDisplayed(TestTags.ATTACHMENTS_ADD_FILE_ROW)
        awaitTag(TestTags.ATTACHMENTS_ADD_FILE_ROW).performClick()

        // The sheet is up — its URL field is the thing that was unreachable.
        assertTagDisplayed(TestTags.ATTACHMENTS_URL_INPUT)
        assertTagDisplayed(TestTags.ATTACHMENTS_ADD_URL_BUTTON)
    }

    @Test
    fun the_sheet_file_picker_is_not_the_row_that_opens_the_sheet() = runDesktopAppTest(
        clock = CLOCK,
        checkA11y = true,
    ) { koin ->
        tasks(koin).given(due = today, title = "Task with a file attachment")

        tapTab("Today")
        awaitTag(TestTags.taskItem("Task with a file attachment")).performClick()
        awaitTag(TestTags.TASK_EDITOR_TITLE_INPUT)

        scrollToTag(TestTags.ATTACHMENTS_ADD_FILE_ROW)
        awaitTag(TestTags.ATTACHMENTS_ADD_FILE_ROW).performClick()
        assertTagDisplayed(TestTags.ATTACHMENTS_URL_INPUT)

        // Two different controls, two different ids: the row opens the sheet, the
        // button inside it picks a file. One id for both made the flow's tap
        // ambiguous — it could hit either.
        assertTagDisplayed(TestTags.ATTACHMENTS_PICK_FILE_BUTTON)
    }

    private companion object {
        /** Mid-month, so no assertion in this file straddles a boundary. */
        val FIXED_NOW: Instant = Instant.parse("2026-09-16T10:00:00Z")
        val CLOCK: FakeClock = FakeClock(FIXED_NOW)
        val today: LocalDate = LocalDate(2026, 9, 16)
    }
}
