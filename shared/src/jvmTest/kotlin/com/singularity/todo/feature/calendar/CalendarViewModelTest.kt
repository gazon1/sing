@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.feature.calendar

import co.touchlab.kermit.Logger
import com.singularity.todo.core.coroutines.testScope
import com.singularity.todo.core.draft.FakeDraftStore
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
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.Month
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
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
@Tag("fast")
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
            draftStore = FakeDraftStore(),
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
        completedAt: kotlin.time.Instant? = null,
    ) {
        fakeTaskRepo.seed(
            Task(
                id = TaskId.fromString(id),
                title = title,
                userId = testUserId,
                dueDate = dueDate,
                dueTime = dueTime,
                completedAt = completedAt,
                createdAt = kotlin.time.Instant.fromEpochMilliseconds(0),
                updatedAt = kotlin.time.Instant.fromEpochMilliseconds(0),
            ),
        )
    }

    // ─── date selection mode ───────────────────────────────────────────────
    //
    // The mode picks several days for one task. A long press on a day that
    // holds tasks enters it; a tap then picks a day instead of opening it.
    //
    // The selection is asserted on [CalendarViewModel.dateSelection] rather than
    // on the screen state: the screen state only exists once the calendar's data
    // has loaded, and this harness never gets there — see the file's header. The
    // picking does not depend on the data, so it is not tested through it.

    @Test
    fun `entering the selection mode selects the day that was pressed`() = runTest {
        val vm = createVm(scope = backgroundScope, initialDate = anchor, initialMode = CalendarViewMode.MONTH)

        vm.onIntent(CalendarIntent.EnterDateSelectionMode(LocalDate(2026, Month.SEPTEMBER, 16)))

        assertTrue(vm.dateSelection.value.isActive, "the mode is on")
        assertEquals(setOf(LocalDate(2026, Month.SEPTEMBER, 16)), vm.dateSelection.value.dates)
    }

    @Test
    fun `a tap in the selection mode picks a day instead of opening it`() = runTest {
        val vm = createVm(scope = backgroundScope, initialDate = anchor, initialMode = CalendarViewMode.MONTH)
        vm.onIntent(CalendarIntent.EnterDateSelectionMode(LocalDate(2026, Month.SEPTEMBER, 16)))

        vm.onIntent(CalendarIntent.DayClicked(LocalDate(2026, Month.SEPTEMBER, 18)))

        assertEquals(
            setOf(LocalDate(2026, Month.SEPTEMBER, 16), LocalDate(2026, Month.SEPTEMBER, 18)),
            vm.dateSelection.value.dates,
        )
    }

    @Test
    fun `a second tap on the same day unpicks it`() = runTest {
        val vm = createVm(scope = backgroundScope, initialDate = anchor, initialMode = CalendarViewMode.MONTH)
        vm.onIntent(CalendarIntent.EnterDateSelectionMode(LocalDate(2026, Month.SEPTEMBER, 16)))

        vm.onIntent(CalendarIntent.DayClicked(LocalDate(2026, Month.SEPTEMBER, 16)))

        assertTrue(vm.dateSelection.value.dates.isEmpty(), "a day that is tapped twice is not picked")
    }

    @Test
    fun `leaving the selection mode forgets the picked days`() = runTest {
        val vm = createVm(scope = backgroundScope, initialDate = anchor, initialMode = CalendarViewMode.MONTH)
        vm.onIntent(CalendarIntent.EnterDateSelectionMode(LocalDate(2026, Month.SEPTEMBER, 16)))

        vm.onIntent(CalendarIntent.ExitDateSelectionMode)

        assertFalse(vm.dateSelection.value.isActive)
        assertTrue(vm.dateSelection.value.dates.isEmpty())
    }

    // ─── initial state ─────────────────────────────────────────────────────

    @Test
    fun `initial state is Loading`() = runTest {
        val vm = createVm(scope = backgroundScope)
        assertIs<CalendarUiState.Loading>(vm.stateFlow.value)
    }

    // ─── intent handlers (smoke tests — no crash) ─────────────────────────

    @Test
    fun `ViewModeChanged changes mode`() = runTest {
        val vm = createVm(scope = backgroundScope, initialMode = CalendarViewMode.MONTH)
        vm.onIntent(CalendarIntent.ViewModeChanged(CalendarViewMode.WEEK))
        assertEquals(CalendarViewMode.WEEK, vm.calendarState.value.viewMode)
    }

    @Test
    fun `GoNext moves anchor forward one month`() = runTest {
        val vm = createVm(scope = backgroundScope, initialDate = anchor, initialMode = CalendarViewMode.MONTH)
        vm.onIntent(CalendarIntent.GoNext)
        assertEquals(LocalDate(2026, Month.OCTOBER, 1), vm.calendarState.value.anchor)
    }

    @Test
    fun `GoPrevious moves anchor back one month`() = runTest {
        val vm = createVm(scope = backgroundScope, initialDate = anchor, initialMode = CalendarViewMode.MONTH)
        vm.onIntent(CalendarIntent.GoPrevious)
        assertEquals(LocalDate(2026, Month.AUGUST, 1), vm.calendarState.value.anchor)
    }

    @Test
    fun `GoNext moves anchor forward one day in DAY mode`() = runTest {
        val dayAnchor = LocalDate(2026, Month.SEPTEMBER, 16)
        val vm = createVm(scope = backgroundScope, initialDate = dayAnchor, initialMode = CalendarViewMode.DAY)
        vm.onIntent(CalendarIntent.GoNext)
        assertEquals(LocalDate(2026, Month.SEPTEMBER, 17), vm.calendarState.value.anchor)
    }

    @Test
    fun `GoToday resets anchor to today`() = runTest {
        val otherDate = LocalDate(2025, Month.JANUARY, 1)
        val vm = createVm(scope = backgroundScope, initialDate = otherDate, initialMode = CalendarViewMode.MONTH)
        vm.onIntent(CalendarIntent.GoToday)
        assertEquals(anchor, vm.calendarState.value.anchor)
    }

    @Test
    fun `DayClicked in MONTH mode switches to DAY on clicked date`() = runTest {
        val vm = createVm(scope = backgroundScope, initialMode = CalendarViewMode.MONTH)
        vm.onIntent(CalendarIntent.DayClicked(LocalDate(2026, Month.SEPTEMBER, 22)))
        assertEquals(CalendarViewMode.DAY, vm.calendarState.value.viewMode)
        assertEquals(LocalDate(2026, Month.SEPTEMBER, 22), vm.calendarState.value.anchor)
    }

    @Test
    fun `DayClicked in DAY mode moves anchor to clicked date`() = runTest {
        val vm = createVm(scope = backgroundScope, initialMode = CalendarViewMode.DAY)
        vm.onIntent(CalendarIntent.DayClicked(LocalDate(2026, Month.SEPTEMBER, 22)))
        assertEquals(LocalDate(2026, Month.SEPTEMBER, 22), vm.calendarState.value.anchor)
    }

    @Test
    fun `ToggleMiniCalendar toggles open state`() = runTest {
        val vm = createVm(scope = backgroundScope)
        vm.onIntent(CalendarIntent.ToggleMiniCalendar)
        assertTrue(vm.calendarState.value.isMiniOpen)
        vm.onIntent(CalendarIntent.ToggleMiniCalendar)
        assertFalse(vm.calendarState.value.isMiniOpen)
    }

    @Test
    fun `DismissMiniCalendar closes mini calendar`() = runTest {
        val vm = createVm(scope = backgroundScope)
        vm.onIntent(CalendarIntent.ToggleMiniCalendar) // open first
        vm.onIntent(CalendarIntent.DismissMiniCalendar) // then dismiss
        assertFalse(vm.calendarState.value.isMiniOpen)
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
            completedAt = kotlin.time.Instant.fromEpochMilliseconds(1),
        )
        val tasks = fakeTaskRepo.tasks.first()
        assertNotNull(tasks["t1"]?.completedAt)
    }
}
