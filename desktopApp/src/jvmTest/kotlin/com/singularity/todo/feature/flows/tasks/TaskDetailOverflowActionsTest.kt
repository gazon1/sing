package com.singularity.todo.feature.flows.tasks

import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.performClick
import com.singularity.todo.core.platform.TimeZoneProvider
import com.singularity.todo.core.platform.todayAt
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.port.TaskRepository
import com.singularity.todo.test.fakes.FakeClock
import com.singularity.todo.test.helpers.assertTagTextEquals
import com.singularity.todo.test.helpers.awaitTag
import com.singularity.todo.test.helpers.awaitTagGone
import com.singularity.todo.test.helpers.clickTag
import com.singularity.todo.test.helpers.runDesktopAppTest
import com.singularity.todo.test.helpers.tapTab
import com.singularity.todo.test.helpers.tasks
import kotlinx.datetime.TimeZone
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.koin.core.Koin
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The task detail's overflow menu performs the action its row names.
 *
 * `PopupReachabilityTest` opens this menu and asserts where its window lives; it
 * never presses a row. A row that took a tap and did nothing therefore passed
 * every test in the suite, and these two are the missing half: one tap on a row
 * reaches that row's write.
 *
 * ## Why the offer, and not just the archived flag
 *
 * Archive and Delete are one repository write — `TaskLifecycleSlot.archive` and
 * `.delete` both call `softDelete` — so the stored flag proves *a* row ran, not
 * which one. Swapped actions would pass on the flag alone. What the rows publish
 * is what differs to the user: "Task archived" against "Task deleted". Asserting
 * the offer both tells the rows apart and synchronizes the test — the offer is
 * published only after the write has succeeded, so the stored state is read after
 * it instead of raced with it.
 *
 * The Android shell's own bar, ids and popup are asserted on the device, in
 * `TaskDetailOverflowActionsTest` (androidTest): the desktop tier renders the
 * shared bar and cannot see the shell.
 */
@OptIn(ExperimentalTestApi::class)
@Tag("slow")
@Tag("slow:compose")
class TaskDetailOverflowActionsTest {
    /** One tap opens the menu, and one tap on Archive publishes the archive offer. */
    @Test
    @DisplayName("one tap opens the overflow and one tap on Archive archives the task")
    fun one_tap_opens_the_menu_and_one_tap_on_archive_archives_the_task() =
        runDesktopAppTest(
            clock = TEST_CLOCK,
            overrides = module { single<TimeZoneProvider> { TestTimeZone } },
        ) { koin ->
            val taskId = seedAndOpen(koin, OPEN_ARCHIVE_TASK)

            clickTag(TestTags.TASK_EDITOR_MORE_MENU)
            awaitTag(TestTags.EditorOverflow.ARCHIVE)
            clickTag(TestTags.EditorOverflow.ARCHIVE)

            awaitTag(TestTags.SNACKBAR_MESSAGE)
            assertTagTextEquals(TestTags.SNACKBAR_MESSAGE, ARCHIVED_OFFER)
            assertTrue(
                taskRepository(koin).get(taskId)?.isTrashed == true,
                "the Archive row did not archive the task: the menu closed and nothing was written",
            )
            // A menu left open over the screen it acted on reads to a user as "my tap missed".
            awaitTagGone(TestTags.EditorOverflow.ARCHIVE)
        }

    /**
     * One tap on Delete publishes the delete offer.
     *
     * The offer is where the way back lives, so the row has to reach it. Only its
     * presence is asserted: whether tapping Undo restores the task is #2723's, and
     * that defect is open — this test must not be read as saying the undo works.
     */
    @Test
    @DisplayName("one tap on Delete deletes the task and offers the undo")
    fun one_tap_on_delete_deletes_the_task_and_offers_the_undo() =
        runDesktopAppTest(
            clock = TEST_CLOCK,
            overrides = module { single<TimeZoneProvider> { TestTimeZone } },
        ) { koin ->
            val taskId = seedAndOpen(koin, OPEN_DELETE_TASK)

            clickTag(TestTags.TASK_EDITOR_MORE_MENU)
            awaitTag(TestTags.EditorOverflow.DELETE)
            clickTag(TestTags.EditorOverflow.DELETE)

            awaitTag(TestTags.SNACKBAR_MESSAGE)
            assertTagTextEquals(TestTags.SNACKBAR_MESSAGE, DELETED_OFFER)
            // The offer carries the undo action — the way back it promises.
            awaitTag(TestTags.SNACKBAR_ACTION)
            assertTrue(
                taskRepository(koin).get(taskId)?.isTrashed == true,
                "the Delete row did not delete the task",
            )
        }

    /**
     * Seeds a task under [title] and leaves its detail on screen.
     *
     * The detail is opened by pressing the row, so the caller's first action is the
     * overflow tap: the "one tap opens the menu" claim is measured from a settled
     * screen, not from a screen still arriving.
     *
     * @return the id of the seeded row, so the caller can read the write back.
     */
    private suspend fun DesktopComposeUiTest.seedAndOpen(
        koin: Koin,
        title: String,
    ): TaskId {
        val taskId = requireNotNull(tasks(koin).given(due = TODAY, title = title).lastTaskId)
        tapTab("Today")
        awaitTag(TestTags.taskItem(title)).performClick()
        awaitTag(TestTags.TASK_EDITOR_TITLE_INPUT)
        return taskId
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

        /** Distinct titles, so one test's fixture cannot be the other's row. */
        const val OPEN_ARCHIVE_TASK = "Overflow archive task"
        const val OPEN_DELETE_TASK = "Overflow delete task"

        /** The offers the two rows publish, and the only user-visible difference. */
        const val ARCHIVED_OFFER = "Task archived"
        const val DELETED_OFFER = "Task deleted"
    }
}
