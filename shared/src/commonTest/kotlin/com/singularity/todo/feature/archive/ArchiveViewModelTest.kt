package com.singularity.todo.feature.archive

import com.singularity.todo.test.fakes.FakeArchiveRepository
import com.singularity.todo.test.fakes.FakeTaskRepository
import com.singularity.todo.test.helpers.VmScope
import com.singularity.todo.test.helpers.testVm
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Tests for the archive list's retry path.
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

    private fun TestScope.createVm(): ArchiveViewModel =
        testVm(
            stateAccessor = { vm: ArchiveViewModel -> vm.stateFlow },
            vmScope = VmScope.Background,
        ) { scope ->
            ArchiveViewModel(archiveRepo = archiveRepo, taskRepo = taskRepo, scope = scope)
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
}
