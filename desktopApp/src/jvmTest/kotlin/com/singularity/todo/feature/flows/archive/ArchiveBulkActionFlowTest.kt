package com.singularity.todo.feature.flows.archive

import androidx.compose.ui.test.ExperimentalTestApi
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.feature.tasks.domain.model.TaskFilter
import com.singularity.todo.feature.tasks.domain.port.TaskRepository
import com.singularity.todo.test.fakes.FakeClock
import com.singularity.todo.test.helpers.assertTagDisplayed
import com.singularity.todo.test.helpers.assertTagNotEnabled
import com.singularity.todo.test.helpers.assertTagTextEquals
import com.singularity.todo.test.helpers.assertTextDisplayed
import com.singularity.todo.test.helpers.awaitTag
import com.singularity.todo.test.helpers.awaitTagGone
import com.singularity.todo.test.helpers.clickTag
import com.singularity.todo.test.helpers.runDesktopAppTest
import com.singularity.todo.test.helpers.tapTab
import com.singularity.todo.test.helpers.tasks
import kotlinx.coroutines.flow.first
import kotlinx.datetime.LocalDate
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.koin.core.Koin
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Instant

/**
 * The bulk archive moved off the content area and behind a confirmation.
 *
 * The screen used to carry a full-width "archive completed tasks" button. This
 * pins the replacement: the action is an overflow item, the confirmation names the
 * count, Cancel writes nothing, and Confirm performs the sweep exactly once.
 *
 * The write is never asserted through the UI alone. A row moving into the archive
 * list means the observation re-emitted; the repository read says whether the row
 * actually changed, which is the fact a cancel has to leave alone.
 */
@OptIn(ExperimentalTestApi::class)
@Tag("slow")
@Tag("slow:compose")
class ArchiveBulkActionFlowTest {
    @Test
    @DisplayName("the bulk archive lives in the overflow and confirms before writing")
    fun the_bulk_archive_confirms_before_writing() =
        runDesktopAppTest(clock = CLOCK, checkA11y = true) { koin ->
            tasks(koin).given(due = TODAY, title = TITLE, completed = true)

            tapTab("Archive")
            assertTagDisplayed(TestTags.ARCHIVE_EMPTY)

            clickTag(TestTags.ARCHIVE_OVERFLOW)
            awaitTag(TestTags.ARCHIVE_BULK_ACTION)
            clickTag(TestTags.ARCHIVE_BULK_ACTION)

            assertTextDisplayed("Archive completed tasks?")
            // The count is the reason the dialog exists: the confirm button names the
            // same number the menu item does, so a dialog that hardcodes or drops it
            // fails here rather than in a user's hands.
            assertTagTextEquals(TestTags.Dialog.CONFIRM, "Archive 1 task")
            clickTag(TestTags.Dialog.DISMISS)
            awaitTagGone(TestTags.Dialog.DISMISS)

            assertNull(archivedAt(koin), "Cancel must not archive anything")

            clickTag(TestTags.ARCHIVE_OVERFLOW)
            awaitTag(TestTags.ARCHIVE_BULK_ACTION)
            clickTag(TestTags.ARCHIVE_BULK_ACTION)

            assertTextDisplayed("Archive completed tasks?")
            assertTagTextEquals(TestTags.Dialog.CONFIRM, "Archive 1 task")
            clickTag(TestTags.Dialog.CONFIRM)

            // The row appears in the archive list only after the write and the
            // observation: waiting for it is what makes the read below a fact about
            // the finished operation rather than about the click.
            assertTagDisplayed(TestTags.taskItem(TITLE))
            assertNotNull(archivedAt(koin), "Confirm must archive the completed task")
        }

    @Test
    @DisplayName("the bulk archive is unavailable when no completed task is eligible")
    fun the_action_is_disabled_without_eligible_tasks() =
        runDesktopAppTest(clock = CLOCK, checkA11y = true) { koin ->
            // An active task is not eligible; the archive list is empty, and the
            // count is zero. The item stays discoverable and cannot be pressed.
            tasks(koin).given(due = TODAY, title = TITLE)

            tapTab("Archive")
            assertTagDisplayed(TestTags.ARCHIVE_EMPTY)

            clickTag(TestTags.ARCHIVE_OVERFLOW)
            assertTagNotEnabled(TestTags.ARCHIVE_BULK_ACTION)
        }

    /** The task's archived state, read from the live list or the trash. */
    private suspend fun archivedAt(koin: Koin): Instant? {
        val repo = koin.get<TaskRepository>()
        val live = repo.observeByFilter(TaskFilter.All).first().firstOrNull { it.title == TITLE }
        if (live != null) return live.archivedAt
        return repo
            .observeByFilter(TaskFilter.Trash)
            .first()
            .first { it.title == TITLE }
            .archivedAt
    }

    private companion object {
        const val TITLE = "Archive me"
        val TODAY: LocalDate = LocalDate(2026, 3, 14)
        val CLOCK: FakeClock = FakeClock(Instant.parse("2026-03-14T12:00:00Z"))
    }
}
