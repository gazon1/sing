package com.singularity.todo.feature.calendar.presentation.viewmodel

import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.ui.MviViewModel
import com.singularity.todo.feature.calendar.domain.logic.CalendarTaskMapper
import com.singularity.todo.feature.calendar.domain.logic.firstDayOfMonth
import com.singularity.todo.feature.calendar.domain.logic.goNext
import com.singularity.todo.feature.calendar.domain.logic.goPrevious
import com.singularity.todo.feature.calendar.domain.logic.headerLabel
import com.singularity.todo.feature.calendar.domain.logic.lastDayOfMonth
import com.singularity.todo.feature.calendar.domain.logic.visibleRange
import com.singularity.todo.feature.calendar.domain.model.CalendarTaskUi
import com.singularity.todo.feature.calendar.domain.model.CalendarDateSelection
import com.singularity.todo.feature.calendar.domain.model.CalendarViewMode
import com.singularity.todo.feature.calendar.presentation.state.CalendarIntent
import com.singularity.todo.feature.calendar.presentation.state.CalendarUiEvent
import com.singularity.todo.feature.calendar.presentation.state.CalendarUiState
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.collections.immutable.toImmutableSet
import com.singularity.todo.feature.tasks.domain.model.TaskDraftKeys
import com.singularity.todo.feature.tasks.domain.model.TaskFilter
import com.singularity.todo.feature.tasks.presentation.state.DueDateOption
import com.singularity.todo.feature.tasks.presentation.state.TaskDraft
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import com.singularity.todo.core.observability.CrashReportingPort
import com.singularity.todo.core.observability.NoOpCrashReportingPort
import com.singularity.todo.core.observability.reportingScope
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
 * 4. One-shot events (task click → navigate) → [emit]
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
    crashReporter: CrashReportingPort = NoOpCrashReportingPort(),
    scope: AutoCloseableCoroutineScope = reportingScope(crashReporter),
) : MviViewModel<CalendarUiState, CalendarIntent, CalendarUiEvent>(
        initialState = CalendarUiState.Loading,
        crashReporter = crashReporter,
        scope = scope,
    ) {

    /** Today's date, stable for the lifetime of this VM (captured at construction). */
    private val today: LocalDate = deps.today

    /**
     * The days picked for one task.
     *
     * The single source of the selection: the screen projects it into
     * [CalendarUiState.Loaded], and the intents write it. It is a flow of its
     * own because the picking does not depend on the calendar's data — a user
     * can pick days before the tasks of the month have loaded, and a rule that
     * only worked once the data arrived would be a rule that sometimes did
     * nothing.
     */
    private val _dateSelection = MutableStateFlow(CalendarDateSelection())

    /** The days picked for one task, readable whether or not the screen has loaded. */
    val dateSelection: StateFlow<CalendarDateSelection> = _dateSelection.asStateFlow()

    /** Selection state — anchor date, view mode, mini panel open/closed. */
    private val _calendarState = MutableStateFlow(
        CalendarState(
            anchor = initialDate,
            viewMode = initialMode,
            isMiniOpen = false,
        ),
    )

    init {
        vmScope.launch {
            combine(
                _calendarState,
                _dateSelection,
                deps.reminderRepo.observeRecurringTaskIds(),
            ) { cal, selection, recurringIds ->
                Triple(cal, selection, recurringIds)
            }.flatMapLatest { (cal, selection, recurringIds) ->
                // Extend query window by ±7 days so neighbouring months are preloaded
                val from = firstDayOfMonth(cal.anchor).minus(7, DateTimeUnit.DAY)
                val to = lastDayOfMonth(cal.anchor).plus(7, DateTimeUnit.DAY)
                deps.taskRepo.observeByFilter(TaskFilter.ByDateRange(from, to))
                    .map { tasks ->
                        val tasksByDate = tasks
                            .map { task ->
                                CalendarTaskMapper.toCalendarTaskUi(
                                    task,
                                    today,
                                    recurringIds.contains(task.id),
                                )
                            }
                            .groupBy { it.date }
                        cal.toLoadedState(tasksByDate, today).copy(
                            selectedDates = selection.dates.toImmutableSet(),
                            isDateSelectionMode = selection.isActive,
                        )
                    }
            }.collect { setState(it) }
        }
    }

    /**
     * Processes a user intent.
     * NOTE: per AGENTS.md rule — no `.first()` re-fetch inside a debounced collector.
     * [flatMapLatest] above handles window changes reactively.
     */
    override fun onIntent(intent: CalendarIntent) {
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
                val current = _calendarState.value
                // In the selection mode a tap picks a day; it does not open it.
                // Without this the first tap of a multi-selection would throw the
                // user out of the mode they just entered.
                if (_dateSelection.value.isActive) {
                    _dateSelection.value = _dateSelection.value.toggle(intent.date)
                } else {
                    _calendarState.value = current.copy(
                        viewMode = if (current.viewMode == CalendarViewMode.MONTH) {
                            CalendarViewMode.DAY
                        } else {
                            current.viewMode
                        },
                        anchor = intent.date,
                    )
                }
            }

            CalendarIntent.ToggleMiniCalendar -> {
                _calendarState.value = _calendarState.value.copy(isMiniOpen = !_calendarState.value.isMiniOpen)
            }

            CalendarIntent.DismissMiniCalendar -> {
                _calendarState.value = _calendarState.value.copy(isMiniOpen = false)
            }

            is CalendarIntent.TaskClicked -> {
                vmScope.launch {
                    emit(CalendarUiEvent.NavigateToTask(intent.taskId))
                }
            }

            is CalendarIntent.MonthPageChanged -> {
                // Dedupe against current anchor: a swipe that settles on the same
                // month it started on must not cancel and re-subscribe the Room flow.
                val newAnchor = LocalDate(intent.month.year, intent.month.month, 1)
                if (newAnchor != _calendarState.value.anchor) {
                    _calendarState.value = _calendarState.value.copy(anchor = newAnchor)
                }
            }

            is CalendarIntent.EmptyCellLongPressed -> {
                vmScope.launch {
                    emit(CalendarUiEvent.ShowCreateTaskSheet(intent.date))
                }
            }

            is CalendarIntent.EnterDateSelectionMode -> {
                _dateSelection.value = _dateSelection.value.enter(intent.date)
            }

            is CalendarIntent.ToggleDateSelection -> {
                _dateSelection.value = _dateSelection.value.toggle(intent.date)
            }

            CalendarIntent.ExitDateSelectionMode -> {
                _dateSelection.value = _dateSelection.value.exit()
            }

            CalendarIntent.CreateTaskOnSelectedDates -> createTaskOnSelectedDates()
        }
    }

    /**
     * Writes the picked days into a draft and asks for the editor.
     *
     * The draft is the channel: the calendar does not create the task, and the
     * editor does not know where the days came from. Writing a draft and
     * pointing the editor at it means the task is created by exactly the same
     * code as a task created from the agenda.
     */
    private fun createTaskOnSelectedDates() = vmScope.launch {
        val dates = _dateSelection.value.dates.sorted()
        if (dates.isEmpty()) return@launch
        val primary = dates.first()
        val draft = TaskDraft(
            dueDate = DueDateOption.Custom(primary, primary.toString()),
            extraDueDates = dates.drop(1),
        )
        deps.draftStore.save(TaskDraftKeys.CALENDAR_MULTI_DATE, draft, TaskDraft.serializer())
        emit(CalendarUiEvent.NavigateToCreateWithDates(TaskDraftKeys.CALENDAR_MULTI_DATE))
    }

    /** Internal calendar state (anchor, mode, mini panel, date selection). */
    private data class CalendarState(val anchor: LocalDate, val viewMode: CalendarViewMode, val isMiniOpen: Boolean) {
        fun toLoadedState(
            tasksByDate: Map<LocalDate, List<CalendarTaskUi>>,
            today: LocalDate,
        ): CalendarUiState.Loaded {
            val dates = visibleRange(anchor, viewMode)
            return CalendarUiState.Loaded(
                anchor = anchor,
                viewMode = viewMode,
                visibleDates = dates.toImmutableList(),
                tasksByDate = tasksByDate.mapValues { (_, tasks) -> tasks.toImmutableList() }.toImmutableMap(),
                today = today,
                selectedDate = anchor,
                isMiniCalendarOpen = isMiniOpen,
                headerLabel = headerLabel(anchor, viewMode),
                selectedTaskId = null,
            )
        }
    }
}
