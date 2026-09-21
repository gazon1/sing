package com.singularity.todo.feature.tasks

import co.touchlab.kermit.Logger
import com.singularity.todo.core.clock.FakeAutosaveScheduler
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Unit tests for [TaskCreateViewModel] debounce + draft persistence behavior.
 *
 * Timing: tests use real `delay()` to advance virtual time past the debounce
 * threshold set by [FakeAutosaveScheduler.delayMs]. The delay is set to 50ms
 * so tests are fast while remaining deterministic.
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
    private val fakeScheduler = FakeAutosaveScheduler().apply { setDelayMs(50L) }
    private val fakeCurrentUser = FakeProfileAwareCurrentUser(initialUserId = testUserId)

    private fun createVm(scope: CoroutineScope): TaskCreateViewModel {
        val deps = TaskCreateDeps(
            createFromDraft = CreateTaskFromDraftUseCase(fakeTaskRepo, Clock),
            currentUser = fakeCurrentUser,
            logger = Logger.withTag("TaskCreate"),
            draftStore = fakeDraftStore,
            autosaveScheduler = fakeScheduler,
        )
        return TaskCreateViewModel(deps = deps, initialDueDate = null, scope = testScope(scope))
    }

    private val draftKey get() = "${testUserId.value}:${TaskCreateDeps.DRAFT_KEY}"

    @Test
    fun `draft saved after debounce delay elapses`() = runTest {
        val vm = createVm(backgroundScope)
        // Wait for init coroutines to settle (restore + debounce collector)
        delay(10)

        // Type a title — triggers a new debounce window
        vm.onIntent(TaskCreateIntent.TitleChanged("Buy groceries"))

        // Draft NOT saved yet — debounce hasn't fired (only 10ms elapsed, delay = 50ms)
        assertNull(fakeDraftStore.load<TaskDraft>(draftKey, TaskDraft.serializer()))

        // Wait past the 50ms debounce delay
        delay(60)

        // Draft SHOULD be saved now
        val saved = fakeDraftStore.load<TaskDraft>(draftKey, TaskDraft.serializer())
        assertNotNull(saved)
        assertEquals("Buy groceries", saved.title)
    }

    @Test
    fun `draft NOT saved before debounce delay elapses`() = runTest {
        val vm = createVm(backgroundScope)
        delay(10)

        vm.onIntent(TaskCreateIntent.TitleChanged("Quick note"))
        // Wait only 20ms — less than the 50ms debounce delay
        delay(20)

        val saved = fakeDraftStore.load<TaskDraft>(draftKey, TaskDraft.serializer())
        // debounce hasn't fired yet — still null
        assertNull(saved)
    }

    @Test
    fun `draft cleared after successful save`() = runTest {
        val vm = createVm(backgroundScope)
        delay(10)

        vm.onIntent(TaskCreateIntent.TitleChanged("Task to create"))
        // Wait for debounce to fire and draft to be saved
        delay(100)
        assertNotNull(fakeDraftStore.load<TaskDraft>(draftKey, TaskDraft.serializer()))

        // Trigger save (title is non-blank → createTask is called)
        vm.onIntent(TaskCreateIntent.SaveClicked)
        // Wait for save + clear to complete
        delay(50)

        assertNull(fakeDraftStore.load<TaskDraft>(draftKey, TaskDraft.serializer()))
    }
}
