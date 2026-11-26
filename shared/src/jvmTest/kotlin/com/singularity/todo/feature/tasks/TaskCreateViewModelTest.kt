
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.feature.tasks

import co.touchlab.kermit.Logger
import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.draft.DraftStore
import com.singularity.todo.core.draft.FakeDraftStore
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.text.FieldOwnedText
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.tags.TagId
import com.singularity.todo.feature.tasks.domain.model.RecurrenceSpec
import com.singularity.todo.feature.tasks.domain.model.TaskPriority
import com.singularity.todo.feature.tasks.domain.state.TaskCreateIntent
import com.singularity.todo.feature.tasks.domain.state.TaskCreateUiEvent
import com.singularity.todo.feature.tasks.domain.state.TaskDraft
import com.singularity.todo.feature.tasks.domain.usecase.CreateTaskFromDraftUseCase
import com.singularity.todo.feature.tasks.domain.usecase.MaterializeRecurringSeriesUseCase
import com.singularity.todo.feature.tasks.presentation.viewmodel.TaskCreateDeps
import com.singularity.todo.feature.tasks.presentation.viewmodel.TaskCreateViewModel
import com.singularity.todo.feature.tasks.presentation.viewmodel.taskDraftStorageKey
import com.singularity.todo.test.fakes.FakeAttachmentRepository
import com.singularity.todo.test.fakes.FakeAuthRepository
import com.singularity.todo.test.fakes.FakeChecklistRepository
import com.singularity.todo.test.fakes.FakeClock
import com.singularity.todo.test.fakes.FakeProfileAwareCurrentUser
import com.singularity.todo.test.fakes.FakeTaskRepository
import com.singularity.todo.test.fakes.FakeUnitOfWork
import com.singularity.todo.test.fakes.TEST_TZ
import io.qameta.allure.Epic
import io.qameta.allure.Feature
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.Month
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
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
@Epic("Tasks")
@Feature("Tasks")
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
    private val fakeCurrentUser =
        FakeProfileAwareCurrentUser(
            FakeAuthRepository(
                initialSession =
                    com.singularity.todo.core.auth.Session
                        .Anonymous(testUserId),
            ),
        )
    private val fakeChecklistRepository = FakeChecklistRepository()

    // The same user as the task's, so `observeAll` sees what the save wrote.
    private val fakeAttachmentRepository = FakeAttachmentRepository(currentUser = fakeCurrentUser)

    private fun createVm(
        scope: CoroutineScope,
        initialDueDate: LocalDate? = null,
        initialProjectId: ProjectId? = null,
        initialTagIds: Set<TagId> = emptySet(),
        draftStore: DraftStore = fakeDraftStore,
    ): TaskCreateViewModel {
        val deps =
            TaskCreateDeps(
                createFromDraft =
                    CreateTaskFromDraftUseCase(
                        fakeTaskRepo,
                        FakeClock(taskNow),
                        TEST_TZ,
                        fakeCurrentUser,
                        fakeChecklistRepository,
                        fakeAttachmentRepository,
                        FakeUnitOfWork(),
                        MaterializeRecurringSeriesUseCase(fakeTaskRepo, FakeUnitOfWork(), FakeClock(taskNow)),
                    ),
                logger = Logger.withTag("TaskCreateTest"),
                draftStore = draftStore,
            )
        return TaskCreateViewModel(
            deps = deps,
            initialDueDate = initialDueDate,
            draftKey = null,
            initialProjectId = initialProjectId,
            initialTagIds = initialTagIds,
            scope = AutoCloseableCoroutineScope(scope.coroutineContext),
        )
    }

    // FakeDraftStore stores bare keys (no user prefix), matching TaskCreateViewModel's bare key usage.
    private val draftKey get() = TaskCreateDeps.DRAFT_KEY

    // ─── Draft restore (seed-if-empty) ────────────────────────────────────────

    @Test
    fun `draft restored from store on init subsequent save uses restored title`() =
        runTest {
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
            assertEquals(
                "Restored task",
                fakeTaskRepo.tasks.value.values
                    .first()
                    .title,
            )
        }

    // ─── SaveClicked — blank title ───────────────────────────────────────────

    @Test
    fun `SaveClicked with blank title does not create task`() =
        runTest {
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
    fun `SaveClicked with valid title creates task in repository`() =
        runTest {
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
            assertEquals(
                "New task",
                fakeTaskRepo.tasks.value.values
                    .first()
                    .title,
            )
        }

    @Test
    fun `SaveClicked with description creates task with description`() =
        runTest {
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
            assertEquals(
                "Some description",
                fakeTaskRepo.tasks.value.values
                    .first()
                    .description,
            )
        }

    @Test
    fun `SaveClicked with priority creates task with priority`() =
        runTest {
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
            assertEquals(
                TaskPriority.High,
                fakeTaskRepo.tasks.value.values
                    .first()
                    .priority,
            )
        }

    /**
     * The pin row is drawn on both editors, so the value it sets has to reach the
     * task. It did not: the draft carried the flag and the write dropped it, which
     * made the row a control that reported success and changed nothing.
     */
    @Test
    fun `SaveClicked with the pin set creates a pinned task`() =
        runTest {
            val vm = createVm(backgroundScope)
            advanceTimeBy(1_000)
            runCurrent()
            vm.onIntent(TaskCreateIntent.TitleChanged("Pinned task"))
            vm.onIntent(TaskCreateIntent.PinToggled)
            advanceTimeBy(1_000)
            runCurrent()
            vm.onIntent(TaskCreateIntent.SaveClicked)
            advanceTimeBy(1_000)
            runCurrent()
            assertTrue(
                fakeTaskRepo.tasks.value.values
                    .first()
                    .isPinned,
                "the draft carried the pin and the created task did not",
            )
        }

    /** The estimate row is the other half of the same defect: the detail had it, the
     * write from a draft dropped it, and the create editor had no row at all. */
    @Test
    fun `SaveClicked with an estimate creates a task with that estimate`() =
        runTest {
            val vm = createVm(backgroundScope)
            advanceTimeBy(1_000)
            runCurrent()
            vm.onIntent(TaskCreateIntent.TitleChanged("Estimated task"))
            vm.onIntent(TaskCreateIntent.SetEstimate(45))
            advanceTimeBy(1_000)
            runCurrent()
            vm.onIntent(TaskCreateIntent.SaveClicked)
            advanceTimeBy(1_000)
            runCurrent()
            assertEquals(
                45,
                fakeTaskRepo.tasks.value.values
                    .first()
                    .estimateMinutes,
            )
        }

    // ─── SaveClicked — clears draft ─────────────────────────────────────────
    @Test
    fun `SaveClicked clears draft after successful save`() =
        runTest {
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
    fun `SetDueDate with valid date creates task with that date`() =
        runTest {
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
            assertEquals(
                date,
                fakeTaskRepo.tasks.value.values
                    .first()
                    .dueDate,
            )
        }

    @Test
    fun `DueDateCleared removes dueDate from created task`() =
        runTest {
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
            assertNull(
                fakeTaskRepo.tasks.value.values
                    .first()
                    .dueDate,
            )
        }

    // ─── Several dates ──────────────────────────────────────────────────────
    //
    // A draft with several dates becomes a series: one task per date, all
    // sharing one series id. A draft with one date stays an ordinary task.

    @Test
    fun `SetDueDates with three dates creates three tasks that share one series id`() =
        runTest {
            val dates =
                setOf(
                    LocalDate(2026, Month.OCTOBER, 5),
                    LocalDate(2026, Month.OCTOBER, 12),
                    LocalDate(2026, Month.OCTOBER, 19),
                )
            val vm = createVm(backgroundScope)
            advanceTimeBy(1_000)
            runCurrent()
            vm.onIntent(TaskCreateIntent.TitleChanged("Pay the rent"))
            vm.onIntent(TaskCreateIntent.SetDueDates(dates))
            advanceTimeBy(1_000)
            runCurrent()
            vm.onIntent(TaskCreateIntent.SaveClicked)
            advanceTimeBy(1_000)
            runCurrent()

            val created = fakeTaskRepo.tasks.value.values
            assertEquals(3, created.size, "one task per date")
            val seriesIds = created.map { it.seriesId }.toSet()
            assertEquals(1, seriesIds.size, "every member shares one series id")
            assertTrue(seriesIds.first() != null, "a series of three is a series")
            val createdDates = created.mapNotNull { it.dueDate }.sorted()
            val expectedDates = dates.toList().sorted()
            assertEquals(expectedDates, createdDates, "each member keeps its own date")
        }

    @Test
    fun `SetDueDates with one date creates an ordinary task`() =
        runTest {
            val vm = createVm(backgroundScope)
            advanceTimeBy(1_000)
            runCurrent()
            vm.onIntent(TaskCreateIntent.TitleChanged("Single"))
            vm.onIntent(TaskCreateIntent.SetDueDates(setOf(LocalDate(2026, Month.OCTOBER, 5))))
            advanceTimeBy(1_000)
            runCurrent()
            vm.onIntent(TaskCreateIntent.SaveClicked)
            advanceTimeBy(1_000)
            runCurrent()

            val created = fakeTaskRepo.tasks.value.values
            assertEquals(1, created.size)
            assertNull(created.first().seriesId, "one date is not a series")
        }

    @Test
    fun `SetDueDates with no dates clears the date`() =
        runTest {
            val vm = createVm(backgroundScope)
            advanceTimeBy(1_000)
            runCurrent()
            vm.onIntent(TaskCreateIntent.TitleChanged("Undated"))
            vm.onIntent(TaskCreateIntent.SetDueDates(setOf(LocalDate(2026, Month.OCTOBER, 5))))
            advanceTimeBy(1_000)
            runCurrent()
            vm.onIntent(TaskCreateIntent.SetDueDates(emptySet()))
            advanceTimeBy(1_000)
            runCurrent()
            vm.onIntent(TaskCreateIntent.SaveClicked)
            advanceTimeBy(1_000)
            runCurrent()

            assertNull(
                fakeTaskRepo.tasks.value.values
                    .first()
                    .dueDate,
            )
        }

    @Test
    fun `a second date drops the repeat rule and says so`() =
        runTest {
            val vm = createVm(backgroundScope)
            advanceTimeBy(1_000)
            runCurrent()
            vm.onIntent(TaskCreateIntent.TitleChanged("Recurring"))
            vm.onIntent(
                TaskCreateIntent.SetRecurrence(
                    RecurrenceSpec.Interval(
                        base = RecurrenceSpec.RecurrenceBase.FROM_DUE,
                        amount = 1,
                        unit = DateTimeUnit.WEEK,
                    ),
                ),
            )
            advanceTimeBy(1_000)
            runCurrent()
            vm.onIntent(
                TaskCreateIntent.SetDueDates(
                    setOf(LocalDate(2026, Month.OCTOBER, 5), LocalDate(2026, Month.OCTOBER, 12)),
                ),
            )
            runCurrent()

            assertEquals(
                "Повтор отменён: выбрано несколько дат",
                vm.state.value.error,
                "the user is told that the rule was dropped",
            )
            vm.onIntent(TaskCreateIntent.SaveClicked)
            advanceTimeBy(1_000)
            runCurrent()

            val created = fakeTaskRepo.tasks.value.values
            assertEquals(2, created.size)
            assertTrue(created.all { it.recurrence == null }, "a series never holds a rule")
        }

    // ─── Race guard (compareAndSet) ─────────────────────────────────────────
    @Test
    fun `parallel SaveClicked calls are guarded by compareAndSet — only one save`() =
        runTest {
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
            assertEquals(
                "Raced task",
                fakeTaskRepo.tasks.value.values
                    .first()
                    .title,
            )
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
    fun `TitleChanged marks the draft dirty and enables save`() =
        runTest {
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
    fun `clearing the title disables save again`() =
        runTest {
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
    fun `Discard clears the autosaved draft`() =
        runTest {
            val vm = createVm(backgroundScope)
            advanceTimeBy(1_000)
            runCurrent()
            vm.onIntent(TaskCreateIntent.TitleChanged("To be discarded"))
            advanceTimeBy(1_000)
            runCurrent()
            // make sure it was autosaved
            assertNotNull(fakeDraftStore.load(draftKey, TaskDraft.serializer()))
            vm.onIntent(TaskCreateIntent.Discard)
            runCurrent()
            assertNull(fakeDraftStore.load(draftKey, TaskDraft.serializer()))
        }

    @Test
    fun `a blank title rejects save with a validation error and creates nothing`() =
        runTest {
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

    // ─── Seeds ──────────────────────────────────────────────────────────────
    //
    // The capture flows seed the screen with a date, a project and tags (a FAB
    // in a project, a task in a section). The seeds travel in the draft and the
    // draft is what gets written, so a seed that is dropped here is dropped
    // from the created task too.

    @Test
    fun `the seeded date, project and tags reach the created task`() =
        runTest {
            val date = LocalDate(2026, Month.OCTOBER, 5)
            val projectId = ProjectId("project-1")
            val tagId = TagId("tag-1")
            val vm =
                createVm(
                    backgroundScope,
                    initialDueDate = date,
                    initialProjectId = projectId,
                    initialTagIds = setOf(tagId),
                )
            advanceTimeBy(1_000)
            runCurrent()
            vm.onIntent(TaskCreateIntent.TitleChanged("Seeded task"))
            advanceTimeBy(1_000)
            runCurrent()
            vm.onIntent(TaskCreateIntent.SaveClicked)
            advanceTimeBy(1_000)
            runCurrent()

            val created =
                fakeTaskRepo.tasks.value.values
                    .first()
            assertEquals(date, created.dueDate, "the seeded date must survive the save")
            assertEquals(projectId, created.projectId, "the seeded project must survive the save")
            assertEquals(listOf(tagId), created.tags, "the seeded tags must survive the save")
        }

    // ─── Checklist and attachments ──────────────────────────────────────────
    //
    // Both are written after the task row, by the same save. A save that drops
    // them leaves a task the user believes has a checklist and a link.

    @Test
    fun `the checklist item survives the save`() =
        runTest {
            val vm = createVm(backgroundScope)
            advanceTimeBy(1_000)
            runCurrent()
            vm.onIntent(TaskCreateIntent.TitleChanged("Task with a checklist"))
            vm.onIntent(TaskCreateIntent.AddChecklistItem("Buy milk"))
            advanceTimeBy(1_000)
            runCurrent()
            vm.onIntent(TaskCreateIntent.SaveClicked)
            advanceTimeBy(1_000)
            runCurrent()

            val created =
                fakeTaskRepo.tasks.value.values
                    .first()
            val items = fakeChecklistRepository.items.value.values
            assertEquals(1, items.size, "the checklist item must be written")
            assertEquals("Buy milk", items.first().title)
            assertEquals(created.id.value, items.first().taskId, "the item must belong to the created task")
        }

    @Test
    fun `the url attachment survives the save`() =
        runTest {
            val vm = createVm(backgroundScope)
            advanceTimeBy(1_000)
            runCurrent()
            vm.onIntent(TaskCreateIntent.TitleChanged("Task with a link"))
            vm.onIntent(TaskCreateIntent.AddAttachmentUrl("https://example.com/spec", "Spec"))
            advanceTimeBy(1_000)
            runCurrent()
            vm.onIntent(TaskCreateIntent.SaveClicked)
            advanceTimeBy(1_000)
            runCurrent()

            val created =
                fakeTaskRepo.tasks.value.values
                    .first()
            val attachments = fakeAttachmentRepository.observeAll().first()
            assertEquals(1, attachments.size, "the attachment must be written")
            assertEquals("https://example.com/spec", attachments.first().url)
            assertEquals(created.id, attachments.first().taskId, "the attachment must belong to the created task")
        }

    // ─── The create editor's rows of the draft behavior matrix ───────────────
    //
    // The base class owns the contract; the create editor overrides three
    // extension points — `validate`, `onSaved` and `onAutosaveError` — and each
    // override has to keep its promise.

    /**
     * The screen navigates away on the event, not on the state: the task is
     * created, but without the event the editor stays open over a task that
     * already exists.
     */
    @Test
    fun `a successful save emits the Saved event`() =
        runTest {
            val vm = createVm(backgroundScope)
            advanceTimeBy(1_000)
            runCurrent()

            val events = mutableListOf<TaskCreateUiEvent>()
            val job = launch { vm.events.collect { events += it } }
            runCurrent()

            vm.onIntent(TaskCreateIntent.TitleChanged("Emit me"))
            runCurrent()
            vm.onIntent(TaskCreateIntent.SaveClicked)
            advanceTimeBy(1_000)
            runCurrent()

            assertEquals(
                1,
                events.filterIsInstance<TaskCreateUiEvent.Saved>().size,
                "the screen is told that the task exists",
            )
            job.cancel()
        }

    /**
     * A draft is stored under the scope it was written in — a project, a set of
     * tags. A draft written in one project's agenda must not open in another
     * project's editor, or the task lands in a project the person never chose.
     */
    @Test
    fun `a draft is restored only into the scope it was saved in`() =
        runTest {
            fakeDraftStore.save(
                taskDraftStorageKey(null, ProjectId("project-1"), emptySet()),
                TaskDraft(title = FieldOwnedText.empty().seeded("Scoped draft")),
                TaskDraft.serializer(),
            )

            val vm = createVm(backgroundScope, initialProjectId = ProjectId("project-2"))
            advanceTimeBy(1_000)
            runCurrent()

            // The editor opened in project-2 starts empty, so the save is refused
            // for the missing title — the other project's draft did not leak in.
            vm.onIntent(TaskCreateIntent.SaveClicked)
            advanceTimeBy(1_000)
            runCurrent()

            assertEquals(0, fakeTaskRepo.tasks.value.size, "a draft from another scope must not leak into this one")
            assertEquals("Title is required", vm.state.value.error)
        }

    /**
     * The other half of the scoping guarantee: the draft saved for a scope is
     * restored when the editor opens *in that scope*. Without this half, the
     * test above would also pass if the store were never restored at all.
     */
    @Test
    fun `a draft is restored into the scope it was saved in`() =
        runTest {
            fakeDraftStore.save(
                taskDraftStorageKey(null, ProjectId("project-1"), emptySet()),
                TaskDraft(title = FieldOwnedText.empty().seeded("Scoped draft")),
                TaskDraft.serializer(),
            )

            val vm = createVm(backgroundScope, initialProjectId = ProjectId("project-1"))
            advanceTimeBy(1_000)
            runCurrent()

            assertEquals(
                "Scoped draft",
                vm.state.value.draft.title.text,
                "the draft saved for this scope is restored into it",
            )

            vm.onIntent(TaskCreateIntent.SaveClicked)
            advanceTimeBy(1_000)
            runCurrent()

            assertEquals(1, fakeTaskRepo.tasks.value.size)
            assertEquals(
                "Scoped draft",
                fakeTaskRepo.tasks.value.values
                    .first()
                    .title,
                "the task is created from the restored draft",
            )
        }

    /**
     * The autosave is a safety net. A store that refuses the write must not take
     * the editor with it: the text stays, and the task can still be created from
     * the editor's own copy.
     */
    @Test
    fun `a failed autosave leaves the editor usable`() =
        runTest {
            val vm = createVm(backgroundScope, draftStore = FailingDraftStore())
            advanceTimeBy(1_000)
            runCurrent()

            vm.onIntent(TaskCreateIntent.TitleChanged("Survives"))
            advanceTimeBy(1_000)
            runCurrent()

            assertEquals("Survives", vm.state.value.draft.title.text, "the text is the editor's, not the store's")
            assertTrue(vm.state.value.isDirty, "a failed autosave is not a checkpoint")

            vm.onIntent(TaskCreateIntent.SaveClicked)
            advanceTimeBy(1_000)
            runCurrent()

            assertEquals(1, fakeTaskRepo.tasks.value.size, "the task is created from the editor's copy")
        }
}

/**
 * A store whose writes fail.
 *
 * The editor has to survive it: the autosave is a safety net, and a broken
 * store must not take the editor down with it.
 */
private class FailingDraftStore : DraftStore {
    override suspend fun <T> load(
        key: String,
        deserializer: DeserializationStrategy<T>,
    ): T? = null

    override suspend fun <T> save(
        key: String,
        value: T,
        serializer: SerializationStrategy<T>,
    ): Unit = throw IllegalStateException("the store is down")

    override suspend fun clear(key: String) = Unit

    override suspend fun clearPrefix(prefix: String) = Unit
}
