
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.feature.tasks

import com.singularity.todo.test.fakes.FakeClock
import com.singularity.todo.test.fakes.TEST_TZ
import co.touchlab.kermit.Logger
import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.draft.FakeDraftStore
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.tasks.domain.usecase.CreateTaskFromDraftUseCase
import com.singularity.todo.feature.tasks.presentation.state.TaskCreateIntent
import com.singularity.todo.feature.tasks.presentation.state.TaskDraft
import com.singularity.todo.feature.tasks.presentation.viewmodel.TaskCreateDeps
import com.singularity.todo.feature.tasks.presentation.viewmodel.TaskCreateViewModel
import com.singularity.todo.test.fakes.FakeAttachmentRepository
import com.singularity.todo.test.fakes.FakeChecklistRepository
import com.singularity.todo.test.fakes.FakeProfileAwareCurrentUser
import com.singularity.todo.test.fakes.FakeTaskRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Instant

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
@Tag("fast")
class TaskCreateDebounceTest {

    /**
     * A fixed instant, so a draft that resolves `DueDateOption.Today` gets a date
     * this file can state. The use case was handed `Clock.System` before #91, so
     * the resolved date was a property of the day the suite ran — and nothing in
     * these tests asserted it, so nothing failed when it moved.
     */
    private val taskNow: Instant = Instant.parse("2026-09-16T09:00:00Z")

    private val testUserId = UserId("test-user")
    private val fakeTaskRepo = FakeTaskRepository()
    private val fakeDraftStore = FakeDraftStore()
    private val fakeCurrentUser = FakeProfileAwareCurrentUser(initialUserId = testUserId)
    private val fakeChecklistRepository = FakeChecklistRepository()
    private val fakeAttachmentRepository = FakeAttachmentRepository()

    private fun createVm(scope: CoroutineScope): TaskCreateViewModel {
        val deps = TaskCreateDeps(
            createFromDraft = CreateTaskFromDraftUseCase(
                fakeTaskRepo,
                FakeClock(taskNow),
                TEST_TZ,
                fakeCurrentUser,
                fakeChecklistRepository,
                fakeAttachmentRepository,
            ),
            logger = Logger.withTag("TaskCreate"),
            draftStore = fakeDraftStore,
        )
        return TaskCreateViewModel(
            deps = deps,
            initialDueDate = null,
            sectionPrefillKey = null,
            scope = AutoCloseableCoroutineScope(scope.coroutineContext),
        )
    }

    // FakeDraftStore stores bare keys (no user prefix), matching TaskCreateViewModel's bare key usage.
    private val draftKey get() = TaskCreateDeps.DRAFT_KEY

    @Test
    fun `draft saved after debounce delay elapses`() = runTest {
        val vm = createVm(backgroundScope)
        // Wait for init coroutines to settle (restore + debounce collector)
        advanceTimeBy(1_000)
        runCurrent()

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
        advanceTimeBy(1_000)
        runCurrent()

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
        advanceTimeBy(1_000)
        runCurrent()

        vm.onIntent(TaskCreateIntent.TitleChanged("Task to create"))
        // Advance past debounce delay
        advanceTimeBy(510)
        runCurrent()
        assertNotNull(fakeDraftStore.load(draftKey, TaskDraft.serializer()))

        // Trigger save (title is non-blank → createTask is called)
        vm.onIntent(TaskCreateIntent.SaveClicked)
        // Wait for save + clear to complete
        advanceTimeBy(1_000)
        runCurrent()

        assertNull(fakeDraftStore.load(draftKey, TaskDraft.serializer()))
    }
}
