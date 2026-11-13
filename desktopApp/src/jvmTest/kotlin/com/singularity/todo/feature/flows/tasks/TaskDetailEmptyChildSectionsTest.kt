package com.singularity.todo.feature.flows.tasks

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.performClick
import com.singularity.todo.core.platform.TimeZoneProvider
import com.singularity.todo.core.platform.todayAt
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.test.fakes.FakeClock
import com.singularity.todo.test.helpers.assertTagNotExists
import com.singularity.todo.test.helpers.assertTagTextEquals
import com.singularity.todo.test.helpers.assertTextNotExists
import com.singularity.todo.test.helpers.awaitTag
import com.singularity.todo.test.helpers.awaitTagGone
import com.singularity.todo.test.helpers.awaitText
import com.singularity.todo.test.helpers.clickTag
import com.singularity.todo.test.helpers.clickTagScrolled
import com.singularity.todo.test.helpers.runDesktopAppTest
import com.singularity.todo.test.helpers.scrollToTag
import com.singularity.todo.test.helpers.tapTab
import com.singularity.todo.test.helpers.tasks
import com.singularity.todo.test.helpers.typeIntoTag
import kotlinx.datetime.TimeZone
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.time.Instant

/**
 * The task detail of a task that has only a title.
 *
 * ## The rule this guards
 *
 * The task detail shows what the task has and offers what it does not. A section
 * with nothing in it — the attachments, the logbook, the related tasks, the time —
 * is not drawn, and neither is the row of a state nobody claimed. The checklist and
 * the subtasks are different: their header is the way to write into them, so they
 * stay as a header, closed, and open on a tap.
 *
 * The `+ Ещё` row is the way in to everything that is not on the screen. It is
 * drawn only when there is something to offer.
 *
 * ## What it asserts
 *
 * * The empty hideable sections and the unset state row are not on the screen.
 * * The checklist and the subtasks are on the screen as closed headers, and they
 *   open on a tap.
 * * No section reports a count over nothing.
 * * The `+ Ещё` sheet offers the hidden properties, and picking one puts it on the
 *   screen.
 */
@OptIn(ExperimentalTestApi::class)
@Tag("slow")
@Tag("slow:compose")
class TaskDetailEmptyChildSectionsTest {
    @Test
    @DisplayName("the task detail of a task with only a title shows what it has and offers what it does not")
    fun the_task_detail_of_a_task_with_only_a_title_shows_what_it_has_and_offers_what_it_does_not() =
        runDesktopAppTest(
            clock = TEST_CLOCK,
            overrides = module { single<TimeZoneProvider> { TestTimeZone } },
        ) { koin ->
            tasks(koin).given(due = todayAt(TEST_CLOCK, TEST_ZONE), title = "Only a title")

            tapTab("Today")
            awaitTag(TestTags.taskItem("Only a title")).performClick()
            awaitTag(TestTags.TASK_EDITOR_TITLE_INPUT)

            // The checklist and the subtasks are on the screen as closed headers.
            // They are the only two that cannot be hidden: their header is the way
            // to write into them.
            awaitTag(TestTags.TASK_CHECKLIST_SECTION)
            awaitTag(TestTags.SUBTASKS_SECTION)

            // A closed section shows no content — and no count over nothing.
            assertTagNotExists(TestTags.CHECKLIST_INLINE_ADD_INPUT)
            assertTagNotExists(TestTags.SUBTASK_ADD_INPUT)
            assertTextNotExists("Checklist (0/0)")
            assertTextNotExists("Subtasks (0/0)")

            // The sections with nothing in them are not on the screen at all, and
            // neither is the row of a state nobody claimed.
            assertTagNotExists(TestTags.ATTACHMENTS_SECTION)
            assertTagNotExists(TestTags.LOGBOOK_SECTION)
            assertTagNotExists(TestTags.TASK_DETAIL_RELATED_TASKS_SECTION)
            assertTagNotExists(TestTags.TimeTracking.SECTION)
            assertTagNotExists(TestTags.TASK_EDITOR_WORKFLOW_STATE_ROW)

            // The row that offers them is on the screen: there is something to
            // offer.
            awaitTag(TestTags.TASK_EDITOR_MORE_PARAMETERS_ROW)

            // The checklist opens on a tap of its header, and the way in is there.
            clickTagScrolled(TestTags.TASK_CHECKLIST_SECTION)
            awaitTag(TestTags.CHECKLIST_INLINE_ADD_INPUT)
            scrollToTag(TestTags.CHECKLIST_EMPTY_HINT)
            assertTagTextEquals(
                TestTags.CHECKLIST_EMPTY_HINT,
                "No checklist items yet. Add the first one below.",
            )

            // The first checklist item is added from the screen itself.
            scrollToTag(TestTags.CHECKLIST_INLINE_ADD_INPUT)
            typeIntoTag(TestTags.CHECKLIST_INLINE_ADD_INPUT, "Milk")
            clickTag(TestTags.CHECKLIST_INLINE_ADD_BUTTON)
            awaitText("Checklist (0/1)")
            // The section is no longer empty, so it no longer says it is.
            awaitTagGone(TestTags.CHECKLIST_EMPTY_HINT)

            // The same for the subtasks.
            //
            // The snackbar that the checklist item raised is still on the screen:
            // it is drawn over the bottom of the window. The editor keeps the band
            // it takes clear, so the header is still pressed by an ordinary
            // click.
            clickTagScrolled(TestTags.SUBTASKS_SECTION)
            awaitTag(TestTags.SUBTASK_ADD_INPUT)
            scrollToTag(TestTags.SUBTASK_ADD_INPUT)
            typeIntoTag(TestTags.SUBTASK_ADD_INPUT, "Child task")
            clickTag(TestTags.SUBTASK_ADD_BUTTON)
            awaitText("Subtasks (0/1)")
            awaitTagGone(TestTags.SUBTASKS_EMPTY_HINT)

            // The sheet offers exactly the properties that are not on the screen.
            //
            // The `+ Ещё` row is the last row of the editor, and the snackbar that
            // the subtask raised is drawn over the bottom of the window — over
            // that row. The row is the only way in to the properties that are not
            // on the screen, so it has to be reachable while the snackbar is up:
            // the editor keeps the band the snackbar takes clear, and the row is
            // opened with an ordinary click, the way a user opens it.
            awaitTag(TestTags.SNACKBAR_MESSAGE)
            clickTagScrolled(TestTags.TASK_EDITOR_MORE_PARAMETERS_ROW)
            awaitTag(TestTags.TASK_EDITOR_MORE_PARAMETERS_SHEET)
            awaitTag(TestTags.moreParameterRow("Attachments"))
            awaitTag(TestTags.moreParameterRow("Logbook"))
            awaitTag(TestTags.moreParameterRow("RelatedTasks"))
            awaitTag(TestTags.moreParameterRow("TimeTracking"))
            awaitTag(TestTags.moreParameterRow("WorkflowState"))

            // Picking one puts it on the screen.
            clickTag(TestTags.moreParameterRow("Attachments"))
            awaitTag(TestTags.ATTACHMENTS_SECTION)
        }

    private object TestTimeZone : TimeZoneProvider {
        override fun current(): TimeZone = TEST_ZONE
    }

    private companion object {
        val TEST_ZONE: TimeZone = TimeZone.UTC

        /**
         * Midday UTC on a Saturday, so no timezone that can reach this test can
         * roll the date across a midnight boundary and empty the Today tab.
         */
        val TEST_CLOCK = FakeClock(Instant.parse("2026-03-14T12:00:00Z"))
    }
}
