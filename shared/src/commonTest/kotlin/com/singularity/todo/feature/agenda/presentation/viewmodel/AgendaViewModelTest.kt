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
import com.singularity.todo.test.fakes.FakeTaskRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import com.singularity.todo.feature.tasks.presentation.state.DueDateOption
import com.singularity.todo.feature.tasks.presentation.state.TaskDraft
import com.singularity.todo.feature.agenda.domain.model.AgendaIntent
import kotlin.test.assertNotNull

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
                vm.state.value,
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
            val state = assertIs<AgendaUiState.Loaded>(vm.state.value)
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

            val state = assertIs<AgendaUiState.Loaded>(vm.state.value)
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
}
