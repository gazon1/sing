package com.singularity.todo.feature.archive

import com.singularity.todo.core.observability.CrashReportingPort
import com.singularity.todo.core.observability.NoOpCrashReportingPort
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.test.fakes.FakeArchiveRepository
import com.singularity.todo.test.fakes.FakeTaskRepository
import com.singularity.todo.test.fakes.RecordingCrashReportingPort
import com.singularity.todo.test.fakes.testTask
import com.singularity.todo.test.helpers.VmScope
import com.singularity.todo.test.helpers.testVm
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Tests for the archive list's retry path and its bulk sweep.
 *
 * The archive used to observe the trash through a private `reload()` whose
 * `catch` sat on the observation's own chain. A `catch` there ends the
 * collection that handled the exception, so the Retry intent called `reload()`
 * on a screen that had nothing left to restart — and nothing in the code said
 * so. These tests drive the failure first and then assert the repository was
 * *asked again*, because a state that merely changed would pass a test that
 * only looked at the state.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Tag("fast")
class ArchiveViewModelTest {
    private val taskRepo = FakeTaskRepository()

    /**
     * The archive write path is not what this test is about, and the fake
     * persists nothing: the list these tests read is the trash observation.
     */
    private val archiveRepo = FakeArchiveRepository()

    private fun TestScope.createVm(reporter: CrashReportingPort = NoOpCrashReportingPort()): ArchiveViewModel =
        testVm(
            stateAccessor = { vm: ArchiveViewModel -> vm.stateFlow },
            vmScope = VmScope.Background,
        ) { scope ->
            ArchiveViewModel(
                archiveRepo = archiveRepo,
                taskRepo = taskRepo,
                crashReporter = reporter,
                scope = scope,
            )
        }.vm

    /**
     * A Retry after a failed load observes the archive again and leaves the error.
     *
     * The second subscription succeeds, which is the only way the test can tell
     * "the retry ran" from "the screen stayed where it was".
     */
    @Test
    fun `a retry after a failed load observes the archive again`() =
        runTest {
            taskRepo.nextObserveFilterFailure = IllegalStateException("the observation failed")

            val vm = createVm()
            // The failure travels through the trigger's branch and the combine,
            // so the scheduler is drained more than once.
            runCurrent()
            runCurrent()

            assertIs<ArchiveUiState.Error>(vm.stateFlow.value)
            assertEquals(1, taskRepo.observeByFilterCalls)

            vm.onIntent(ArchiveIntent.Retry)
            runCurrent()
            runCurrent()

            assertEquals(
                2,
                taskRepo.observeByFilterCalls,
                "Retry must observe the archive again, not only change the state",
            )
            assertIs<ArchiveUiState.Content>(
                vm.stateFlow.value,
                "the retry must leave the error state — the screen must not stay on the spinner",
            )
        }

    /**
     * A retry that fails again lands in the error state, not on a spinner.
     *
     * A retry that succeeds leaves the error; a retry that fails must publish
     * the second failure too. The trigger stays armed across both attempts, so
     * the defect this guards against is a second failure that never reaches the
     * screen and leaves it waiting for a list that will not come.
     */
    @Test
    fun `a retry that fails again lands in the error state`() =
        runTest {
            taskRepo.nextObserveFilterFailure = IllegalStateException("the first attempt failed")

            val vm = createVm()
            runCurrent()
            runCurrent()

            assertIs<ArchiveUiState.Error>(vm.stateFlow.value)

            taskRepo.nextObserveFilterFailure = IllegalStateException("the retry failed too")
            vm.onIntent(ArchiveIntent.Retry)
            runCurrent()
            runCurrent()

            assertEquals(2, taskRepo.observeByFilterCalls)
            assertIs<ArchiveUiState.Error>(
                vm.stateFlow.value,
                "a failed retry must land in the error state, not on the spinner",
            )
        }

    /**
     * The reporter receives the throwable the repository threw.
     *
     * The observation fails inside a channel hop, and a channel carries a *copy*
     * of the throwable — the original is the copy's cause. Reporting the copy
     * would file a throwable that never existed, so the identity is what this
     * asserts: the reporter has to receive the very instance the repository
     * threw, not a look-alike carrying the same message.
     */
    @Test
    fun `a failed observation reports the original cause`() =
        runTest {
            val failure = IllegalStateException("the observation failed")
            taskRepo.nextObserveFilterFailure = failure
            val reporter = RecordingCrashReportingPort()

            val vm = createVm(reporter)
            runCurrent()
            runCurrent()

            assertIs<ArchiveUiState.Error>(vm.stateFlow.value)

            val reported = reporter.reports.single { it.second == "archive.observe_failed" }
            assertSame(
                failure,
                reported.first,
                "the reporter must receive the throwable the repository threw",
            )
        }

    /**
     * The count the confirmation will show is the set the write would touch.
     *
     * The count is the repository's projection, not a filter over a task list. This
     * pins the set: completed and live, not active, not already archived.
     */
    @Test
    fun `the eligible count is the completed live tasks`() =
        runTest {
            taskRepo.seed(
                testTask(id = TaskId("completed"), title = "Completed", completedAt = NOW),
                testTask(id = TaskId("active"), title = "Active"),
                testTask(
                    id = TaskId("archived"),
                    title = "Already archived",
                    completedAt = NOW,
                    archivedAt = NOW,
                ),
            )

            val vm = createVm()
            runCurrent()

            val content = assertIs<ArchiveUiState.Content>(vm.stateFlow.value)
            assertEquals(
                1,
                content.eligibleCount,
                "only a completed task that is not archived is eligible",
            )
            assertEquals(1, content.tasks.size, "the archive list still shows the archived task")
        }

    /**
     * A successful sweep reports the repository's count, not the dialog's snapshot.
     *
     * Another device can change eligibility between opening the dialog and
     * confirming, so the number the user is told came from the write.
     */
    @Test
    fun `a successful archive reports the repository's count`() =
        runTest {
            archiveRepo.archiveCompletedTasksOverride = Result.success(3)
            val vm = createVm()
            val events = mutableListOf<ArchiveUiEvent>()
            val collector = launch { vm.events.collect { events += it } }
            runCurrent()

            try {
                vm.onIntent(ArchiveIntent.ArchiveCompletedTasks)
                runCurrent()

                assertEquals(1, archiveRepo.archiveCompletedTasksCalls)
                val archived = assertIs<ArchiveUiEvent.Archived>(events.single())
                assertEquals("Moved 3 tasks to archive", archived.message)
            } finally {
                collector.cancel()
            }
        }

    /**
     * A sweep that archived nothing says so.
     *
     * The dialog is gated at a positive count, so this is the concurrent case: another
     * device moved the same work between the dialog and the confirmation. The write is
     * idempotent; the user still needs to be told why nothing appeared.
     */
    @Test
    fun `a sweep that archived nothing says so`() =
        runTest {
            archiveRepo.archiveCompletedTasksOverride = Result.success(0)
            val vm = createVm()
            val events = mutableListOf<ArchiveUiEvent>()
            val collector = launch { vm.events.collect { events += it } }
            runCurrent()

            try {
                vm.onIntent(ArchiveIntent.ArchiveCompletedTasks)
                runCurrent()

                assertIs<ArchiveUiEvent.NothingToArchive>(events.single())
            } finally {
                collector.cancel()
            }
        }

    /**
     * A failed sweep must clear the in-flight guard.
     *
     * The guard lives in the ViewModel because the dialog's confirm button has no
     * `enabled` gate of its own. A failure that left the flag set would leave the
     * action unavailable until the screen was recreated.
     */
    @Test
    fun `a failed sweep makes the action available again`() =
        runTest {
            archiveRepo.archiveCompletedTasksOverride = Result.failure(IllegalStateException("disk full"))
            val vm = createVm()
            runCurrent()

            vm.onIntent(ArchiveIntent.ArchiveCompletedTasks)
            runCurrent()

            val afterFailure = assertIs<ArchiveUiState.Content>(vm.stateFlow.value)
            assertFalse(afterFailure.archiving, "a failed sweep must clear the in-flight guard")

            archiveRepo.archiveCompletedTasksOverride = Result.success(1)
            vm.onIntent(ArchiveIntent.ArchiveCompletedTasks)
            runCurrent()

            assertEquals(
                2,
                archiveRepo.archiveCompletedTasksCalls,
                "the action must be available again after a failed sweep",
            )
        }

    /** A single archived task is reported in the singular, not as "1 tasks". */
    @Test
    fun `a single archived task is reported in the singular`() =
        runTest {
            archiveRepo.archiveCompletedTasksOverride = Result.success(1)
            val vm = createVm()
            val events = mutableListOf<ArchiveUiEvent>()
            val collector = launch { vm.events.collect { events += it } }
            runCurrent()

            try {
                vm.onIntent(ArchiveIntent.ArchiveCompletedTasks)
                runCurrent()

                val archived = assertIs<ArchiveUiEvent.Archived>(events.single())
                assertEquals("Moved 1 task to archive", archived.message)
            } finally {
                collector.cancel()
            }
        }

    /**
     * A failed sweep reaches the user and the reporter.
     *
     * `catchTo` already unwraps the repository's `Result.failure`; this pins the
     * path so a later refactor cannot turn the failure back into silence.
     */
    @Test
    fun `a failed archive reaches the error event and the reporter`() =
        runTest {
            val failure = IllegalStateException("disk full")
            archiveRepo.archiveCompletedTasksOverride = Result.failure(failure)
            val reporter = RecordingCrashReportingPort()
            val vm = createVm(reporter)
            val events = mutableListOf<ArchiveUiEvent>()
            val collector = launch { vm.events.collect { events += it } }
            runCurrent()

            try {
                vm.onIntent(ArchiveIntent.ArchiveCompletedTasks)
                runCurrent()

                assertIs<ArchiveUiEvent.Error>(events.single())
                assertTrue(
                    reporter.reports.any { (throwable, key) ->
                        key == "Failed to archive completed tasks" && throwable === failure
                    },
                    "the repository's failure must reach the reporter: ${reporter.reports}",
                )
            } finally {
                collector.cancel()
            }
        }

    /**
     * One confirmation is one write.
     *
     * The flag is set synchronously, so a second dispatch that arrives before the
     * coroutine starts is refused. The dialog's confirm button has no `enabled`
     * gate of its own — the screen's menu item does — so the guard has to live in
     * the ViewModel where every dispatch meets it.
     */
    @Test
    fun `a second confirmation while the sweep runs is ignored`() =
        runTest {
            archiveRepo.archiveCompletedTasksOverride = Result.success(1)
            val vm = createVm()

            vm.onIntent(ArchiveIntent.ArchiveCompletedTasks)
            vm.onIntent(ArchiveIntent.ArchiveCompletedTasks)
            runCurrent()

            assertEquals(
                1,
                archiveRepo.archiveCompletedTasksCalls,
                "one confirmation is one write",
            )
        }

    private companion object {
        val NOW: Instant = Instant.fromEpochMilliseconds(1_000)
    }
}
