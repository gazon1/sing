package com.singularity.todo.feature.flows.tasks

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.performClick
import com.singularity.todo.core.platform.TimeZoneProvider
import com.singularity.todo.core.platform.todayAt
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.test.fakes.FakeClock
import com.singularity.todo.test.helpers.assertTagDisplayed
import com.singularity.todo.test.helpers.assertTagTextEquals
import com.singularity.todo.test.helpers.assertTextNotExists
import com.singularity.todo.test.helpers.awaitTag
import com.singularity.todo.test.helpers.awaitTagGone
import com.singularity.todo.test.helpers.awaitText
import com.singularity.todo.test.helpers.clickTag
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
 * A child section with nothing in it is drawn, and it says so: the checklist, the
 * subtasks and the logbook keep their card, the card names what is missing, and no
 * card reports a count over nothing. The sections cannot simply hide themselves
 * when they are empty — each carries the only way to add its first item — so the
 * empty state has to read as deliberate rather than as a leftover.
 *
 * ## What it asserts
 *
 * * No empty count: no "Checklist (0/0)", no "Subtasks (0/0)", no "Attachments (0)".
 * * Each empty section names its emptiness in a line of its own.
 * * The ways in still work: the first checklist item and the first subtask are added
 *   from the detail screen itself, the logbook's add-note control is on screen, and
 *   a task with no attachments still has a row that opens the attachments sheet.
 */
@OptIn(ExperimentalTestApi::class)
@Tag("slow")
@Tag("slow:compose")
class TaskDetailEmptyChildSectionsTest {
    @Test
    @DisplayName("the task detail of a task with only a title says what its empty sections are")
    fun the_task_detail_of_a_task_with_only_a_title_says_what_its_empty_sections_are() =
        runDesktopAppTest(
            clock = TEST_CLOCK,
            overrides = module { single<TimeZoneProvider> { TestTimeZone } },
        ) { koin ->
            tasks(koin).given(due = todayAt(TEST_CLOCK, TEST_ZONE), title = "Only a title")

            tapTab("Today")
            awaitTag(TestTags.taskItem("Only a title")).performClick()
            awaitTag(TestTags.TASK_EDITOR_TITLE_INPUT)

            // The sections are on screen before anything is asserted about them —
            // otherwise "no empty count" would pass on a screen that had not drawn
            // them yet.
            awaitTag(TestTags.TASK_CHECKLIST_SECTION)
            awaitTag(TestTags.SUBTASKS_SECTION)
            awaitTag(TestTags.ATTACHMENTS_ADD_FILE_ROW)

            // No section reports a count over nothing.
            assertTextNotExists("Checklist (0/0)")
            assertTextNotExists("Subtasks (0/0)")
            assertTextNotExists("Attachments (0)")

            // Each empty section says what it is instead of leaving a bare card.
            scrollToTag(TestTags.CHECKLIST_EMPTY_HINT)
            assertTagDisplayed(TestTags.CHECKLIST_EMPTY_HINT)
            assertTagTextEquals(
                TestTags.CHECKLIST_EMPTY_HINT,
                "No checklist items yet. Add the first one below.",
            )

            scrollToTag(TestTags.SUBTASKS_EMPTY_HINT)
            assertTagDisplayed(TestTags.SUBTASKS_EMPTY_HINT)
            assertTagTextEquals(
                TestTags.SUBTASKS_EMPTY_HINT,
                "No subtasks yet. Add the first one below.",
            )

            scrollToTag(TestTags.LOGBOOK_EMPTY_HINT)
            assertTagDisplayed(TestTags.LOGBOOK_EMPTY_HINT)
            assertTagTextEquals(
                TestTags.LOGBOOK_EMPTY_HINT,
                "No notes or time entries yet. Add a note to start the log.",
            )

            // The way in: the first checklist item is added from the screen itself.
            scrollToTag(TestTags.CHECKLIST_INLINE_ADD_INPUT)
            typeIntoTag(TestTags.CHECKLIST_INLINE_ADD_INPUT, "Milk")
            clickTag(TestTags.CHECKLIST_INLINE_ADD_BUTTON)
            awaitText("Checklist (0/1)")
            // The section is no longer empty, so it no longer says it is.
            awaitTagGone(TestTags.CHECKLIST_EMPTY_HINT)

            // The same for the first subtask.
            scrollToTag(TestTags.SUBTASK_ADD_INPUT)
            typeIntoTag(TestTags.SUBTASK_ADD_INPUT, "Child task")
            clickTag(TestTags.SUBTASK_ADD_BUTTON)
            awaitText("Subtasks (0/1)")
            awaitTagGone(TestTags.SUBTASKS_EMPTY_HINT)

            // The logbook's add-note control and the attachments' add-file row are
            // the ways in for the two sections that are not typed into.
            scrollToTag(TestTags.LOGBOOK_ADD_NOTE_BUTTON)
            assertTagDisplayed(TestTags.LOGBOOK_ADD_NOTE_BUTTON)
            scrollToTag(TestTags.ATTACHMENTS_ADD_FILE_ROW)
            assertTagDisplayed(TestTags.ATTACHMENTS_ADD_FILE_ROW)
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
