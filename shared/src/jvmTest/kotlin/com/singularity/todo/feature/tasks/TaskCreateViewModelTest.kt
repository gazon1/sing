package com.singularity.todo.feature.tasks

import co.touchlab.kermit.Logger
import com.singularity.todo.core.coroutines.testScope
import com.singularity.todo.core.draft.FakeDraftStore
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.platform.Clock
import com.singularity.todo.feature.tasks.domain.model.TaskPriority
import com.singularity.todo.feature.tasks.domain.usecase.CreateTaskFromDraftUseCase
import com.singularity.todo.feature.tasks.presentation.state.TaskCreateIntent
import com.singularity.todo.feature.tasks.presentation.state.TaskDraft
import com.singularity.todo.feature.tasks.presentation.viewmodel.TaskCreateDeps
import com.singularity.todo.feature.tasks.presentation.viewmodel.TaskCreateViewModel
import com.singularity.todo.test.fakes.FakeAuthRepository
import com.singularity.todo.test.fakes.FakeProfileAwareCurrentUser
import com.singularity.todo.test.fakes.FakeTaskRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.Month
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds

/**
 * Tests for [TaskCreateViewModel].
 *
 * Tests verify the two observable side-effects that are reliably testable:
 * 1. Repository state (task is created / not created based on input validity)
 * 2. DraftStore state (draft is saved after debounce, cleared after discard)
 *
 * Internal state (draft title, isDirty) requires an active StateFlow collector
 * since the VM uses `stateIn(scope, WhileSubscribed(5000), initial)`.
 * Those are verified via integration with the repository in the save tests.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TaskCreateViewModelTest {

    private val testUserId = UserId("test-user")
    private val fakeTaskRepo = FakeTaskRepository()
    private val fakeDraftStore = FakeDraftStore()
    private val fakeCurrentUser = FakeProfileAwareCurrentUser(
        FakeAuthRepository(
            initialSession = com.singularity.todo.core.auth.Session.Anonymous(testUserId),
        ),
    )

    private fun createVm(scope: CoroutineScope): TaskCreateViewModel {
        val deps = TaskCreateDeps(
            createFromDraft = CreateTaskFromDraftUseCase(fakeTaskRepo, Clock, fakeCurrentUser),
            logger = Logger.withTag("TaskCreateTest"),
            draftStore = fakeDraftStore,
        )
        return TaskCreateViewModel(deps = deps, initialDueDate = null, scope = testScope(scope))
    }

    // FakeDraftStore stores bare keys (no user prefix), matching TaskCreateViewModel's bare key usage.
    private val draftKey get() = TaskCreateDeps.DRAFT_KEY

    // ─── Draft restore (seed-if-empty) ────────────────────────────────────────

    @Test
    fun `draft restored from store on init subsequent save uses restored title`() = runTest {
        // Pre-seed a draft
        fakeDraftStore.save(draftKey, TaskDraft(title = "Restored task"), TaskDraft.serializer())
        val vm = createVm(backgroundScope)
        advanceUntilIdle()
        // Draft was restored (seed-if-empty pattern: restore only if current is initial).
        // Verify by saving without typing — should create task with restored title.
        vm.onIntent(TaskCreateIntent.SaveClicked)
        delay(100.milliseconds)
        assertEquals(1, fakeTaskRepo.tasks.value.size)
        assertEquals("Restored task", fakeTaskRepo.tasks.value.values.first().title)
    }

    // ─── SaveClicked — blank title ───────────────────────────────────────────

    @Test
    fun `SaveClicked with blank title does not create task`() = runTest {
        val vm = createVm(backgroundScope)
        advanceUntilIdle()
        assertEquals(0, fakeTaskRepo.tasks.value.size)
        vm.onIntent(TaskCreateIntent.SaveClicked)
        advanceUntilIdle()
        assertEquals(0, fakeTaskRepo.tasks.value.size)
    }

    // ─── SaveClicked — valid title ───────────────────────────────────────────

    @Test
    fun `SaveClicked with valid title creates task in repository`() = runTest {
        val vm = createVm(backgroundScope)
        advanceUntilIdle()
        vm.onIntent(TaskCreateIntent.TitleChanged("New task"))
        advanceUntilIdle()
        vm.onIntent(TaskCreateIntent.SaveClicked)
        // Wait for save coroutine to complete
        delay(100.milliseconds)
        assertEquals(1, fakeTaskRepo.tasks.value.size)
        assertEquals("New task", fakeTaskRepo.tasks.value.values.first().title)
    }

    @Test
    fun `SaveClicked with description creates task with description`() = runTest {
        val vm = createVm(backgroundScope)
        advanceUntilIdle()
        vm.onIntent(TaskCreateIntent.TitleChanged("Task with desc"))
        vm.onIntent(TaskCreateIntent.DescriptionChanged("Some description"))
        advanceUntilIdle()
        vm.onIntent(TaskCreateIntent.SaveClicked)
        delay(100.milliseconds)
        assertEquals(1, fakeTaskRepo.tasks.value.size)
        assertEquals("Some description", fakeTaskRepo.tasks.value.values.first().description)
    }

    @Test
    fun `SaveClicked with priority creates task with priority`() = runTest {
        val vm = createVm(backgroundScope)
        advanceUntilIdle()
        vm.onIntent(TaskCreateIntent.TitleChanged("Important task"))
        vm.onIntent(TaskCreateIntent.SetPriority(TaskPriority.High))
        advanceUntilIdle()
        vm.onIntent(TaskCreateIntent.SaveClicked)
        delay(100.milliseconds)
        assertEquals(TaskPriority.High, fakeTaskRepo.tasks.value.values.first().priority)
    }

    // ─── SaveClicked — clears draft ─────────────────────────────────────────

    @Test
    fun `SaveClicked clears draft after successful save`() = runTest {
        val vm = createVm(backgroundScope)
        advanceUntilIdle()
        vm.onIntent(TaskCreateIntent.TitleChanged("To be cleared"))
        advanceUntilIdle()
        vm.onIntent(TaskCreateIntent.SaveClicked)
        delay(100.milliseconds)
        // Draft should be cleared from store
        assertNull(fakeDraftStore.load(draftKey, TaskDraft.serializer()))
    }

    // ─── DueDate ────────────────────────────────────────────────────────────

    @Test
    fun `SetDueDate with valid date creates task with that date`() = runTest {
        val date = LocalDate(2026, Month.OCTOBER, 5)
        val vm = createVm(backgroundScope)
        advanceUntilIdle()
        vm.onIntent(TaskCreateIntent.TitleChanged("Dated task"))
        vm.onIntent(TaskCreateIntent.SetDueDate(date))
        advanceUntilIdle()
        vm.onIntent(TaskCreateIntent.SaveClicked)
        delay(100.milliseconds)
        assertEquals(date, fakeTaskRepo.tasks.value.values.first().dueDate)
    }

    @Test
    fun `DueDateCleared removes dueDate from created task`() = runTest {
        val date = LocalDate(2026, Month.OCTOBER, 5)
        val vm = createVm(backgroundScope)
        advanceUntilIdle()
        vm.onIntent(TaskCreateIntent.TitleChanged("Date test"))
        vm.onIntent(TaskCreateIntent.SetDueDate(date))
        advanceUntilIdle()
        vm.onIntent(TaskCreateIntent.DueDateCleared)
        advanceUntilIdle()
        vm.onIntent(TaskCreateIntent.SaveClicked)
        delay(100.milliseconds)
        assertNull(fakeTaskRepo.tasks.value.values.first().dueDate)
    }

    // ─── Race guard (compareAndSet) ─────────────────────────────────────────

    @Test
    fun `parallel SaveClicked calls are guarded by compareAndSet — only one save`() = runTest {
        val vm = createVm(backgroundScope)
        advanceUntilIdle()
        vm.onIntent(TaskCreateIntent.TitleChanged("Raced task"))

        // Fire two saves synchronously — second is blocked by compareAndSet guard
        vm.onIntent(TaskCreateIntent.SaveClicked)
        vm.onIntent(TaskCreateIntent.SaveClicked)

        delay(200.milliseconds)
        // Exactly one task created — the guard prevented double-save
        assertEquals(1, fakeTaskRepo.tasks.value.size)
        assertEquals("Raced task", fakeTaskRepo.tasks.value.values.first().title)
    }
}
