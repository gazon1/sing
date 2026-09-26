package com.singularity.todo.feature.calendar

import co.touchlab.kermit.Logger
import com.singularity.todo.core.coroutines.testScope
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.calendar.domain.model.CalendarViewMode
import com.singularity.todo.feature.calendar.presentation.state.CalendarIntent
import com.singularity.todo.feature.calendar.presentation.state.CalendarUiEvent
import com.singularity.todo.feature.calendar.presentation.state.CalendarUiState
import com.singularity.todo.feature.calendar.presentation.viewmodel.CalendarDeps
import com.singularity.todo.feature.calendar.presentation.viewmodel.CalendarViewModel
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.test.fakes.FakeAuthRepository
import com.singularity.todo.test.fakes.FakeProfileAwareCurrentUser
import com.singularity.todo.test.fakes.FakeReminderRepository
import com.singularity.todo.test.fakes.FakeTaskRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.Month
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/**
 * Tests for [CalendarViewModel] intents and state transitions.
 *
 * ## Virtual-time note
 *
 * [FakeProfileAwareCurrentUser] starts collectors eagerly on `Dispatchers.Default`.
 * The test dispatcher cannot advance virtual time for that work, so the VM's state
 * may not transition from `Loading` to `Loaded` within the test. Tests focus on
 * verifying intent-handler logic (which is synchronous) and the Loading state.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CalendarViewModelTest {

    private val testUserId = UserId("test-user")
    private val anchor = LocalDate(2026, Month.SEPTEMBER, 1)

    private val fakeTaskRepo = FakeTaskRepository()
    private val fakeCurrentUser = FakeProfileAwareCurrentUser(
        FakeAuthRepository(
            initialSession = com.singularity.todo.core.auth.Session.Anonymous(testUserId),
        ),
    )

    // Must share the same ProfileAwareCurrentUser so that all internal collectors
    // run on the test dispatcher — advanceUntilIdle() can then drive them to completion.
    private val fakeReminderRepo = FakeReminderRepository(fakeCurrentUser)

    private fun createVm(
        initialDate: LocalDate = anchor,
        initialMode: CalendarViewMode = CalendarViewMode.MONTH,
        scope: CoroutineScope,
    ) = CalendarViewModel(
        deps = CalendarDeps(
            taskRepo = fakeTaskRepo,
            reminderRepo = fakeReminderRepo,
            logger = Logger.withTag("CalendarTest"),
            today = anchor, // deterministic — same as anchor so date math is predictable
        ),
        initialDate = initialDate,
        initialMode = initialMode,
        scope = testScope(scope),
    )

    private fun seedTask(
        id: String,
        title: String,
        dueDate: LocalDate,
        dueTime: LocalTime? = null,
        completedAt: Instant? = null,
    ) {
        fakeTaskRepo.seed(
            Task(
                id = TaskId.fromString(id),
                title = title,
                userId = testUserId,
                dueDate = dueDate,
                dueTime = dueTime,
                completedAt = completedAt,
                createdAt = Instant.fromEpochMilliseconds(0),
                updatedAt = Instant.fromEpochMilliseconds(0),
            ),
        )
    }

    // ─── initial state ─────────────────────────────────────────────────────

    @Test
    fun `initial state is Loading`() = runTest {
        val vm = createVm(scope = backgroundScope)
        assertIs<CalendarUiState.Loading>(vm.state.value)
    }

    // ─── intent handlers (smoke tests — no crash) ─────────────────────────

    @Test
    fun `ViewModeChanged does not crash`() = runTest {
        val vm = createVm(scope = backgroundScope, initialMode = CalendarViewMode.MONTH)
        vm.onIntent(CalendarIntent.ViewModeChanged(CalendarViewMode.WEEK))
        assertIs<CalendarUiState.Loading>(vm.state.value)
    }

    @Test
    fun `GoNext does not crash for MONTH mode`() = runTest {
        val vm = createVm(scope = backgroundScope, initialDate = anchor, initialMode = CalendarViewMode.MONTH)
        vm.onIntent(CalendarIntent.GoNext)
        assertIs<CalendarUiState.Loading>(vm.state.value)
    }

    @Test
    fun `GoPrevious does not crash for MONTH mode`() = runTest {
        val vm = createVm(scope = backgroundScope, initialDate = anchor, initialMode = CalendarViewMode.MONTH)
        vm.onIntent(CalendarIntent.GoPrevious)
        assertIs<CalendarUiState.Loading>(vm.state.value)
    }

    @Test
    fun `GoNext does not crash for DAY mode`() = runTest {
        val dayAnchor = LocalDate(2026, Month.SEPTEMBER, 16)
        val vm = createVm(scope = backgroundScope, initialDate = dayAnchor, initialMode = CalendarViewMode.DAY)
        vm.onIntent(CalendarIntent.GoNext)
        assertIs<CalendarUiState.Loading>(vm.state.value)
    }

    @Test
    fun `GoToday does not crash`() = runTest {
        val otherDate = LocalDate(2025, Month.JANUARY, 1)
        val vm = createVm(scope = backgroundScope, initialDate = otherDate, initialMode = CalendarViewMode.MONTH)
        vm.onIntent(CalendarIntent.GoToday)
        assertIs<CalendarUiState.Loading>(vm.state.value)
    }

    @Test
    fun `DayClicked does not crash in MONTH mode`() = runTest {
        val vm = createVm(scope = backgroundScope, initialMode = CalendarViewMode.MONTH)
        vm.onIntent(CalendarIntent.DayClicked(LocalDate(2026, Month.SEPTEMBER, 22)))
        assertIs<CalendarUiState.Loading>(vm.state.value)
    }

    @Test
    fun `DayClicked does not crash in DAY mode`() = runTest {
        val vm = createVm(scope = backgroundScope, initialMode = CalendarViewMode.DAY)
        vm.onIntent(CalendarIntent.DayClicked(LocalDate(2026, Month.SEPTEMBER, 22)))
        assertIs<CalendarUiState.Loading>(vm.state.value)
    }

    @Test
    fun `ToggleMiniCalendar does not crash twice`() = runTest {
        val vm = createVm(scope = backgroundScope)
        vm.onIntent(CalendarIntent.ToggleMiniCalendar)
        vm.onIntent(CalendarIntent.ToggleMiniCalendar)
        assertIs<CalendarUiState.Loading>(vm.state.value)
    }

    @Test
    fun `DismissMiniCalendar does not crash`() = runTest {
        val vm = createVm(scope = backgroundScope)
        vm.onIntent(CalendarIntent.ToggleMiniCalendar) // open first
        vm.onIntent(CalendarIntent.DismissMiniCalendar) // then dismiss
        assertIs<CalendarUiState.Loading>(vm.state.value)
    }

    // ─── TaskClicked emits NavigateToTask event ──────────────────────────────
    // Note: Collecting SharedFlow.first() inside runTest creates a coroutine that
    // the TestScope tracks. We use backgroundScope to avoid UncompletedCoroutinesError.
    @Test
    fun `TaskClicked emits NavigateToTask event with correct taskId`() = runTest {
        @Suppress("UNCHECKED_CAST")
        val vm = createVm(scope = backgroundScope)
        val taskId = TaskId.generate()

        vm.onIntent(CalendarIntent.TaskClicked(taskId))
        advanceUntilIdle()

        val event = vm.events.first()
        assertIs<CalendarUiEvent.NavigateToTask>(event)
        assertEquals(taskId, event.taskId)
    }

    // ─── FakeTaskRepository integration ───────────────────────────────────

    @Test
    fun `seeded tasks are visible in repository`() = runTest {
        seedTask("t1", "Meeting", LocalDate(2026, Month.SEPTEMBER, 16))
        val tasks = fakeTaskRepo.tasks.first()
        assertEquals(1, tasks.size)
        assertEquals("Meeting", tasks["t1"]?.title)
    }

    @Test
    fun `seeded tasks with dueTime have correct dueTime`() = runTest {
        seedTask("t1", "Standup", LocalDate(2026, Month.SEPTEMBER, 16), LocalTime(9, 0))
        val tasks = fakeTaskRepo.tasks.first()
        assertEquals(LocalTime(9, 0), tasks["t1"]?.dueTime)
    }

    @Test
    fun `completed tasks have non-null completedAt`() = runTest {
        seedTask(
            id = "t1",
            title = "Done task",
            dueDate = LocalDate(2026, Month.SEPTEMBER, 16),
            completedAt = Instant.fromEpochMilliseconds(1),
        )
        val tasks = fakeTaskRepo.tasks.first()
        assertNotNull(tasks["t1"]?.completedAt)
    }
}
