
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.feature.tasks

import com.singularity.todo.test.fakes.FakeClock
import com.singularity.todo.test.fakes.TEST_TZ
import co.touchlab.kermit.Logger
import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.draft.FakeDraftStore
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.text.FieldOwnedText
import com.singularity.todo.feature.tasks.domain.model.TaskPriority
import com.singularity.todo.feature.tasks.domain.usecase.CreateTaskFromDraftUseCase
import com.singularity.todo.feature.tasks.presentation.state.TaskCreateIntent
import com.singularity.todo.feature.tasks.presentation.state.TaskDraft
import com.singularity.todo.feature.tasks.presentation.viewmodel.TaskCreateDeps
import com.singularity.todo.feature.tasks.presentation.viewmodel.TaskCreateViewModel
import com.singularity.todo.test.fakes.FakeAttachmentRepository
import com.singularity.todo.test.fakes.FakeAuthRepository
import com.singularity.todo.test.fakes.FakeChecklistRepository
import com.singularity.todo.test.fakes.FakeProfileAwareCurrentUser
import com.singularity.todo.test.fakes.FakeTaskRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.Month
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

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
@Tag("fast")
class TaskCreateViewModelTest {

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
    private val fakeCurrentUser = FakeProfileAwareCurrentUser(
        FakeAuthRepository(
            initialSession = com.singularity.todo.core.auth.Session.Anonymous(testUserId),
        ),
    )
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
            logger = Logger.withTag("TaskCreateTest"),
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

    // ─── Draft restore (seed-if-empty) ────────────────────────────────────────

    @Test
    fun `draft restored from store on init subsequent save uses restored title`() = runTest {
        // Pre-seed a draft
        fakeDraftStore.save(
            draftKey,
            TaskDraft(title = FieldOwnedText.empty().seeded("Restored task")),
            TaskDraft.serializer(),
        )
        val vm = createVm(backgroundScope)
        advanceTimeBy(1_000)
        runCurrent()
        // Draft was restored (seed-if-empty pattern: restore only if current is initial).
        // Verify by saving without typing — should create task with restored title.
        vm.onIntent(TaskCreateIntent.SaveClicked)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(1, fakeTaskRepo.tasks.value.size)
        assertEquals("Restored task", fakeTaskRepo.tasks.value.values.first().title)
    }

    // ─── SaveClicked — blank title ───────────────────────────────────────────

    @Test
    fun `SaveClicked with blank title does not create task`() = runTest {
        val vm = createVm(backgroundScope)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(0, fakeTaskRepo.tasks.value.size)
        vm.onIntent(TaskCreateIntent.SaveClicked)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(0, fakeTaskRepo.tasks.value.size)
    }

    // ─── SaveClicked — valid title ───────────────────────────────────────────

    @Test
    fun `SaveClicked with valid title creates task in repository`() = runTest {
        val vm = createVm(backgroundScope)
        advanceTimeBy(1_000)
        runCurrent()
        vm.onIntent(TaskCreateIntent.TitleChanged("New task"))
        advanceTimeBy(1_000)
        runCurrent()
        vm.onIntent(TaskCreateIntent.SaveClicked)
        // Wait for save coroutine to complete
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(1, fakeTaskRepo.tasks.value.size)
        assertEquals("New task", fakeTaskRepo.tasks.value.values.first().title)
    }

    @Test
    fun `SaveClicked with description creates task with description`() = runTest {
        val vm = createVm(backgroundScope)
        advanceTimeBy(1_000)
        runCurrent()
        vm.onIntent(TaskCreateIntent.TitleChanged("Task with desc"))
        vm.onIntent(TaskCreateIntent.DescriptionChanged("Some description"))
        advanceTimeBy(1_000)
        runCurrent()
        vm.onIntent(TaskCreateIntent.SaveClicked)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(1, fakeTaskRepo.tasks.value.size)
        assertEquals("Some description", fakeTaskRepo.tasks.value.values.first().description)
    }

    @Test
    fun `SaveClicked with priority creates task with priority`() = runTest {
        val vm = createVm(backgroundScope)
        advanceTimeBy(1_000)
        runCurrent()
        vm.onIntent(TaskCreateIntent.TitleChanged("Important task"))
        vm.onIntent(TaskCreateIntent.SetPriority(TaskPriority.High))
        advanceTimeBy(1_000)
        runCurrent()
        vm.onIntent(TaskCreateIntent.SaveClicked)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(TaskPriority.High, fakeTaskRepo.tasks.value.values.first().priority)
    }

    // ─── SaveClicked — clears draft ─────────────────────────────────────────

    @Test
    fun `SaveClicked clears draft after successful save`() = runTest {
        val vm = createVm(backgroundScope)
        advanceTimeBy(1_000)
        runCurrent()
        vm.onIntent(TaskCreateIntent.TitleChanged("To be cleared"))
        advanceTimeBy(1_000)
        runCurrent()
        vm.onIntent(TaskCreateIntent.SaveClicked)
        advanceTimeBy(1_000)
        runCurrent()
        // Draft should be cleared from store
        assertNull(fakeDraftStore.load(draftKey, TaskDraft.serializer()))
    }

    // ─── DueDate ────────────────────────────────────────────────────────────

    @Test
    fun `SetDueDate with valid date creates task with that date`() = runTest {
        val date = LocalDate(2026, Month.OCTOBER, 5)
        val vm = createVm(backgroundScope)
        advanceTimeBy(1_000)
        runCurrent()
        vm.onIntent(TaskCreateIntent.TitleChanged("Dated task"))
        vm.onIntent(TaskCreateIntent.SetDueDate(date))
        advanceTimeBy(1_000)
        runCurrent()
        vm.onIntent(TaskCreateIntent.SaveClicked)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(date, fakeTaskRepo.tasks.value.values.first().dueDate)
    }

    @Test
    fun `DueDateCleared removes dueDate from created task`() = runTest {
        val date = LocalDate(2026, Month.OCTOBER, 5)
        val vm = createVm(backgroundScope)
        advanceTimeBy(1_000)
        runCurrent()
        vm.onIntent(TaskCreateIntent.TitleChanged("Date test"))
        vm.onIntent(TaskCreateIntent.SetDueDate(date))
        advanceTimeBy(1_000)
        runCurrent()
        vm.onIntent(TaskCreateIntent.DueDateCleared)
        advanceTimeBy(1_000)
        runCurrent()
        vm.onIntent(TaskCreateIntent.SaveClicked)
        advanceTimeBy(1_000)
        runCurrent()
        assertNull(fakeTaskRepo.tasks.value.values.first().dueDate)
    }

    // ─── Race guard (compareAndSet) ─────────────────────────────────────────

    @Test
    fun `parallel SaveClicked calls are guarded by compareAndSet — only one save`() = runTest {
        val vm = createVm(backgroundScope)
        advanceTimeBy(1_000)
        runCurrent()
        vm.onIntent(TaskCreateIntent.TitleChanged("Raced task"))

        // Fire two saves synchronously — second is blocked by compareAndSet guard
        vm.onIntent(TaskCreateIntent.SaveClicked)
        vm.onIntent(TaskCreateIntent.SaveClicked)

        advanceTimeBy(1_000)
        runCurrent()
        // Exactly one task created — the guard prevented double-save
        assertEquals(1, fakeTaskRepo.tasks.value.size)
        assertEquals("Raced task", fakeTaskRepo.tasks.value.values.first().title)
    }

    // ─── Dirty flag / save-enable contract ──────────────────────────────────
    //
    // The screen enables Save from `state.isSaveEnabled`, which is recomputed on
    // every edit as `validate(draft) == null && !isSaving`. That is the exact
    // contract the E2E suite depends on: a field whose `onValueChange` never
    // fires leaves the draft untouched, so `isDirty` stays false and Save stays
    // disabled no matter what the user appears to have typed. Asserting it here
    // means a Compose upgrade cannot quietly move that condition.
    //
    // The header of this file used to claim these were untestable because the VM
    // used `stateIn(…, WhileSubscribed(5000), …)` and needed a live collector.
    // `DraftMviViewModel` exposes a plain StateFlow, so `state.value` is enough.

    @Test
    fun `TitleChanged marks the draft dirty and enables save`() = runTest {
        val vm = createVm(backgroundScope)
        advanceTimeBy(1_000)
        runCurrent()

        assertFalse(vm.state.value.isDirty, "a freshly restored draft is not dirty")
        assertFalse(vm.state.value.isSaveEnabled, "blank title must not enable save")

        vm.onIntent(TaskCreateIntent.TitleChanged("Buy milk"))
        runCurrent()

        assertTrue(vm.state.value.isDirty, "an edit must mark the draft dirty")
        assertTrue(vm.state.value.isSaveEnabled, "a non-blank title must enable save")
    }

    @Test
    fun `clearing the title disables save again`() = runTest {
        val vm = createVm(backgroundScope)
        advanceTimeBy(1_000)
        runCurrent()

        vm.onIntent(TaskCreateIntent.TitleChanged("Buy milk"))
        runCurrent()
        assertTrue(vm.state.value.isSaveEnabled)

        vm.onIntent(TaskCreateIntent.TitleChanged("   "))
        runCurrent()

        assertFalse(
            vm.state.value.isSaveEnabled,
            "whitespace is blank: save must disable without the draft being reverted to pristine",
        )
        assertTrue(vm.state.value.isDirty, "still dirty — the user did edit, then cleared it")
    }

    @Test
    fun `a blank title rejects save with a validation error and creates nothing`() = runTest {
        val vm = createVm(backgroundScope)
        advanceTimeBy(1_000)
        runCurrent()

        // Type, then clear: the draft is dirty but invalid.
        vm.onIntent(TaskCreateIntent.TitleChanged("Buy milk"))
        vm.onIntent(TaskCreateIntent.TitleChanged(""))
        runCurrent()
        vm.onIntent(TaskCreateIntent.SaveClicked)
        advanceTimeBy(1_000)
        runCurrent()

        assertEquals(0, fakeTaskRepo.tasks.value.size, "an invalid draft must not reach the repository")
        assertEquals("Title is required", vm.state.value.error)
        assertFalse(vm.state.value.isSaving, "a rejected save must clear isSaving, not wedge the button")
    }
}
