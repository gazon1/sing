package com.singularity.todo.ui

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.uiautomator.By
import com.singularity.todo.MainActivity
import com.singularity.todo.core.seed.SeedWriter
import com.singularity.todo.core.seed.TaskSeed
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.feature.notes.domain.port.NotesRepository
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.projects.domain.port.ProjectsRepository
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.port.TaskRepository
import com.singularity.todo.testing.InstrumentedTestBase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import kotlin.time.Clock

/**
 * The task detail's overflow menu, on the Android shell (#2883).
 *
 * ## Why this test is on a device
 *
 * On Android the shell owns the only top bar: `TaskDetailTopBar` publishes its
 * actions through `ScreenActionsHolder`, and the shell draws them in a bar the
 * screen never renders. The overflow button is therefore composed in one place
 * and painted in another, and its menu is a popup with a window of its own. A
 * desktop test renders the shared bar and pins the Compose logic; it cannot say
 * anything about the shell's bar, the Android semantics behind the resource ids,
 * or where the popup lands. Those are what #2883 is about.
 *
 * ## What it asserts
 *
 * * one tap on the overflow opens the menu;
 * * Archive and Delete are addressable as *clickable* rows by their tags;
 * * one tap on a row archives the task, and the write is read back.
 *
 * ## Why each row also asserts the offer it publishes
 *
 * Archive and Delete are the same repository write: `TaskLifecycleSlot.archive`
 * and `.delete` both call `softDelete`, and the Archive screen lists soft-deleted
 * tasks. `isTrashed` therefore proves *a* row ran, not *which* one — swapped
 * actions would pass on that flag alone. The rows are told apart by the offer
 * they publish, which is the user-visible difference: "Task archived" against
 * "Task deleted". That offer is also the synchronization point — it is published
 * only after the write succeeded, so the stored state is read after it rather
 * than raced with it.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class TaskDetailOverflowActionsTest : InstrumentedTestBase() {
    /** One tap opens the menu, and one tap on Archive archives the task. */
    @Test
    fun one_tap_opens_the_overflow_and_one_tap_on_archive_archives_the_task() {
        val scenario = launchApp()
        try {
            val taskId = seedTask(ARCHIVE_TASK)
            openTaskDetail(ARCHIVE_TASK)

            openOverflowAndAssertRowIsClickable(TestTags.EditorOverflow.ARCHIVE)
            device.tapTag(TestTags.EditorOverflow.ARCHIVE)

            // Archive's own offer: it arrives after the write, and it is what says
            // which row ran. Waiting for it is the synchronization, not a courtesy.
            device.awaitObject(
                By.res(TestTags.SNACKBAR_MESSAGE).text(ARCHIVED_OFFER),
                "the archive offer",
                MENU_TIMEOUT_MS,
            )
            assertTrue(
                "the Archive row wrote no archive. ${device.diagnostics()}",
                isTrashed(taskId),
            )
        } finally {
            scenario.close()
        }
    }

    /**
     * One tap on Delete deletes the task, and the delete offer is the one shown.
     *
     * This is the destructive row, and its offer is the only way back — so the row
     * has to reach it. That the offer says "Task deleted" and not "Task archived"
     * is what stops the two rows from being interchangeable.
     */
    @Test
    fun one_tap_on_delete_deletes_the_task_and_offers_the_delete_undo() {
        val scenario = launchApp()
        try {
            val taskId = seedTask(DELETE_TASK)
            openTaskDetail(DELETE_TASK)

            openOverflowAndAssertRowIsClickable(TestTags.EditorOverflow.DELETE)
            device.tapTag(TestTags.EditorOverflow.DELETE)

            device.awaitObject(
                By.res(TestTags.SNACKBAR_MESSAGE).text(DELETED_OFFER),
                "the delete offer",
                MENU_TIMEOUT_MS,
            )
            // The offer carries the undo action — the way back the row promises.
            device.awaitTag(
                TestTags.SNACKBAR_ACTION,
                "the delete offer's undo action",
                MENU_TIMEOUT_MS,
            )
            assertTrue(
                "the Delete row wrote no delete. ${device.diagnostics()}",
                isTrashed(taskId),
            )
        } finally {
            scenario.close()
        }
    }

    /**
     * One tap on the overflow, then the row it must produce.
     *
     * The row is asserted to be *clickable*: a `DropdownMenuItem` is rendered as a
     * clickable node whose label is a non-clickable child, and a test that found
     * only the label would be asserting on text that performs nothing.
     */
    private fun openOverflowAndAssertRowIsClickable(rowTag: String) {
        device.tapTag(TestTags.TASK_EDITOR_MORE_MENU)
        device.awaitTag(rowTag, "the overflow menu's $rowTag row", MENU_TIMEOUT_MS)
        assertNotNull(
            "the $rowTag row is not a clickable node: a tap on its label would not act. " +
                device.diagnostics(),
            device.findObject(By.res(rowTag))?.takeIf { it.isClickable },
        )
    }

    /**
     * Starts the app and waits for the shell.
     *
     * The repository and the seed writer are read from the app's own graph, so the
     * app has to have started before either is reachable.
     */
    private fun launchApp(): ActivityScenario<MainActivity> {
        device.wakeUp()
        runCatching { device.executeShellCommand("wm dismiss-keyguard") }
        val intent =
            Intent(
                ApplicationProvider.getApplicationContext(),
                MainActivity::class.java,
            )
        val scenario = ActivityScenario.launch<MainActivity>(intent)
        device.awaitShell()
        return scenario
    }

    /**
     * Writes the fixture this test addresses, through the app's own [SeedWriter].
     *
     * That is the same writer the debug deep link uses to seed a Maestro flow: the
     * row ends up live under the title the test names, so a row an earlier run left
     * trashed is brought back rather than duplicated.
     *
     * @return the id of the row the profile now holds under [title].
     */
    @Suppress("NoRunBlocking") // Instrumented test: the seed writer is a suspend API.
    private fun seedTask(title: String): TaskId =
        runBlocking {
            seedWriter().writeTask(TaskSeed(title = title, stamp = Clock.System.now())).id
        }

    /**
     * Opens the detail of the seeded task.
     *
     * The row is addressed by its generated tag, the same identity a Maestro flow
     * addresses — `TestTags.taskItem(title)` is the row's resource id. The seed is
     * undated, so the row is in Inbox's "No Date" bucket; that is where the
     * `seed-task` Maestro helper looks for the same fixture.
     */
    private fun openTaskDetail(title: String) {
        device.tapTag(INBOX_TAB)
        device.tapTag(TestTags.taskItem(title))
        device.awaitTag(
            TestTags.TASK_EDITOR_TITLE_INPUT,
            "the task detail editor",
            MENU_TIMEOUT_MS,
        )
    }

    /** Whether the stored task is archived. */
    @Suppress("NoRunBlocking") // Instrumented test: the repository is a suspend API.
    private fun isTrashed(taskId: TaskId): Boolean =
        runBlocking { taskRepository().get(taskId)?.isTrashed == true }

    @Suppress("NoRunBlocking") // Instrumented test: the graph is built by the app.
    private fun taskRepository(): TaskRepository = GlobalContext.get().get()

    private fun seedWriter(): SeedWriter {
        val koin = GlobalContext.get()
        return SeedWriter(
            tasks = koin.get<TaskRepository>(),
            notes = koin.get<NotesRepository>(),
            projects = koin.get<ProjectsRepository>(),
            currentUser = koin.get<ProfileAwareCurrentUser>(),
        )
    }

    private companion object {
        /** The tab an undated seeded task is listed under. */
        const val INBOX_TAB = "nav_tab_inbox"

        /** Distinct titles, so one test's fixture cannot be the other's row. */
        const val ARCHIVE_TASK = "Overflow archive task"
        const val DELETE_TASK = "Overflow delete task"

        /** The offers the two rows publish, and the only user-visible difference. */
        const val ARCHIVED_OFFER = "Task archived"
        const val DELETED_OFFER = "Task deleted"

        const val MENU_TIMEOUT_MS = 10_000L
    }
}
