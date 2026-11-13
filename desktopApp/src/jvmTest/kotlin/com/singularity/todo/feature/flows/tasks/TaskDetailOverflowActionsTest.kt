package com.singularity.todo.feature.flows.tasks

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.performClick
import com.singularity.todo.core.platform.TimeZoneProvider
import com.singularity.todo.core.platform.todayAt
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.feature.tasks.domain.port.TaskRepository
import com.singularity.todo.test.fakes.FakeClock
import com.singularity.todo.test.helpers.awaitTag
import com.singularity.todo.test.helpers.awaitTagGone
import com.singularity.todo.test.helpers.clickTag
import com.singularity.todo.test.helpers.countNodesWithTagInAnyRoot
import com.singularity.todo.test.helpers.runDesktopAppTest
import com.singularity.todo.test.helpers.tapTab
import com.singularity.todo.test.helpers.tasks
import kotlinx.datetime.TimeZone
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.koin.core.Koin
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The task detail's overflow menu acts on the first tap (#2883).
 *
 * ## What this pins
 *
 * The menu is the only way to Archive or Delete a task from its detail screen, and
 * both actions are destructive. Two contracts have to hold for it to be an entry
 * point at all:
 *
 * * one tap on the overflow opens the menu;
 * * one tap on a row performs that row's action, and it is the action the row names.
 *
 * Neither contract had a test. `PopupReachabilityTest` opens the menu and asserts
 * where its window lives; it never presses a row, so a row that received a tap and
 * did nothing would have passed every test in the suite.
 *
 * ## Why the assertion is the write, not the menu
 *
 * "The menu closed" is not the same as "the action ran" — a tap on the scrim
 * dismisses the menu too. So each row is asserted where it lands: the task's
 * `isTrashed` in the repository, plus the undo offer Delete publishes.
 */
@OptIn(ExperimentalTestApi::class)
@Tag("slow")
@Tag("slow:compose")
class TaskDetailOverflowActionsTest {
    /**
     * One tap opens the menu, and one tap on Archive archives the task.
     *
     * Archive rather than Delete: archiving leaves the screen where it is and offers
     * no undo toast, so the state read afterwards is the row's own write rather than
     * a race with a snackbar.
     */
    @Test
    @DisplayName("one tap opens the task detail overflow and one tap on its row archives the task")
    fun one_tap_opens_the_menu_and_one_tap_on_a_row_archives_the_task() =
        runDesktopAppTest(
            clock = TEST_CLOCK,
            overrides = module { single<TimeZoneProvider> { TestTimeZone } },
        ) { koin ->
            val robot = tasks(koin).given(due = TODAY, title = "Overflow task")
            val taskId = requireNotNull(robot.lastTaskId)

            tapTab("Today")
            awaitTag(TestTags.taskItem("Overflow task")).performClick()
            awaitTag(TestTags.TASK_EDITOR_TITLE_INPUT)

            // One tap. The assertion is the menu itself, so a first tap consumed by
            // anything leaves nothing here to find.
            clickTag(TestTags.TASK_EDITOR_MORE_MENU)
            awaitTag(TestTags.EditorOverflow.ARCHIVE)

            // One tap on the row. Its action is a write, so the write is the assertion.
            clickTag(TestTags.EditorOverflow.ARCHIVE)

            assertTrue(
                taskRepository(koin).get(taskId)?.isTrashed == true,
                "the Archive row did not archive the task: the menu closed and nothing was written",
            )
            // The menu is gone once the row acted. A menu left open over the screen it
            // acted on is the failure that reads to a user as "my tap missed".
            awaitTagGone(TestTags.EditorOverflow.ARCHIVE)
        }

    /**
     * One tap on the Delete row deletes the task and offers the undo.
     *
     * Delete is the destructive row, and the undo offer is the only way back — so the
     * row has to reach it. The tag count also holds the rows apart: a menu drawing two
     * rows under one action's tag would pass a test that only looked for one.
     */
    @Test
    @DisplayName("one tap on the Delete row deletes the task and offers the undo")
    fun one_tap_on_the_delete_row_deletes_the_task_and_offers_the_undo() =
        runDesktopAppTest(
            clock = TEST_CLOCK,
            overrides = module { single<TimeZoneProvider> { TestTimeZone } },
        ) { koin ->
            val robot = tasks(koin).given(due = TODAY, title = "Delete me")
            val taskId = requireNotNull(robot.lastTaskId)

            tapTab("Today")
            awaitTag(TestTags.taskItem("Delete me")).performClick()
            awaitTag(TestTags.TASK_EDITOR_TITLE_INPUT)

            clickTag(TestTags.TASK_EDITOR_MORE_MENU)
            awaitTag(TestTags.EditorOverflow.DELETE)
            assertEquals(
                1,
                countNodesWithTagInAnyRoot(TestTags.EditorOverflow.DELETE),
                "the Delete row is not a single row: one tag, one action",
            )

            clickTag(TestTags.EditorOverflow.DELETE)

            // Delete is asynchronous: the row dispatches an intent and the write lands
            // after it. The undo offer is the screen's own signal that the write
            // succeeded, so it is awaited before the stored state is read.
            awaitTag(TestTags.SNACKBAR_ACTION)
            assertTrue(
                taskRepository(koin).get(taskId)?.isTrashed == true,
                "the Delete row offered the undo but wrote no delete",
            )
        }

    private fun taskRepository(koin: Koin): TaskRepository = koin.get()

    private object TestTimeZone : TimeZoneProvider {
        override fun current(): TimeZone = TEST_ZONE
    }

    private companion object {
        val TEST_ZONE: TimeZone = TimeZone.UTC

        /**
         * Midday UTC on a Saturday, so no timezone that can reach this test can roll
         * the date across midnight and empty the Today tab.
         */
        val TEST_CLOCK = FakeClock(Instant.parse("2026-03-14T12:00:00Z"))
        val TODAY = todayAt(TEST_CLOCK, TEST_ZONE)
    }
}
