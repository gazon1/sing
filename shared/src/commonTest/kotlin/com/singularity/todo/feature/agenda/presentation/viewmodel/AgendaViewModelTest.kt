@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.feature.agenda.presentation.viewmodel

import co.touchlab.kermit.Logger
import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.draft.FakeDraftStore
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.schedule.GreetingSettingsRepository
import com.singularity.todo.feature.agenda.domain.logic.AgendaPresets
import com.singularity.todo.feature.agenda.domain.model.AgendaIntent
import com.singularity.todo.feature.agenda.domain.model.AgendaUiEvent
import com.singularity.todo.feature.agenda.presentation.state.AgendaUiState
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.reminders.ReminderId
import com.singularity.todo.feature.reminders.ReminderScheduler
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskDraftKeys
import com.singularity.todo.feature.tasks.domain.model.TaskFilter
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.state.DueDateOption
import com.singularity.todo.feature.tasks.domain.state.TaskDraft
import com.singularity.todo.feature.tasks.domain.usecase.MergeTaskSeriesUseCase
import com.singularity.todo.feature.tasks.domain.usecase.TaskReopenUseCase
import com.singularity.todo.test.fakes.FakeClock
import com.singularity.todo.test.fakes.FakeProfileAwareCurrentUser
import com.singularity.todo.test.fakes.FakeProjectsRepository
import com.singularity.todo.test.fakes.FakeSettingsRepository
import com.singularity.todo.test.fakes.FakeTaskMutationsUseCase
import com.singularity.todo.test.fakes.FakeTaskRepository
import com.singularity.todo.test.fakes.RecordingCrashReportingPort
import com.singularity.todo.test.fakes.TEST_TZ
import com.singularity.todo.test.helpers.VmScope
import com.singularity.todo.test.helpers.awaitState
import com.singularity.todo.test.helpers.testVm
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Regression tests for [AgendaViewModel]'s Loading → Loaded transition.
 *
 * The VM used to collect with `.collect { updateState { it } }`. The reducer's `it`
 * shadows the collected element, so the transform was the identity function: data
 * arrived from the repository and was discarded, and the screen rendered its
 * loading indicator forever. These tests assert the state actually advances.
 *
 * ## Why `runCurrent` and not `advanceUntilIdle`
 * `todayFlow` is an infinite `while (true) { emit; delay(untilMidnight) }` loop, so
 * advancing virtual time never lets the test scheduler drain. `runCurrent` executes
 * the coroutines that are already runnable — including the flow's first, immediate
 * emission — without moving the clock, which is all this transition needs.
 *
 * ## Why the harness is told what kind of ViewModel this is
 * The same infinite loop means the VM's init coroutine never completes, so its work
 * cannot be the test's work: runTest would await it and time out. The test does not
 * build that scope itself — it says that the ViewModel under test is one whose
 * collector never ends, and the harness builds the scope that kind needs.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Tag("fast")
class AgendaViewModelTest {
    /**
     * A fixed instant for the ViewModels built by [createVm].
     *
     * It used to be `Clock.System`, so anything the agenda resolved against
     * "today" was a function of the day the suite ran. The companion
     * date-sensitivity test below pins its own date explicitly, because it is
     * asserting about a specific one; this is only here so the rest of the file
     * is not reading the wall clock.
     */
    private val now: Instant = Instant.parse("2026-09-16T09:00:00Z")

    /**
     * The tasks the agenda reads.
     *
     * The plain fake. The agenda's read is the one that can fail and leave the
     * screen on an error — and the retry that has to get the agenda back is
     * only reachable through that failure — so the failing variant is a fake
     * of its own, built by the tests that need it.
     */
    private val fakeRepo = FakeTaskRepository()
    private val fakeSettings = FakeSettingsRepository()
    private val fakeCurrentUser: ProfileAwareCurrentUser = FakeProfileAwareCurrentUser()
    private val fakeTaskMutations = FakeTaskMutationsUseCase(fakeRepo)
    private val fakeReminderScheduler =
        object : ReminderScheduler {
            override val isSupported: Boolean = true

            override suspend fun schedule(reminder: com.singularity.todo.feature.reminders.Reminder) {}

            override suspend fun cancel(
                id: ReminderId,
                userId: UserId,
            ) {}

            override suspend fun cancelByTask(
                taskId: com.singularity.todo.feature.tasks.domain.model.TaskId,
                userId: UserId,
            ) {}
        }

    private fun task(
        id: String,
        title: String,
    ) = Task(
        id = TaskId(id),
        title = title,
        createdAt = Clock.System.now(),
        updatedAt = Clock.System.now(),
        userId = UserId("test-user"),
    )

    private val fakeProjectsRepo = FakeProjectsRepository()
    private val completion = TaskReopenUseCase(fakeRepo, fakeProjectsRepo, FakeClock(now))

    /**
     * The dependencies every ViewModel in this file is built with.
     *
     * The list of them grew with the feature — the merge of two series is one of
     * them now — and six copies of the same fourteen lines is how a test file
     * grows past the size a class is allowed to be. One builder, four knobs:
     * what the clock says, which drafts are kept, which reminders are
     * cancelled, and which greeting the hour produces.
     */
    private fun agendaDeps(
        clock: kotlin.time.Clock = FakeClock(now),
        draftStore: FakeDraftStore = FakeDraftStore(),
        reminderScheduler: ReminderScheduler = fakeReminderScheduler,
        greetingSettings: GreetingSettingsRepository = fakeSettings.greeting,
        taskRepo: FakeTaskRepository = fakeRepo,
    ) = AgendaDeps(
        taskRepo = taskRepo,
        clock = clock,
        timeZone = TEST_TZ,
        logger = Logger,
        draftStore = draftStore,
        reminderScheduler = reminderScheduler,
        currentUser = fakeCurrentUser,
        taskMutations = fakeTaskMutations,
        completion = completion,
        mergeSeries = MergeTaskSeriesUseCase(fakeRepo),
        workScheduleSettings = fakeSettings.workSchedule,
        greetingSettings = greetingSettings,
        projectsRepository = fakeProjectsRepo,
    )

    /**
     * The ViewModel under test, in the scope its kind of work needs.
     *
     * The agenda collects a clock flow that never ends, so the ViewModel's work
     * cannot be the test's work — the test would wait for a collector that never
     * finishes. The harness owns that choice: the test says *what kind of
     * ViewModel it is testing* — one whose collector never ends — and the harness
     * builds the scope that kind needs.
     */
    private fun TestScope.createVm(draftStore: FakeDraftStore = FakeDraftStore()): AgendaViewModel =
        createVmIn { scope ->
            AgendaViewModel(
                deps = agendaDeps(draftStore = draftStore),
                definition = AgendaPresets.Inbox,
                scope = scope,
            )
        }

    /**
     * A ViewModel of this kind, built by hand.
     *
     * Some tests need a ViewModel with a different clock, a different definition
     * or a different reminder scheduler — so they build it themselves. The scope
     * is still the harness's: the test names the kind of ViewModel, not the
     * scope it runs in.
     */
    private fun TestScope.createVmIn(factory: (AutoCloseableCoroutineScope) -> AgendaViewModel): AgendaViewModel =
        testVm(
            stateAccessor = { vm: AgendaViewModel -> vm.stateFlow },
            vmScope = VmScope.Background,
        ) { scope -> factory(scope) }.vm

    @Test
    fun `leaves Loading and renders the seeded tasks`() =
        runTest {
            fakeRepo.seed(task("t1", "Buy milk"), task("t2", "Ship release"))
            val vm = createVm()
            runCurrent()

            val state =
                assertIs<AgendaUiState.Loaded>(
                    vm.stateFlow.value,
                    "VM stayed on Loading — the collected state is being discarded",
                )
            val titles = state.sections.flatMap { it.tasks }.map { it.task.title }
            assertEquals(setOf("Buy milk", "Ship release"), titles.toSet())
        }

    @Test
    fun `emits Loaded even when the user has no tasks`() =
        runTest {
            val vm = createVm()
            runCurrent()

            // An empty list must still resolve to Loaded — otherwise the screen
            // would spin forever before it could show its empty state.
            val state = assertIs<AgendaUiState.Loaded>(vm.stateFlow.value)
            assertEquals(emptyList(), state.sections.flatMap { it.tasks })
        }

    /**
     * Regression: a task with `dueDate = null` that is seeded *before* VM construction
     * must appear in the Loaded state. Before the fix in [ProfileAwareCurrentUser],
     * `_scopedUserId` was seeded with `MutableStateFlow(UserId.anonymous)` (not yet
     * resolved from the auth context), so the repository query ran with the anonymous
     * user id and returned no results for the real seeded task.
     */
    @Test
    fun `undated task seeded before VM construction is visible in Loaded sections`() =
        runTest {
            val undated = task(id = "no-date-1", title = "Inbox me") // dueDate = null
            fakeRepo.seed(undated) // seed BEFORE creating the VM

            val vm = createVm()
            runCurrent()

            val state = assertIs<AgendaUiState.Loaded>(vm.stateFlow.value)
            assertTrue(
                state.sections.any { section ->
                    section.tasks.any { it.task.id == undated.id }
                },
                "Undated task seeded before VM construction must reach the agenda's No Date section",
            )
        }

    /**
     * A section's '+' button must prefill *today*, not the day the definition was
     * written.
     *
     * The presets used to carry hardcoded `LocalDate(2026, 10, 3)`-style constants,
     * which happened to match the test seed and so looked right in every test while
     * being wrong for every user on any other day: tap '+' in the Today section in
     * November and the new task comes due in October. A saved view is a template that
     * outlives the day it was written, so the bucket has to be resolved at tap time.
     *
     * The clock here is deliberately not "today" — a test that agrees with the
     * system clock proves nothing about a constant of the same value.
     */
    @Test
    fun `create in section prefills a due date relative to the injected clock`() =
        runTest {
            val pinned = LocalDate(2031, 7, 9) // a Wednesday, far from any seed date
            val draftStore = FakeDraftStore()
            val vm =
                createVmIn { scope ->
                    AgendaViewModel(
                        // Noon UTC with an explicit zone. The zone used to be the
                        // host's, which is why this had to be noon: midnight would land
                        // on a different date in half the world. With `timeZone` pinned to
                        // UTC the instant can be anything, and the "noon so the date cannot
                        // move" reasoning goes with it (#91).
                        deps =
                            agendaDeps(
                                clock = FakeClock(Instant.parse("2031-07-09T00:30:00Z")),
                                draftStore = draftStore,
                            ),
                        definition = AgendaPresets.Today,
                        scope = scope,
                    )
                }
            runCurrent()

            vm.onIntent(AgendaIntent.CreateInSection("today"))
            runCurrent()

            val draft = draftStore.load("section_create_draft_today", TaskDraft.serializer())
            assertNotNull(draft, "the section create must have stored a draft")
            assertEquals(
                DueDateOption.Custom(pinned, pinned.toString()),
                draft.dueDate,
                "'+' in the Today section must prefill the clock's today, not a compile-time constant",
            )
        }

    // ─────────────────────── A task on several days ─────────────────────────────
    //
    // The agenda writes the picked days into a draft and points the editor at it.
    // The days travel in the draft, not in a route parameter, so the editor is the
    // only place that creates the task.

    @Test
    fun `CreateOnDates writes the picked days into a draft`() =
        runTest {
            val draftStore = FakeDraftStore()
            val vm = createVm(draftStore = draftStore)
            runCurrent()

            val dates =
                setOf(
                    LocalDate(2026, 10, 5),
                    LocalDate(2026, 10, 12),
                    LocalDate(2026, 10, 19),
                )
            vm.onIntent(AgendaIntent.CreateOnDates(dates))
            runCurrent()

            val draft = draftStore.load(TaskDraftKeys.AGENDA_MULTI_DATE, TaskDraft.serializer())
            assertNotNull(draft, "the picked days must reach a draft")
            assertEquals(
                DueDateOption.Custom(LocalDate(2026, 10, 5), LocalDate(2026, 10, 5).toString()),
                draft.dueDate,
                "the earliest day is the primary one",
            )
            assertEquals(
                listOf(LocalDate(2026, 10, 12), LocalDate(2026, 10, 19)),
                draft.extraDueDates,
                "the other days ride along as extra dates",
            )
        }

    // ─────────────────────── Delete / undo ──────────────────────────────────────
    //
    // Every test below drives `onIntent` and asserts on what the *user* ends up
    // seeing or owning. The defect this suite exists for: `handleTaskDelete`
    // announced a delete it never issued, so the snackbar asserted something
    // false and Undo restored a task that was still live.

    /** `archivedAt` is the only honest witness that a soft delete really happened. */
    private fun archivedAt(id: String): kotlin.time.Instant? = fakeRepo.tasks.value[id]?.archivedAt

    private fun withVm(block: suspend TestScope.(AgendaViewModel) -> Unit) =
        runTest {
            block(createVm())
        }

    @Test
    fun `deleting a task soft-deletes it`() =
        withVm { vm ->
            fakeRepo.seed(task("t1", "Doomed"))
            runCurrent()

            vm.onIntent(AgendaIntent.TaskDeleteClicked(TaskId("t1")))
            runCurrent()

            assertNotNull(archivedAt("t1"), "the delete must reach the repository, not just the snackbar")
        }

    @Test
    fun `a failed delete offers no undo and reports the failure`() =
        withVm { vm ->
            fakeRepo.seed(task("t1", "Doomed"))
            fakeRepo.softDeleteOverride = Result.failure(IllegalStateException("disk full"))
            runCurrent()

            val events = mutableListOf<AgendaUiEvent>()
            val collector = launch { vm.events.collect { events += it } }
            try {
                vm.onIntent(AgendaIntent.TaskDeleteClicked(TaskId("t1")))
                runCurrent()

                assertNull(archivedAt("t1"), "a rejected delete must not archive anything")
                assertNull(
                    vm.pendingDelete.value,
                    "an offer to recover from a delete that never happened is a lie",
                )
                assertTrue(
                    events.any { it is AgendaUiEvent.ShowError },
                    "the user must be told the delete failed: $events",
                )
            } finally {
                collector.cancel()
            }
        }

    @Test
    fun `undo restores the deleted task and clears the offer`() =
        withVm { vm ->
            fakeRepo.seed(task("t1", "UndoMe"))
            runCurrent()

            vm.onIntent(AgendaIntent.TaskDeleteClicked(TaskId("t1")))
            runCurrent()
            assertNotNull(archivedAt("t1"))

            vm.onIntent(AgendaIntent.UndoDeleteTapped)
            runCurrent()

            assertNull(archivedAt("t1"), "undo must reverse the delete it offered to reverse")
            assertNull(vm.pendingDelete.value, "a successful reversal closes the offer")
        }

    /**
     * The assertion #78 says the old surface could not make.
     *
     * Clearing the offer *before* knowing the reversal worked removes the only
     * recovery path at the exact moment the user needs it — and since the snackbar
     * is dismissed with the offer, the failure becomes invisible. This is
     * `delete-safety-feedback` Phase 1 task 1.
     */
    @Test
    fun `a failed reversal keeps the offer so the user can try again`() =
        withVm { vm ->
            fakeRepo.seed(task("t1", "UndoMe"))
            runCurrent()
            vm.onIntent(AgendaIntent.TaskDeleteClicked(TaskId("t1")))
            runCurrent()

            fakeRepo.restoreOverride = Result.failure(IllegalStateException("still writing"))
            val events = mutableListOf<AgendaUiEvent>()
            val collector = launch { vm.events.collect { events += it } }
            try {
                vm.onIntent(AgendaIntent.UndoDeleteTapped)
                runCurrent()

                assertTrue(
                    events.any { it is AgendaUiEvent.ShowError },
                    "a failed reversal must be reported: $events",
                )
                assertNotNull(
                    vm.pendingDelete.value,
                    "a failed reversal must leave the offer addressable, not delete the last way back",
                )
                assertNotNull(archivedAt("t1"), "the task stays deleted until a reversal actually succeeds")
            } finally {
                collector.cancel()
            }
        }

    @Test
    fun `undo after the window expired restores nothing`() =
        withVm { vm ->
            fakeRepo.seed(task("t1", "Doomed"))
            runCurrent()
            vm.onIntent(AgendaIntent.TaskDeleteClicked(TaskId("t1")))
            runCurrent()

            advanceTimeBy(AgendaViewModel.UNDO_WINDOW_MS + 1)
            runCurrent()
            assertNull(vm.pendingDelete.value, "the window must close on its own")

            // A late tap must issue no write at all — not a restore against a live row.
            var restoreAttempts = 0
            fakeRepo.onRestore { restoreAttempts++ }
            vm.onIntent(AgendaIntent.UndoDeleteTapped)
            runCurrent()

            assertEquals(0, restoreAttempts, "a closed window must not issue a restore")
            assertNotNull(archivedAt("t1"), "a late undo must not resurrect a task the window closed on")
        }

    @Test
    fun `a new delete supersedes the previous offer`() =
        withVm { vm ->
            fakeRepo.seed(task("t1", "First"), task("t2", "Second"))
            runCurrent()

            vm.onIntent(AgendaIntent.TaskDeleteClicked(TaskId("t1")))
            runCurrent()
            assertEquals(
                TaskId("t1"),
                vm.pendingDelete.value
                    ?.taskIds
                    ?.singleOrNull(),
            )

            vm.onIntent(AgendaIntent.TaskDeleteClicked(TaskId("t2")))
            runCurrent()
            assertEquals(
                TaskId("t2"),
                vm.pendingDelete.value
                    ?.taskIds
                    ?.singleOrNull(),
                "the newer delete owns the offer",
            )

            vm.onIntent(AgendaIntent.UndoDeleteTapped)
            runCurrent()

            assertNull(archivedAt("t2"), "undo applies to the newer delete")
            assertNotNull(archivedAt("t1"), "the superseded delete stays deleted — its window was taken")
        }

    /**
     * A timer that no longer owns the slot must not clear it.
     *
     * The two deletes are two seconds apart so the first window closes at t=5000
     * while the second is still open until t=7000; without the offset both expire
     * together and the assertion is never actually reached.
     *
     * Verified to be non-vacuous: with the generation check *and* the job cancel
     * both removed, the stale timer wins the race and this fails. It does not
     * distinguish which of the two is doing the work — under `runTest`'s single
     * thread the cancel alone is enough — so it pins the invariant, not the
     * mechanism. The generation check is what carries it on the multi-threaded
     * production dispatcher, where cancelling a timer that has already resumed is
     * best-effort.
     */
    @Test
    fun `a superseded timer cannot clear the newer offer`() =
        withVm { vm ->
            fakeRepo.seed(task("t1", "First"), task("t2", "Second"))
            runCurrent()

            // First delete's window closes at t=5000; second's at t=7000.
            vm.onIntent(AgendaIntent.TaskDeleteClicked(TaskId("t1")))
            runCurrent()
            advanceTimeBy(2_000)
            vm.onIntent(AgendaIntent.TaskDeleteClicked(TaskId("t2")))
            runCurrent()

            // Land strictly between the two expiries, so the stale timer is the only
            // thing that could clear the slot.
            advanceTimeBy(3_001)
            runCurrent()

            assertEquals(
                TaskId("t2"),
                vm.pendingDelete.value
                    ?.taskIds
                    ?.singleOrNull(),
                "the newer offer must survive the older window's expiry",
            )
        }

    @Test
    fun `deleting cancels the task's reminders`() =
        withVm { vm ->
            fakeRepo.seed(task("t1", "Doomed"))
            val cancelled = mutableListOf<TaskId>()
            val scoped =
                createVmIn { scope ->
                    AgendaViewModel(
                        deps =
                            agendaDeps(
                                reminderScheduler = recordingScheduler(cancelled),
                            ),
                        definition = AgendaPresets.Inbox,
                        scope = scope,
                    )
                }
            runCurrent()

            scoped.onIntent(AgendaIntent.TaskDeleteClicked(TaskId("t1")))
            runCurrent()

            assertEquals(listOf(TaskId("t1")), cancelled, "a deleted task must not keep notifying")
            assertNotNull(archivedAt("t1"))
        }

    /**
     * `cancelByTask` returns `Unit` and hands back no `Reminder` spec, so a failed
     * delete cannot restore what it cancelled without a repository API change. The
     * accepted trade: a reminder never outlives its task; a lost reminder is
     * recoverable, a zombie reminder is not. Pinned so the choice stays visible.
     */
    @Test
    fun `a failed delete after a successful cancel leaves the task without its reminder`() =
        withVm { vm ->
            fakeRepo.seed(task("t1", "Doomed"))
            val cancelled = mutableListOf<TaskId>()
            val scoped =
                createVmIn { scope ->
                    AgendaViewModel(
                        deps =
                            agendaDeps(
                                reminderScheduler = recordingScheduler(cancelled),
                            ),
                        definition = AgendaPresets.Inbox,
                        scope = scope,
                    )
                }
            fakeRepo.softDeleteOverride = Result.failure(IllegalStateException("conflict"))
            runCurrent()

            val events = mutableListOf<AgendaUiEvent>()
            val collector = launch { scoped.events.collect { events += it } }
            try {
                scoped.onIntent(AgendaIntent.TaskDeleteClicked(TaskId("t1")))
                runCurrent()

                assertEquals(listOf(TaskId("t1")), cancelled)
                assertNull(archivedAt("t1"), "the task survives a rejected delete")
                assertNull(scoped.pendingDelete.value)
                assertTrue(events.any { it is AgendaUiEvent.ShowError })
            } finally {
                collector.cancel()
            }
        }

    private fun recordingScheduler(sink: MutableList<TaskId>) =
        object : ReminderScheduler {
            override val isSupported: Boolean = true

            override suspend fun schedule(reminder: com.singularity.todo.feature.reminders.Reminder) {}

            override suspend fun cancel(
                id: ReminderId,
                userId: UserId,
            ) {}

            override suspend fun cancelByTask(
                taskId: TaskId,
                userId: UserId,
            ) {
                sink += taskId
            }
        }

    // ─────────────────────────── Selection ──────────────────────────────────────

    @Test
    fun `selection survives an agenda re-evaluation`() =
        withVm { vm ->
            fakeRepo.seed(task("t1", "Keep me"))
            runCurrent()

            vm.onIntent(AgendaIntent.EnterSelectionMode(TaskId("t1")))
            runCurrent()
            assertEquals(setOf(TaskId("t1")), loadedSelection(vm))

            fakeRepo.add(task("t2", "New arrival"))
            runCurrent()

            assertEquals(
                setOf(TaskId("t1")),
                loadedSelection(vm),
                "a re-evaluation must not silently drop what the user had selected",
            )
        }

    /**
     * A selected task that stops being evaluated must not stay selected.
     *
     * Holding the id would let it ride along into a later bulk delete, sending an id
     * for a task that is already gone.
     */
    @Test
    fun `a selected task that leaves the agenda is dropped from the selection`() =
        withVm { vm ->
            fakeRepo.seed(task("t1", "Doomed"))
            runCurrent()

            vm.onIntent(AgendaIntent.EnterSelectionMode(TaskId("t1")))
            runCurrent()
            assertEquals(setOf(TaskId("t1")), loadedSelection(vm))

            fakeRepo.softDelete(TaskId("t1"))
            runCurrent()

            assertEquals(
                emptySet(),
                loadedSelection(vm),
                "an id that is no longer in any section must not stay selected",
            )
            assertFalse(
                assertIs<AgendaUiState.Loaded>(vm.stateFlow.value).isSelectionMode,
                "selection mode exits when nothing remains selected",
            )
        }

    @Test
    fun `a failed completion reports the failure`() =
        withVm { vm ->
            fakeRepo.seed(task("t1", "Doomed"))
            runCurrent()

            val events = mutableListOf<AgendaUiEvent>()
            val collector = launch { vm.events.collect { events += it } }
            try {
                fakeRepo.completeOverride = Result.failure(IllegalStateException("disk full"))
                vm.onIntent(AgendaIntent.TaskCheckClicked(TaskId("t1")))
                runCurrent()

                assertTrue(
                    events.any { it is AgendaUiEvent.ShowError },
                    "the user must be told the toggle failed: $events",
                )
            } finally {
                collector.cancel()
            }
        }

    @Test
    fun `a failed togglePinned reports the failure`() =
        withVm { vm ->
            fakeRepo.seed(task("t1", "Doomed"))
            runCurrent()

            val events = mutableListOf<AgendaUiEvent>()
            val collector = launch { vm.events.collect { events += it } }
            try {
                fakeRepo.togglePinnedOverride = Result.failure(IllegalStateException("disk full"))
                vm.onIntent(AgendaIntent.TaskPinClicked(TaskId("t1")))
                runCurrent()

                assertTrue(
                    events.any { it is AgendaUiEvent.ShowError },
                    "the user must be told the toggle failed: $events",
                )
            } finally {
                collector.cancel()
            }
        }

    // ─── The greeting follows the clock and the settings ──────────────────────
    //
    // The greeting used to be computed once in `init` and snapshotted into every
    // `Loaded` state, so it could change only when the ViewModel was recreated. It is
    // now derived from the two observed greeting-hour settings and a clock that
    // re-emits at every hour boundary, so these tests move the clock and the settings
    // instead of counting dispatches.

    /**
     * Waits until the agenda renders [greeting], then returns the state carrying it.
     *
     * Awaited rather than counted: the value arrives from a `combine` of three flows,
     * so how many dispatches it takes is an implementation detail — and the four
     * `runCurrent()` calls the one-shot computation needed are exactly the choreography
     * that stops being true the moment the shape changes.
     */
    private fun TestScope.awaitGreeting(
        vm: AgendaViewModel,
        greeting: String,
    ): AgendaUiState.Loaded {
        awaitState { (vm.stateFlow.value as? AgendaUiState.Loaded)?.greeting == greeting }
        return assertIs<AgendaUiState.Loaded>(vm.stateFlow.value)
    }

    /** A ViewModel whose clock this test can move, built the way [createVm] builds one. */
    private fun TestScope.createVmAt(clock: FakeClock): AgendaViewModel =
        createVmIn { scope ->
            AgendaViewModel(
                deps = agendaDeps(clock = clock),
                definition = AgendaPresets.Inbox,
                scope = scope,
            )
        }

    /**
     * Moves the ViewModel's clock past the next hour boundary and lets the hour flow
     * notice it.
     *
     * Both clocks have to move, and that is not a detail a test may forget: the fake
     * clock says what time it *is*, the coroutine scheduler says when the hour flow's
     * `delay` elapses, and advancing one without the other either reads the old hour or
     * never wakes up. Overshooting the delay is harmless — the flow re-reads the hour
     * after every wake-up and `distinctUntilChanged` drops a repeat.
     */
    private fun TestScope.crossHourBoundaryTo(
        clock: FakeClock,
        instant: Instant,
    ) {
        clock.setNow(instant)
        advanceTimeBy(61 * 60_000L)
    }

    @Test
    fun `greeting is Good morning before noon`() =
        runTest {
            // fakeSettings default: morningEndHour=12, afternoonEndHour=18
            // fakeClock default: 09:00 UTC → local hour < 12 → "Good morning"
            fakeRepo.seed(task("t1", "Morning task"))
            val vm = createVm()
            assertEquals(
                "Good morning",
                awaitGreeting(vm, "Good morning").greeting,
                "9am UTC should produce Good morning",
            )
        }

    @Test
    fun `greeting is Good afternoon between noon and 6pm`() =
        runTest {
            // A settings that ends morning at 10 and afternoon at 14 (10am–2pm = afternoon).
            // A clock at 11:00 UTC → local hour 11 is between 10 and 14 → "Good afternoon".
            val customGreeting =
                object : GreetingSettingsRepository {
                    override val morningEndHour: kotlinx.coroutines.flow.Flow<Int> = kotlinx.coroutines.flow.flowOf(10)
                    override val afternoonEndHour: kotlinx.coroutines.flow.Flow<Int> =
                        kotlinx.coroutines.flow.flowOf(
                            14,
                        )

                    override suspend fun setMorningEndHour(hour: Int) {}

                    override suspend fun setAfternoonEndHour(hour: Int) {}
                }
            fakeRepo.seed(task("t1", "Afternoon task"))
            val vm =
                createVmIn { scope ->
                    AgendaViewModel(
                        deps =
                            agendaDeps(
                                clock = FakeClock(Instant.parse("2026-09-16T11:00:00Z")),
                                greetingSettings = customGreeting,
                            ),
                        definition = AgendaPresets.Inbox,
                        scope = scope,
                    )
                }
            assertEquals(
                "Good afternoon",
                awaitGreeting(vm, "Good afternoon").greeting,
                "11am UTC with morningEnd=10 should produce Good afternoon",
            )
        }

    @Test
    fun `greeting is Good evening after 6pm`() =
        runTest {
            fakeRepo.seed(task("t1", "Evening task"))
            val vm =
                createVmIn { scope ->
                    AgendaViewModel(
                        deps =
                            agendaDeps(
                                clock = FakeClock(Instant.parse("2026-09-16T20:00:00Z")),
                            ),
                        definition = AgendaPresets.Inbox,
                        scope = scope,
                    )
                }
            assertEquals(
                "Good evening",
                awaitGreeting(vm, "Good evening").greeting,
                "20:00 UTC should produce Good evening",
            )
        }

    /**
     * The defect the one-shot computation had: a screen left open across a boundary kept
     * the greeting it was built with. The ViewModel is the same instance throughout, so
     * nothing about the change can be explained by a fresh `init`.
     */
    @Test
    fun `crossing an hour boundary changes the greeting without recreating the ViewModel`() =
        runTest {
            fakeRepo.seed(task("t1", "Open across noon"))
            val clock = FakeClock(Instant.parse("2026-09-16T11:59:00Z"))
            val vm = createVmAt(clock)
            assertEquals(
                "Good morning",
                awaitGreeting(vm, "Good morning").greeting,
                "11:59 is before the 12:00 morning boundary",
            )

            crossHourBoundaryTo(clock, Instant.parse("2026-09-16T12:01:00Z"))
            assertEquals("Good afternoon", awaitGreeting(vm, "Good afternoon").greeting)

            crossHourBoundaryTo(clock, Instant.parse("2026-09-16T18:01:00Z"))
            assertEquals("Good evening", awaitGreeting(vm, "Good evening").greeting)
        }

    /**
     * #1961: the agenda kept the greeting computed with the hours configured when the
     * screen opened, so a change made in Settings was visible only after leaving the
     * agenda and coming back to it.
     */
    @Test
    fun `changing a configured boundary changes the greeting while the ViewModel lives`() =
        runTest {
            fakeRepo.seed(task("t1", "Settings change"))
            // fakeClock: 09:00 UTC; the fake settings end the morning at 12.
            val vm = createVm()
            assertEquals("Good morning", awaitGreeting(vm, "Good morning").greeting)

            // The user moves the morning boundary below the current hour: 09:00 is now
            // afternoon, and the observed settings flow is what carries the new value.
            fakeSettings.greeting.setMorningEndHour(8)

            assertEquals("Good afternoon", awaitGreeting(vm, "Good afternoon").greeting)
        }

    /**
     * The other half of the promise: a greeting that cannot be computed degrades to
     * none — not to the value the first successful read produced, which would be a wrong
     * greeting and worse than no greeting.
     */
    @Test
    fun `a failing settings read yields no greeting rather than a stale one`() =
        runTest {
            // The first read succeeds; the failure is released afterwards, so the test
            // *shows* a stale greeting being replaced. A read that failed before anything
            // was computed would pass the same assertion while proving nothing.
            val releaseFailure = CompletableDeferred<Unit>()
            val unreadable =
                object : GreetingSettingsRepository {
                    override val morningEndHour: Flow<Int> =
                        flow {
                            emit(10)
                            releaseFailure.await()
                            throw IllegalStateException("settings unreadable")
                        }
                    override val afternoonEndHour: Flow<Int> = flow { emit(18) }

                    override suspend fun setMorningEndHour(hour: Int) {}

                    override suspend fun setAfternoonEndHour(hour: Int) {}
                }
            fakeRepo.seed(task("t1", "Unreadable settings"))
            val vm =
                createVmIn { scope ->
                    AgendaViewModel(
                        deps =
                            agendaDeps(
                                clock = FakeClock(Instant.parse("2026-09-16T11:00:00Z")),
                                greetingSettings = unreadable,
                            ),
                        definition = AgendaPresets.Inbox,
                        scope = scope,
                    )
                }

            // 11:00 with morningEnd=10 is a real greeting, and it is what the user sees
            // before the settings become unreadable.
            assertEquals("Good afternoon", awaitGreeting(vm, "Good afternoon").greeting)

            releaseFailure.complete(Unit)

            assertEquals("", awaitGreeting(vm, "").greeting, "a failed read must degrade to no greeting")
            assertIs<AgendaUiState.Loaded>(
                vm.stateFlow.value,
                "the failure is the greeting's, not the agenda's: the task list must still be the state",
            )
        }

    /**
     * A greeting is decoration. Recomputing it must not cost the user the list they are
     * reading or the selection they are in the middle of making.
     */
    @Test
    fun `a greeting change leaves the task list and the selection alone`() =
        runTest {
            fakeRepo.seed(task("t1", "Kept"))
            val clock = FakeClock(Instant.parse("2026-09-16T11:59:00Z"))
            val vm = createVmAt(clock)
            val before = awaitGreeting(vm, "Good morning")

            vm.onIntent(AgendaIntent.ToggleSelection(TaskId("t1")))
            awaitState {
                (vm.stateFlow.value as? AgendaUiState.Loaded)?.selectedTaskIds?.contains(TaskId("t1")) == true
            }
            val selected = assertIs<AgendaUiState.Loaded>(vm.stateFlow.value)

            crossHourBoundaryTo(clock, Instant.parse("2026-09-16T12:01:00Z"))
            val after = awaitGreeting(vm, "Good afternoon")

            assertEquals(before.sections, after.sections, "a greeting change must not rebuild the task list")
            assertEquals(
                selected.selectedTaskIds,
                after.selectedTaskIds,
                "a greeting change must not drop the selection",
            )
            assertTrue(after.isSelectionMode, "a greeting change must not leave selection mode")
        }

    // ─── WorkSchedule and Greeting deps are wired ─────────────────────────────

    /**
     * Verifies that AgendaDeps accepts WorkScheduleSettingsRepository and
     * GreetingSettingsRepository without throwing. The values flow through the
     * DI graph; this test acts as a wiring smoke test.
     *
     * The workSchedule settings are consumed in AgendaRouteMapping (not AgendaViewModel),
     * so this test only confirms the deps are accepted and the VM starts normally.
     */
    @Test
    fun `VM starts with workScheduleSettings and greetingSettings wired`() =
        runTest {
            fakeRepo.seed(task("t1", "Wired"))
            val vm = createVm()
            val state = awaitGreeting(vm, "Good morning")
            assertEquals("Good morning", state.greeting, "9am UTC with default fakeSettings must produce Good morning")
        }

    private fun loadedSelection(vm: AgendaViewModel): Set<TaskId> =
        assertIs<AgendaUiState.Loaded>(vm.stateFlow.value).selectedTaskIds

    // ─── The blocked-completion guard ─────────────────────────────────────────

    @Test
    fun `a task with an unfinished prerequisite is not closed and asks instead`() =
        withVm { vm ->
            fakeRepo.seed(
                task("dep", "Dependency"),
                task("t1", "Blocked").copy(blockedBy = setOf(TaskId("dep"))),
            )
            runCurrent()

            vm.onIntent(AgendaIntent.TaskCheckClicked(TaskId("t1")))
            runCurrent()

            assertNull(
                fakeRepo.tasks.value["t1"]?.completedAt,
                "a blocked task must not be closed on the first request",
            )
            val request = assertIs<AgendaUiState.Loaded>(vm.stateFlow.value).pendingBlockedClose
            assertNotNull(request, "the user must be asked")
            assertEquals(listOf("Dependency"), request.blockerTitles)
        }

    @Test
    fun `confirming the request closes the task`() =
        withVm { vm ->
            fakeRepo.seed(
                task("dep", "Dependency"),
                task("t1", "Blocked").copy(blockedBy = setOf(TaskId("dep"))),
            )
            runCurrent()

            vm.onIntent(AgendaIntent.TaskCheckClicked(TaskId("t1")))
            runCurrent()
            vm.onIntent(AgendaIntent.ConfirmBlockedClose(TaskId("t1")))
            runCurrent()

            assertNotNull(fakeRepo.tasks.value["t1"]?.completedAt, "the confirmed close must reach the repository")
            assertNull(
                assertIs<AgendaUiState.Loaded>(vm.stateFlow.value).pendingBlockedClose,
                "the confirmation must be closed once it has been answered",
            )
        }

    @Test
    fun `dismissing the request leaves the task alone`() =
        withVm { vm ->
            fakeRepo.seed(
                task("dep", "Dependency"),
                task("t1", "Blocked").copy(blockedBy = setOf(TaskId("dep"))),
            )
            runCurrent()

            vm.onIntent(AgendaIntent.TaskCheckClicked(TaskId("t1")))
            runCurrent()
            vm.onIntent(AgendaIntent.DismissBlockedClose)
            runCurrent()

            assertNull(fakeRepo.tasks.value["t1"]?.completedAt, "nothing may be written")
            assertNull(
                assertIs<AgendaUiState.Loaded>(vm.stateFlow.value).pendingBlockedClose,
                "the confirmation must be closed",
            )
        }

    @Test
    fun `an unblocked task closes without a question`() =
        withVm { vm ->
            fakeRepo.seed(task("t1", "Plain"))
            runCurrent()

            vm.onIntent(AgendaIntent.TaskCheckClicked(TaskId("t1")))
            runCurrent()

            assertNotNull(fakeRepo.tasks.value["t1"]?.completedAt)
            assertNull(
                assertIs<AgendaUiState.Loaded>(vm.stateFlow.value).pendingBlockedClose,
                "an unblocked task must not raise a confirmation",
            )
        }

    @Test
    fun `a finished prerequisite does not ask`() =
        withVm { vm ->
            val done = task("dep", "Dependency").copy(completedAt = now)
            fakeRepo.seed(done, task("t1", "Blocked").copy(blockedBy = setOf(TaskId("dep"))))
            runCurrent()

            vm.onIntent(AgendaIntent.TaskCheckClicked(TaskId("t1")))
            runCurrent()

            assertNotNull(fakeRepo.tasks.value["t1"]?.completedAt)
            assertNull(assertIs<AgendaUiState.Loaded>(vm.stateFlow.value).pendingBlockedClose)
        }

    // ─── The project badge ────────────────────────────────────────────────────

    /** The row for [taskId] in the current state, or null. */
    private fun rowFor(
        vm: AgendaViewModel,
        taskId: String,
    ) = assertIs<AgendaUiState.Loaded>(vm.stateFlow.value)
        .sections
        .flatMap { it.tasks }
        .firstOrNull { it.task.id.value == taskId }

    @Test
    fun `a row carries its project's name and colour`() =
        withVm { vm ->
            val projectId =
                com.singularity.todo.feature.projects.domain.model
                    .ProjectId("p1")
            fakeProjectsRepo.seed(
                com.singularity.todo.test.fakes
                    .testProject(id = projectId, name = "Семья", color = 0xFF6A4C93.toInt()),
            )
            fakeRepo.seed(task("t1", "Buy milk").copy(projectId = projectId))
            runCurrent()

            val row = rowFor(vm, "t1")
            assertEquals("Семья", row?.project, "the row carries the project's name")
            assertEquals(0xFF6A4C93.toInt(), row?.projectColor, "and its colour, resolved once per list build")
        }

    @Test
    fun `a row in a closed project still carries its badge`() =
        withVm { vm ->
            val projectId =
                com.singularity.todo.feature.projects.domain.model
                    .ProjectId("p1")
            fakeProjectsRepo.seed(
                com.singularity.todo.test.fakes.testProject(
                    id = projectId,
                    name = "Closed family",
                    color = 0xFF112233.toInt(),
                    closedAt = now,
                ),
            )
            fakeRepo.seed(task("t1", "Buy milk").copy(projectId = projectId))
            runCurrent()

            assertEquals(
                "Closed family",
                rowFor(vm, "t1")?.project,
                "a closed project still names itself — the badge must not vanish",
            )
        }

    @Test
    fun `a row without a project carries no badge`() =
        withVm { vm ->
            fakeRepo.seed(task("t1", "Buy milk"))
            runCurrent()

            assertNull(rowFor(vm, "t1")?.project, "no project, no badge — and no placeholder")
        }

    @Test
    fun `the projects are resolved once per list build, not per row`() =
        withVm { vm ->
            val projectId =
                com.singularity.todo.feature.projects.domain.model
                    .ProjectId("p1")
            fakeProjectsRepo.seed(
                com.singularity.todo.test.fakes
                    .testProject(id = projectId, name = "Семья", color = 1),
            )
            // A list of many rows, all in the same project.
            fakeRepo.seed(*(0 until 100).map { i -> task("t$i", "Task $i").copy(projectId = projectId) }.toTypedArray())
            runCurrent()

            assertEquals(
                1,
                fakeProjectsRepo.observeAllIncludingClosedCalls,
                "the lookup is once per list build, not once per row",
            )
        }

    // ─── A failed load is reported ───────────────────────────────────────────

    /**
     * A task repository whose filtered observation fails.
     *
     * The fake has no hook for a failing flow, so the observation is overridden
     * here: the agenda's collection stops on it, and the catch that handles the
     * stop is what this test is about.
     *
     * The failure is switchable and the subscriptions are counted, because a
     * retry only means something when the second attempt *can* succeed and
     * when the test can see that a new observation ran at all.
     */
    private class FailingTaskRepository(initialFailure: Throwable) : FakeTaskRepository() {
        /** The failure the next observation throws; null lets it observe normally. */
        var failure: Throwable? = initialFailure

        /** How many times the filtered observation was subscribed to. */
        var observations: Int = 0
            private set

        override fun observeByFilter(filter: TaskFilter): Flow<List<Task>> {
            // The delegate is taken here rather than inside the flow builder:
            // a `super` call is not allowed inside a lambda.
            val observed = super.observeByFilter(filter)
            return flow {
                observations++
                val cause = failure
                if (cause != null) throw cause
                emitAll(observed)
            }
        }
    }

    /**
     * The cause must reach the crash reporter, not only the screen.
     *
     * The catch that handles the stopped collection used to turn the cause into
     * a message and drop it, so a defect in the agenda's observation was
     * invisible off-device: the screen said "Could not load agenda" and nothing
     * said why.
     */
    @Test
    fun `a failed load reports the cause to the crash reporter`() =
        runTest {
            val failure = IllegalStateException("the observation failed")
            val reporter = RecordingCrashReportingPort()
            val vm =
                createVmIn { scope ->
                    AgendaViewModel(
                        deps = agendaDeps(taskRepo = FailingTaskRepository(failure)),
                        definition = AgendaPresets.Inbox,
                        crashReporter = reporter,
                        scope = scope,
                    )
                }
            // The failure travels through several dispatches — flatMapLatest, the
            // combine, the catch — so the scheduler is drained twice.
            runCurrent()
            runCurrent()

            assertIs<AgendaUiState.Error>(vm.stateFlow.value)
            val reported = reporter.reports.single()
            assertSame(failure, reported.first, "the reporter gets the original throwable")
            assertEquals("agenda.load_failed", reported.second)
        }

    // ─── The retry ────────────────────────────────────────────────────────────
    //
    // The error state's Retry button used to do nothing the user could see: the
    // failed collection had ended the ViewModel's subscription to the reload
    // trigger, so the trigger the Retry intent bumped was one nobody listened
    // to any more. The screen went back to the spinner and stayed there.

    /**
     * The retry has to run the observation again, not only change the state.
     *
     * The observable result is the loaded agenda: the screen leaves the error
     * state and shows the tasks again.
     */
    @Test
    fun `a retry after a failed load reaches the loaded state`() =
        runTest {
            val failure = IllegalStateException("the observation failed")
            val repo = FailingTaskRepository(failure)
            val vm =
                createVmIn { scope ->
                    AgendaViewModel(
                        deps = agendaDeps(taskRepo = repo),
                        definition = AgendaPresets.Inbox,
                        scope = scope,
                    )
                }
            runCurrent()
            runCurrent()

            assertIs<AgendaUiState.Error>(vm.stateFlow.value)

            // The second attempt succeeds.
            repo.failure = null

            vm.onIntent(AgendaIntent.Retry)
            runCurrent()
            runCurrent()

            assertIs<AgendaUiState.Loaded>(
                vm.stateFlow.value,
                "the retry must bring the agenda back — the screen must not stay on the spinner",
            )
            assertEquals(
                2,
                repo.observations,
                "the retry must run a new observation, not reuse the one that failed",
            )
        }

    /**
     * A retry that fails again lands in the error state — not on the spinner.
     *
     * The state the Retry intent writes (loading) is a promise that the load
     * is running; when the load fails, that promise is broken and the error
     * state has to replace it.
     */
    @Test
    fun `a retry that fails again lands in the error state`() =
        runTest {
            val failure = IllegalStateException("the observation failed")
            val reporter = RecordingCrashReportingPort()
            val repo = FailingTaskRepository(failure)
            val vm =
                createVmIn { scope ->
                    AgendaViewModel(
                        deps = agendaDeps(taskRepo = repo),
                        definition = AgendaPresets.Inbox,
                        crashReporter = reporter,
                        scope = scope,
                    )
                }
            runCurrent()
            runCurrent()

            assertIs<AgendaUiState.Error>(vm.stateFlow.value)

            vm.onIntent(AgendaIntent.Retry)
            runCurrent()
            runCurrent()

            assertIs<AgendaUiState.Error>(
                vm.stateFlow.value,
                "a failed retry must land in the error state, not on the spinner",
            )
            assertEquals(
                2,
                reporter.reports.size,
                "every failed attempt is reported — the first one and the retry",
            )
            assertSame(failure, reporter.reports.last().first, "the reporter gets the original throwable")
            assertEquals("agenda.load_failed", reporter.reports.last().second)
        }

    // ─── Large overdue section on Today ─────────────────────────────────────

    private fun overdueTask(
        id: String,
        daysAgo: Int = 1,
    ) = Task(
        id = TaskId(id),
        title = "Overdue $id",
        dueDate = LocalDate(2026, 9, 16).minus(daysAgo, kotlinx.datetime.DateTimeUnit.DAY),
        createdAt = Clock.System.now(),
        updatedAt = Clock.System.now(),
        userId = UserId("test-user"),
    )

    @Test
    fun `large overdue section is collapsed by default on Today`() =
        runTest {
            fakeRepo.seed(*(1..1001).map { overdueTask("o$it", daysAgo = (it % 30) + 1) }.toTypedArray())
            val vm =
                createVmIn { scope ->
                    AgendaViewModel(
                        deps = agendaDeps(clock = FakeClock(now)),
                        definition = AgendaPresets.Today,
                        isTodayTab = true,
                        scope = scope,
                    )
                }
            runCurrent()

            val state = assertIs<AgendaUiState.Loaded>(vm.stateFlow.value)
            val overdue = state.sections.firstOrNull { it.id == "overdue" }
            assertNotNull(overdue, "overdue section must be present")
            assertEquals(1001, overdue.badge ?: overdue.tasks.size)
            assertTrue("overdue" in state.collapsedSectionIds, "large overdue must start collapsed on Today")
        }

    @Test
    fun `small overdue section is not collapsed by default on Today`() =
        runTest {
            fakeRepo.seed(overdueTask("o1"), overdueTask("o2", daysAgo = 2))
            val vm =
                createVmIn { scope ->
                    AgendaViewModel(
                        deps = agendaDeps(clock = FakeClock(now)),
                        definition = AgendaPresets.Today,
                        isTodayTab = true,
                        scope = scope,
                    )
                }
            runCurrent()

            val state = assertIs<AgendaUiState.Loaded>(vm.stateFlow.value)
            assertTrue(state.sections.any { it.id == "overdue" })
            assertFalse("overdue" in state.collapsedSectionIds, "small overdue should remain expanded")
        }

    @Test
    fun `toggle overdue section updates state and survives task updates`() =
        runTest {
            fakeRepo.seed(*(1..1001).map { overdueTask("o$it", daysAgo = (it % 30) + 1) }.toTypedArray())
            val vm =
                createVmIn { scope ->
                    AgendaViewModel(
                        deps = agendaDeps(clock = FakeClock(now)),
                        definition = AgendaPresets.Today,
                        isTodayTab = true,
                        scope = scope,
                    )
                }
            runCurrent()

            val initial = assertIs<AgendaUiState.Loaded>(vm.stateFlow.value)
            assertTrue("overdue" in initial.collapsedSectionIds)

            vm.onIntent(AgendaIntent.ToggleSection("overdue"))
            runCurrent()
            val expanded = assertIs<AgendaUiState.Loaded>(vm.stateFlow.value)
            assertFalse("overdue" in expanded.collapsedSectionIds, "manual toggle must expand")

            // New emission (task list change) must keep manual choice.
            fakeRepo.seed(
                *(1..1001).map { overdueTask("o$it", daysAgo = (it % 30) + 1) }.toTypedArray(),
                task("t-today", "Today task").copy(dueDate = LocalDate(2026, 9, 16)),
            )
            runCurrent()
            val afterUpdate = assertIs<AgendaUiState.Loaded>(vm.stateFlow.value)
            assertFalse("overdue" in afterUpdate.collapsedSectionIds, "manual expand must persist across updates")

            vm.onIntent(AgendaIntent.ToggleSection("overdue"))
            runCurrent()
            val collapsedAgain = assertIs<AgendaUiState.Loaded>(vm.stateFlow.value)
            assertTrue("overdue" in collapsedAgain.collapsedSectionIds)
        }

    @Test
    fun `an agenda titled Today is not the Today tab`() =
        runTest {
            fakeRepo.seed(*(1..1001).map { overdueTask("o$it", daysAgo = (it % 30) + 1) }.toTypedArray())
            val lookalike = AgendaPresets.byOverdue().copy(title = AgendaPresets.Today.title)
            val vm =
                createVmIn { scope ->
                    AgendaViewModel(
                        deps = agendaDeps(clock = FakeClock(now)),
                        definition = lookalike,
                        scope = scope,
                    )
                }
            runCurrent()

            val state = assertIs<AgendaUiState.Loaded>(vm.stateFlow.value)
            assertFalse(state.isTodayAgenda, "the title alone must not make a screen the Today tab")
            assertFalse("overdue" in state.collapsedSectionIds, "a lookalike title gets no auto-collapse")
        }

    @Test
    fun `OpenOverdue emits NavigateToOverdue event`() =
        runTest {
            val vm = createVm()
            runCurrent()

            val events = mutableListOf<AgendaUiEvent>()
            val collector = launch { vm.events.collect { events += it } }
            try {
                vm.onIntent(AgendaIntent.OpenOverdue)
                runCurrent()
                assertTrue(
                    events.any { it is AgendaUiEvent.NavigateToOverdue },
                    "expected NavigateToOverdue, got $events",
                )
            } finally {
                collector.cancel()
            }
        }
}
