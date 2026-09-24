package com.singularity.todo.feature.calendar.presentation.viewmodel

import androidx.lifecycle.ViewModel
import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.ui.state.updateState
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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlinx.datetime.plus

/**
 * ViewModel for the Calendar screen.
 *
 * Data flow:
 * 1. [_calendarState] + [ReminderRepository.observeRecurringTaskIds] → [combine] → [flatMapLatest]
 * 2. [taskRepo.observeByFilter] with ByDateRange → [CalendarTaskMapper] (enriched with recurring IDs)
 * 3. Mapped into [CalendarUiState.Loaded] → [state]
 * 4. One-shot events (task click → navigate) → [_events]
 *
 * [CalendarTaskUi.isRecurring] is set by looking up the task ID in the
 * [ReminderRepository.observeRecurringTaskIds] set — loaded once per user switch
 * rather than per-task.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CalendarViewModel(
    private val deps: CalendarDeps,
    initialDate: LocalDate,
    initialMode: CalendarViewMode = CalendarViewMode.MONTH,
    private val scope: AutoCloseableCoroutineScope = AutoCloseableCoroutineScope(),
) : ViewModel() {

    init {
        addCloseable(scope)
    }

    /** Today's date, stable for the lifetime of this VM (captured at construction). */
    private val today: LocalDate = deps.today

    /** Selection state — anchor date, view mode, mini panel open/closed. */
    private val _calendarState = MutableStateFlow(
        CalendarState(
            anchor = initialDate,
            viewMode = initialMode,
            isMiniOpen = false,
        ),
    )

    /** Events emitted to the UI layer (navigate to task detail, errors). */
    private val _events = Channel<CalendarUiEvent>(Channel.BUFFERED)
    val events: Flow<CalendarUiEvent> = _events.receiveAsFlow()

    private val _state = MutableStateFlow<CalendarUiState>(CalendarUiState.Loading)
    val state: StateFlow<CalendarUiState> = _state

    init {
        scope.launch {
            combine(
                _calendarState,
                deps.reminderRepo.observeRecurringTaskIds(),
            ) { cal, recurringIds ->
                cal to recurringIds
            }.flatMapLatest { (cal, recurringIds) ->
                // Extend query window by ±7 days so neighbouring months are preloaded
                val from = firstDayOfMonth(cal.anchor).minus(7, DateTimeUnit.DAY)
                val to = lastDayOfMonth(cal.anchor).plus(7, DateTimeUnit.DAY)
                deps.taskRepo.observeByFilter(TaskFilter.ByDateRange(from, to))
                    .map { tasks ->
                        val tasksByDate = tasks
                            .map { task -> CalendarTaskMapper.toCalendarTaskUi(task, today, recurringIds.contains(task.id)) }
                            .groupBy { it.date }
                        cal.toLoadedState(tasksByDate, today)
                    }
            }.collect { _state.value = it }
        }
    }

    /**
     * Processes a user intent.
     * NOTE: per AGENTS.md rule — no `.first()` re-fetch inside a debounced collector.
     * [flatMapLatest] above handles window changes reactively.
     */
    fun onIntent(intent: CalendarIntent) {
        when (intent) {
            is CalendarIntent.ViewModeChanged -> {
                _calendarState.updateState { it.copy(viewMode = intent.mode) }
            }

            CalendarIntent.GoToday -> {
                _calendarState.updateState { it.copy(anchor = today) }
            }

            CalendarIntent.GoNext -> {
                _calendarState.updateState {
                    it.copy(anchor = goNext(it.anchor, it.viewMode))
                }
            }

            CalendarIntent.GoPrevious -> {
                _calendarState.updateState {
                    it.copy(anchor = goPrevious(it.anchor, it.viewMode))
                }
            }

            is CalendarIntent.DayClicked -> {
                _calendarState.updateState {
                    it.copy(
                        viewMode = if (it.viewMode == CalendarViewMode.MONTH) {
                            CalendarViewMode.DAY
                        } else {
                            it.viewMode
                        },
                        anchor = intent.date,
                    )
                }
            }

            CalendarIntent.ToggleMiniCalendar -> {
                _calendarState.updateState { it.copy(isMiniOpen = !it.isMiniOpen) }
            }

            CalendarIntent.DismissMiniCalendar -> {
                _calendarState.updateState { it.copy(isMiniOpen = false) }
            }

            is CalendarIntent.TaskClicked -> {
                scope.launch {
                    _events.trySend(CalendarUiEvent.NavigateToTask(intent.taskId))
                }
            }

            is CalendarIntent.MonthPageChanged -> {
                // Dedupe against current anchor: a swipe that settles on the same
                // month it started on must not cancel and re-subscribe the Room flow.
                val newAnchor = LocalDate(intent.month.year, intent.month.month, 1)
                if (newAnchor != _calendarState.value.anchor) {
                    _calendarState.updateState { it.copy(anchor = newAnchor) }
                }
            }

            is CalendarIntent.EmptyCellLongPressed -> {
                scope.launch {
                    _events.trySend(CalendarUiEvent.ShowCreateTaskSheet(intent.date))
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
