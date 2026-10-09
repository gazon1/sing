@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.feature.agenda.presentation.viewmodel

import com.singularity.todo.test.fakes.FakeClock
import com.singularity.todo.test.fakes.TEST_TZ
import co.touchlab.kermit.Logger
import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.draft.FakeDraftStore
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.agenda.domain.logic.AgendaPresets
import com.singularity.todo.feature.agenda.domain.model.AgendaUiState
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.reminders.ReminderId
import com.singularity.todo.feature.reminders.ReminderScheduler
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.test.fakes.FakeProfileAwareCurrentUser
import com.singularity.todo.test.fakes.FakeTaskMutationsUseCase
import com.singularity.todo.test.fakes.FakeTaskRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import com.singularity.todo.feature.tasks.presentation.state.DueDateOption
import com.singularity.todo.feature.tasks.presentation.state.TaskDraft
import com.singularity.todo.feature.agenda.domain.model.AgendaIntent
import com.singularity.todo.feature.agenda.domain.model.AgendaUiEvent
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy

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
 * ## Why the VM scope owns its own Job
 * The same infinite loop means the VM's init coroutine never completes, so it cannot
 * be a child of the test's [TestScope] job (runTest would await it and time out).
 * A detached Job plus an explicit `close()` in `finally` keeps the loop bounded.
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

    private val fakeRepo = FakeTaskRepository()
    private val fakeCurrentUser: ProfileAwareCurrentUser = FakeProfileAwareCurrentUser()
    private val fakeTaskMutations = FakeTaskMutationsUseCase(fakeRepo)
    private val fakeReminderScheduler = object : ReminderScheduler {
        override val isSupported: Boolean = true
        override suspend fun schedule(reminder: com.singularity.todo.feature.reminders.Reminder) {}
        override suspend fun cancel(id: ReminderId, userId: UserId) {}
        override suspend fun cancelByTask(
            taskId: com.singularity.todo.feature.tasks.domain.model.TaskId,
            userId: UserId,
        ) {}
    }

    private fun task(id: String, title: String) = Task(
        id = TaskId(id),
        title = title,
        createdAt = Clock.System.now(),
        updatedAt = Clock.System.now(),
        userId = UserId("test-user"),
    )

    private fun TestScope.createVm(scope: AutoCloseableCoroutineScope) = AgendaViewModel(
        deps = AgendaDeps(
            taskRepo = fakeRepo,
            clock = FakeClock(now),
            timeZone = TEST_TZ,
            logger = Logger,
            draftStore = FakeDraftStore(),
            reminderScheduler = fakeReminderScheduler,
            currentUser = fakeCurrentUser,
            taskMutations = fakeTaskMutations,
        ),
        definition = AgendaPresets.Inbox,
        scope = scope,
    )

    @Test
    fun `leaves Loading and renders the seeded tasks`() = runTest {
        fakeRepo.seed(task("t1", "Buy milk"), task("t2", "Ship release"))
        val vmScope = AutoCloseableCoroutineScope(coroutineContext + Job())
        try {
            val vm = createVm(vmScope)
            runCurrent()

            val state = assertIs<AgendaUiState.Loaded>(
                vm.stateFlow.value,
                "VM stayed on Loading — the collected state is being discarded",
            )
            val titles = state.sections.flatMap { it.tasks }.map { it.task.title }
            assertEquals(setOf("Buy milk", "Ship release"), titles.toSet())
        } finally {
            vmScope.close()
        }
    }

    @Test
    fun `emits Loaded even when the user has no tasks`() = runTest {
        val vmScope = AutoCloseableCoroutineScope(coroutineContext + Job())
        try {
            val vm = createVm(vmScope)
            runCurrent()

            // An empty list must still resolve to Loaded — otherwise the screen
            // would spin forever before it could show its empty state.
            val state = assertIs<AgendaUiState.Loaded>(vm.stateFlow.value)
            assertEquals(emptyList(), state.sections.flatMap { it.tasks })
        } finally {
            vmScope.close()
        }
    }

    /**
     * Regression: a task with `dueDate = null` that is seeded *before* VM construction
     * must appear in the Loaded state. Before the fix in [ProfileAwareCurrentUser],
     * `_scopedUserId` was seeded with `MutableStateFlow(UserId.anonymous)` (not yet
     * resolved from the auth context), so the repository query ran with the anonymous
     * user id and returned no results for the real seeded task.
     */
    @Test
    fun `undated task seeded before VM construction is visible in Loaded sections`() = runTest {
        val undated = task(id = "no-date-1", title = "Inbox me") // dueDate = null
        fakeRepo.seed(undated) // seed BEFORE creating the VM

        val vmScope = AutoCloseableCoroutineScope(coroutineContext + Job())
        try {
            val vm = createVm(vmScope)
            runCurrent()

            val state = assertIs<AgendaUiState.Loaded>(vm.stateFlow.value)
            assertTrue(
                state.sections.any { section ->
                    section.tasks.any { it.task.id == undated.id }
                },
                "Undated task seeded before VM construction must reach the agenda's No Date section",
            )
        } finally {
            vmScope.close()
        }
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
    fun `create in section prefills a due date relative to the injected clock`() = runTest {
        val pinned = LocalDate(2031, 7, 9) // a Wednesday, far from any seed date
        val vmScope = AutoCloseableCoroutineScope(coroutineContext + Job())
        val draftStore = FakeDraftStore()
        try {
            val vm = AgendaViewModel(
                deps = AgendaDeps(
                    taskRepo = fakeRepo,
                    // Noon UTC with an explicit zone. The zone used to be the
                    // host's, which is why this had to be noon: midnight would land
                    // on a different date in half the world. With `timeZone` pinned to
                    // UTC the instant can be anything, and the "noon so the date cannot
                    // move" reasoning goes with it (#91).
                    clock = FakeClock(Instant.parse("2031-07-09T00:30:00Z")),
                    timeZone = TEST_TZ,
                    logger = Logger,
                    draftStore = draftStore,
                    reminderScheduler = fakeReminderScheduler,
                    currentUser = fakeCurrentUser,
                    taskMutations = fakeTaskMutations,
                ),
                definition = AgendaPresets.Today,
                scope = vmScope,
            )
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
        } finally {
            vmScope.close()
        }
    }

    // ─────────────────────── Delete / undo ──────────────────────────────────────
    //
    // Every test below drives `onIntent` and asserts on what the *user* ends up
    // seeing or owning. The defect this suite exists for: `handleTaskDelete`
    // announced a delete it never issued, so the snackbar asserted something
    // false and Undo restored a task that was still live.

    /** `archivedAt` is the only honest witness that a soft delete really happened. */
    private fun archivedAt(id: String): kotlin.time.Instant? = fakeRepo.tasks.value[id]?.archivedAt

    private fun withVm(block: suspend TestScope.(AgendaViewModel) -> Unit) = runTest {
        val vmScope = AutoCloseableCoroutineScope(coroutineContext + Job())
        try {
            block(createVm(vmScope))
        } finally {
            vmScope.close()
        }
    }

    @Test
    fun `deleting a task soft-deletes it`() = withVm { vm ->
        fakeRepo.seed(task("t1", "Doomed"))
        runCurrent()

        vm.onIntent(AgendaIntent.TaskDeleteClicked(TaskId("t1")))
        runCurrent()

        assertNotNull(archivedAt("t1"), "the delete must reach the repository, not just the snackbar")
    }

    @Test
    fun `a failed delete offers no undo and reports the failure`() = withVm { vm ->
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
    fun `undo restores the deleted task and clears the offer`() = withVm { vm ->
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
    fun `a failed reversal keeps the offer so the user can try again`() = withVm { vm ->
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
    fun `undo after the window expired restores nothing`() = withVm { vm ->
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
    fun `a new delete supersedes the previous offer`() = withVm { vm ->
        fakeRepo.seed(task("t1", "First"), task("t2", "Second"))
        runCurrent()

        vm.onIntent(AgendaIntent.TaskDeleteClicked(TaskId("t1")))
        runCurrent()
        assertEquals(TaskId("t1"), vm.pendingDelete.value?.taskId)

        vm.onIntent(AgendaIntent.TaskDeleteClicked(TaskId("t2")))
        runCurrent()
        assertEquals(TaskId("t2"), vm.pendingDelete.value?.taskId, "the newer delete owns the offer")

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
    fun `a superseded timer cannot clear the newer offer`() = withVm { vm ->
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
            vm.pendingDelete.value?.taskId,
            "the newer offer must survive the older window's expiry",
        )
    }

    @Test
    fun `deleting cancels the task's reminders`() = withVm { vm ->
        fakeRepo.seed(task("t1", "Doomed"))
        val cancelled = mutableListOf<TaskId>()
        val vmScope = AutoCloseableCoroutineScope(coroutineContext + Job())
        try {
            val scoped = AgendaViewModel(
                deps = AgendaDeps(
                    taskRepo = fakeRepo,
                    clock = FakeClock(now),
                    timeZone = TEST_TZ,
                    logger = Logger,
                    draftStore = FakeDraftStore(),
                    reminderScheduler = recordingScheduler(cancelled),
                    currentUser = fakeCurrentUser,
                    taskMutations = fakeTaskMutations,
                ),
                definition = AgendaPresets.Inbox,
                scope = vmScope,
            )
            runCurrent()

            scoped.onIntent(AgendaIntent.TaskDeleteClicked(TaskId("t1")))
            runCurrent()

            assertEquals(listOf(TaskId("t1")), cancelled, "a deleted task must not keep notifying")
            assertNotNull(archivedAt("t1"))
        } finally {
            vmScope.close()
        }
    }

    /**
     * `cancelByTask` returns `Unit` and hands back no `Reminder` spec, so a failed
     * delete cannot restore what it cancelled without a repository API change. The
     * accepted trade: a reminder never outlives its task; a lost reminder is
     * recoverable, a zombie reminder is not. Pinned so the choice stays visible.
     */
    @Test
    fun `a failed delete after a successful cancel leaves the task without its reminder`() = withVm { vm ->
        fakeRepo.seed(task("t1", "Doomed"))
        val cancelled = mutableListOf<TaskId>()
        val vmScope = AutoCloseableCoroutineScope(coroutineContext + Job())
        try {
            val scoped = AgendaViewModel(
                deps = AgendaDeps(
                    taskRepo = fakeRepo,
                    clock = FakeClock(now),
                    timeZone = TEST_TZ,
                    logger = Logger,
                    draftStore = FakeDraftStore(),
                    reminderScheduler = recordingScheduler(cancelled),
                    currentUser = fakeCurrentUser,
                    taskMutations = fakeTaskMutations,
                ),
                definition = AgendaPresets.Inbox,
                scope = vmScope,
            )
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
        } finally {
            vmScope.close()
        }
    }

    private fun recordingScheduler(sink: MutableList<TaskId>) = object : ReminderScheduler {
        override val isSupported: Boolean = true
        override suspend fun schedule(reminder: com.singularity.todo.feature.reminders.Reminder) {}
        override suspend fun cancel(id: ReminderId, userId: UserId) {}
        override suspend fun cancelByTask(taskId: TaskId, userId: UserId) {
            sink += taskId
        }
    }

    // ─────────────────────────── Selection ──────────────────────────────────────

    @Test
    fun `selection survives an agenda re-evaluation`() = withVm { vm ->
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
    fun `a selected task that leaves the agenda is dropped from the selection`() = withVm { vm ->
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
    fun `a failed toggleComplete reports the failure`() = withVm { vm ->
        fakeRepo.seed(task("t1", "Doomed"))
        runCurrent()

        val events = mutableListOf<AgendaUiEvent>()
        val collector = launch { vm.events.collect { events += it } }
        try {
            fakeRepo.toggleCompleteOverride = Result.failure(IllegalStateException("disk full"))
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
    fun `a failed togglePinned reports the failure`() = withVm { vm ->
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

    private fun loadedSelection(vm: AgendaViewModel): Set<TaskId> =
        assertIs<AgendaUiState.Loaded>(vm.stateFlow.value).selectedTaskIds
}
