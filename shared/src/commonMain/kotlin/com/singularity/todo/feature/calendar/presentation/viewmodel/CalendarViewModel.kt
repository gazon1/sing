package com.singularity.todo.feature.calendar.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.singularity.todo.core.platform.todayFlow
import com.singularity.todo.feature.calendar.domain.logic.CalendarTaskMapper
import com.singularity.todo.feature.calendar.domain.logic.firstDayOfMonth
import com.singularity.todo.feature.calendar.domain.logic.goNext
import com.singularity.todo.feature.calendar.domain.logic.goPrevious
import com.singularity.todo.feature.calendar.domain.logic.headerLabel
import com.singularity.todo.feature.calendar.domain.logic.lastDayOfMonth
import com.singularity.todo.feature.calendar.domain.logic.visibleRange
import com.singularity.todo.feature.calendar.domain.model.CalendarTaskUi
import com.singularity.todo.feature.calendar.domain.model.CalendarViewMode
import com.singularity.todo.feature.calendar.presentation.state.CalendarIntent
import com.singularity.todo.feature.calendar.presentation.state.CalendarUiEvent
import com.singularity.todo.feature.calendar.presentation.state.CalendarUiState
import com.singularity.todo.feature.tasks.domain.model.TaskFilter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlinx.datetime.plus

/**
 * ViewModel for the Calendar screen.
 *
 * Data flow:
 * 1. [currentUser.scopedUserId] + [_calendarState] → [flatMapLatest] → [watchTasks] with ByDateRange
 * 2. Tasks are mapped to [CalendarTaskUi] via [CalendarTaskMapper]
 * 3. Combined into [CalendarUiState.Loaded] → [state]
 * 4. One-shot events (task click → navigate) → [_events]
 *
 * [isRecurring] on [CalendarTaskUi] is always `false` for now — checking
 * [com.singularity.todo.feature.reminders.ReminderRepository] per-task would require
 * N additional queries. A future MR can add [watchRecurringTaskIds] to load all
 * recurring reminder IDs upfront.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CalendarViewModel(
    private val deps: CalendarDeps,
    initialDate: LocalDate,
    initialMode: CalendarViewMode = CalendarViewMode.MONTH,
    private val scope: CoroutineScope,
) : ViewModel() {

    /** Production constructor — Koin uses this. */
    constructor(
        deps: CalendarDeps,
        initialDate: LocalDate,
        initialMode: CalendarViewMode = CalendarViewMode.MONTH,
    ) : this(
        deps = deps,
        initialDate = initialDate,
        initialMode = initialMode,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    )

    /** Today's date, stable for the lifetime of this VM (captured at construction). */
    private val today: LocalDate by lazy {
        // runBlocking is safe here — the lazy initializer runs once during VM construction,
        // and blocking the calling thread for a few ms to read the clock is acceptable.
        runBlocking { deps.clock.todayFlow().first() }
    }

    /** Selection state — anchor date, view mode, mini panel open/closed. */
    private val _calendarState = MutableStateFlow(
        CalendarState(
            anchor = initialDate,
            viewMode = initialMode,
            isMiniOpen = false,
        ),
    )

    /** Events emitted to the UI layer (navigate to task detail, errors). */
    private val _events = MutableSharedFlow<CalendarUiEvent>()
    val events: Flow<CalendarUiEvent> = _events.asSharedFlow()

    /**
     * Main state — combines task flow with calendar selection state.
     * Starts as [CalendarUiState.Loading] and transitions to [CalendarUiState.Loaded]
     * once the first batch of tasks arrives.
     */
    val state: StateFlow<CalendarUiState> = deps.currentUser.scopedUserId
        .flatMapLatest { userId ->
            _calendarState.flatMapLatest { cal ->
                // Extend query window by ±7 days so neighbouring months are preloaded
                val from = firstDayOfMonth(cal.anchor).minus(7, DateTimeUnit.DAY)
                val to = lastDayOfMonth(cal.anchor).plus(7, DateTimeUnit.DAY)
                deps.taskRepo.watchTasks(userId, TaskFilter.ByDateRange(from, to))
                    .map { tasks ->
                        val tasksByDate = tasks
                            .map { CalendarTaskMapper.toCalendarTaskUi(it, today) }
                            .groupBy { it.date }
                        cal.toLoadedState(tasksByDate, today)
                    }
            }
        }
        .stateIn(
            scope,
            SharingStarted.WhileSubscribed(5_000),
            CalendarUiState.Loading,
        )

    /**
     * Processes a user intent.
     * NOTE: per AGENTS.md rule — no `.first()` re-fetch inside a debounced collector.
     * [flatMapLatest] above handles window changes reactively.
     */
    fun onIntent(intent: CalendarIntent) {
        when (intent) {
            is CalendarIntent.ViewModeChanged -> {
                _calendarState.value = _calendarState.value.copy(viewMode = intent.mode)
            }

            CalendarIntent.GoToday -> {
                _calendarState.value = _calendarState.value.copy(anchor = today)
            }

            CalendarIntent.GoNext -> {
                _calendarState.value = _calendarState.value.copy(
                    anchor = goNext(_calendarState.value.anchor, _calendarState.value.viewMode),
                )
            }

            CalendarIntent.GoPrevious -> {
                _calendarState.value = _calendarState.value.copy(
                    anchor = goPrevious(_calendarState.value.anchor, _calendarState.value.viewMode),
                )
            }

            is CalendarIntent.DayClicked -> {
                _calendarState.value = _calendarState.value.copy(
                    // When user clicks a day in month mode, switch to day view
                    viewMode = if (_calendarState.value.viewMode == CalendarViewMode.MONTH) {
                        CalendarViewMode.DAY
                    } else {
                        _calendarState.value.viewMode
                    },
                    anchor = intent.date,
                )
            }

            CalendarIntent.ToggleMiniCalendar -> {
                _calendarState.value = _calendarState.value.copy(
                    isMiniOpen = !_calendarState.value.isMiniOpen,
                )
            }

            CalendarIntent.DismissMiniCalendar -> {
                _calendarState.value = _calendarState.value.copy(isMiniOpen = false)
            }

            is CalendarIntent.TaskClicked -> {
                scope.launch {
                    _events.emit(CalendarUiEvent.NavigateToTask(intent.taskId))
                }
            }
        }
    }

    /** Internal calendar state (anchor, mode, mini panel). */
    private data class CalendarState(val anchor: LocalDate, val viewMode: CalendarViewMode, val isMiniOpen: Boolean) {
        fun toLoadedState(
            tasksByDate: Map<LocalDate, List<CalendarTaskUi>>,
            today: LocalDate,
        ): CalendarUiState.Loaded {
            val dates = visibleRange(anchor, viewMode)
            return CalendarUiState.Loaded(
                anchor = anchor,
                viewMode = viewMode,
                visibleDates = dates,
                tasksByDate = tasksByDate,
                today = today,
                selectedDate = anchor,
                isMiniCalendarOpen = isMiniOpen,
                headerLabel = headerLabel(anchor, viewMode),
                selectedTaskId = null,
            )
        }
    }
}
