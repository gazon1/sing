package com.singularity.todo.feature.tasks

import co.touchlab.kermit.Logger
import com.singularity.todo.core.coroutines.testScope
import com.singularity.todo.core.draft.FakeDraftStore
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.platform.Clock
import com.singularity.todo.feature.tasks.domain.usecase.CreateTaskFromDraftUseCase
import com.singularity.todo.feature.tasks.presentation.state.TaskCreateIntent
import com.singularity.todo.feature.tasks.presentation.state.TaskDraft
import com.singularity.todo.feature.tasks.presentation.viewmodel.TaskCreateDeps
import com.singularity.todo.feature.tasks.presentation.viewmodel.TaskCreateViewModel
import com.singularity.todo.test.fakes.FakeProfileAwareCurrentUser
import com.singularity.todo.test.fakes.FakeTaskRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds

/**
 * Unit tests for [TaskCreateViewModel] debounce + draft persistence behavior.
 *
 * Timing: uses virtual time via advanceTimeBy() + runCurrent().
 * Debounce threshold is 500ms; tests advance 510ms (fires) and 210ms (doesn't fire).
 *
 * Covered:
 * 1. Draft is saved after debounce delay elapses
 * 2. Draft is NOT saved before debounce delay elapses
 * 3. Draft is cleared after successful save
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TaskCreateDebounceTest {

    private val testUserId = UserId("test-user")
    private val fakeTaskRepo = FakeTaskRepository()
    private val fakeDraftStore = FakeDraftStore()
    private val fakeCurrentUser = FakeProfileAwareCurrentUser(initialUserId = testUserId)

    private fun createVm(scope: CoroutineScope): TaskCreateViewModel {
        val deps = TaskCreateDeps(
            createFromDraft = CreateTaskFromDraftUseCase(fakeTaskRepo, Clock, fakeCurrentUser),
            logger = Logger.withTag("TaskCreate"),
            draftStore = fakeDraftStore,
        )
        return TaskCreateViewModel(deps = deps, initialDueDate = null, scope = testScope(scope))
    }

    // FakeDraftStore stores bare keys (no user prefix), matching TaskCreateViewModel's bare key usage.
    private val draftKey get() = TaskCreateDeps.DRAFT_KEY

    @Test
    fun `draft saved after debounce delay elapses`() = runTest {
        val vm = createVm(backgroundScope)
        // Wait for init coroutines to settle (restore + debounce collector)
        advanceUntilIdle()

        // Type a title — triggers a new debounce window
        vm.onIntent(TaskCreateIntent.TitleChanged("Buy groceries"))

        // Draft NOT saved yet — debounce hasn't fired (only 10ms elapsed, delay = 500ms)
        assertNull(fakeDraftStore.load(draftKey, TaskDraft.serializer()))

        // Advance past the 500ms debounce delay
        advanceTimeBy(510)
        runCurrent()

        // Draft SHOULD be saved now
        val saved = fakeDraftStore.load(draftKey, TaskDraft.serializer())
        assertNotNull(saved)
        assertEquals("Buy groceries", saved.title)
    }

    @Test
    fun `draft NOT saved before debounce delay elapses`() = runTest {
        val vm = createVm(backgroundScope)
        advanceUntilIdle()

        vm.onIntent(TaskCreateIntent.TitleChanged("Quick note"))
        // Advance only 200ms — less than the 500ms debounce delay
        advanceTimeBy(210)
        runCurrent()

        val saved = fakeDraftStore.load(draftKey, TaskDraft.serializer())
        // debounce hasn't fired yet — still null
        assertNull(saved)
    }

    @Test
    fun `draft cleared after successful save`() = runTest {
        val vm = createVm(backgroundScope)
        advanceUntilIdle()

        vm.onIntent(TaskCreateIntent.TitleChanged("Task to create"))
        // Advance past debounce delay
        advanceTimeBy(510)
        runCurrent()
        assertNotNull(fakeDraftStore.load(draftKey, TaskDraft.serializer()))

        // Trigger save (title is non-blank → createTask is called)
        vm.onIntent(TaskCreateIntent.SaveClicked)
        // Wait for save + clear to complete
        advanceUntilIdle()

        assertNull(fakeDraftStore.load(draftKey, TaskDraft.serializer()))
    }
}
