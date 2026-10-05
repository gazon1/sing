package com.singularity.todo.feature.calendar.presentation.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.singularity.todo.feature.calendar.domain.logic.YearMonth
import com.singularity.todo.feature.calendar.domain.logic.headerLabel
import com.singularity.todo.feature.calendar.domain.logic.toLocalDate
import com.singularity.todo.feature.calendar.domain.logic.yearMonthForPage
import com.singularity.todo.feature.calendar.domain.model.CalendarViewMode
import com.singularity.todo.feature.calendar.presentation.components.calendar.CalendarTopBar
import com.singularity.todo.feature.calendar.presentation.components.calendar.MiniCalendarPanel
import com.singularity.todo.feature.calendar.presentation.components.calendar.MonthGridView
import com.singularity.todo.feature.calendar.presentation.components.calendar.TimeGridView
import com.singularity.todo.feature.calendar.presentation.state.CalendarIntent
import com.singularity.todo.feature.calendar.presentation.state.CalendarUiState
import kotlinx.datetime.LocalDate

/**
 * Slot-API content composable for the Calendar screen.
 *
 * Exists separately from [CalendarScreen] so it can be used in @Preview
 * without Koin, by passing manually-constructed state and intents.
 *
 * @param state Current UI state.
 * @param onIntent Dispatch user intent to the ViewModel.
 * @param onTaskClick Called when a task chip is clicked — used by the caller
 *                    to handle navigation (e.g. via [CalendarUiEvent.NavigateToTask]).
 *
 * `today` is read from [state] rather than taken as a parameter. It used to be a
 * parameter with a system-clock default, so the screen passed one value and the state
 * carried another — two sources for one fact, and the screen's copy was the one no test
 * could set. The ViewModel computes it from an injected `Clock` and `TimeZoneProvider`,
 * so the state is already the right place to read it from.
 * @param pagerState Optional [PagerState] — hoist to control from outside (e.g.
 *   tests, custom navigation). When null, a default state is created internally.
 */
@Composable
fun CalendarContent(
    state: CalendarUiState,
    onIntent: (CalendarIntent) -> Unit,
    onTaskClick: (LocalDate) -> Unit = {},
    modifier: Modifier = Modifier,
    pagerState: PagerState? = null,
) {
    val loadedState: CalendarUiState.Loaded? = (state as? CalendarUiState.Loaded)

    Surface(modifier = modifier.fillMaxSize()) {
        if (loadedState == null) {
            // Loading state — could add a loading indicator here
            return@Surface
        }

        // Top bar derives its header label live from the current pager page —
        // this avoids a VM round-trip per swipe frame and ensures the label
        // updates instantly as the user drags, not just on settle.
        val liveMonth: YearMonth? = pagerState?.let { ps ->
            val anchorYm = remember(loadedState.anchor) {
                YearMonth(loadedState.anchor.year, loadedState.anchor.month)
            }
            yearMonthForPage(anchorYm, ps.currentPage)
        }
        val liveHeaderLabel = liveMonth?.toLocalDate()?.let { anchorDate ->
            headerLabel(anchorDate, loadedState.viewMode)
        }

        Row(modifier = Modifier.fillMaxSize()) {
            // Main calendar area: Column carries the weight so child views fill height
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            ) {
                CalendarTopBar(
                    state = loadedState,
                    today = loadedState.today,
                    onIntent = onIntent,
                    headerLabelOverride = liveHeaderLabel,
                )

                when (loadedState.viewMode) {
                    CalendarViewMode.MONTH -> MonthGridView(
                        monthAnchor = loadedState.anchor,
                        today = loadedState.today,
                        selectedDate = loadedState.selectedDate,
                        tasksByDate = loadedState.tasksByDate,
                        onDayClick = { onIntent(CalendarIntent.DayClicked(it)) },
                        onTaskClick = { task -> onIntent(CalendarIntent.TaskClicked(task.id)) },
                        onMonthPageChanged = { month -> onIntent(CalendarIntent.MonthPageChanged(month)) },
                        onEmptyCellLongPress = { date -> onIntent(CalendarIntent.EmptyCellLongPressed(date)) },
                        modifier = Modifier.fillMaxSize(),
                        pagerState = pagerState ?: rememberPagerState(initialPage = 120) { 240 },
                    )

                    else -> TimeGridView(
                        dates = loadedState.visibleDates,
                        tasksByDate = loadedState.tasksByDate,
                        today = loadedState.today,
                        selectedTaskId = loadedState.selectedTaskId,
                        onTaskClick = { task -> onIntent(CalendarIntent.TaskClicked(task.id)) },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            // Mini calendar side panel (slides in from right)
            AnimatedVisibility(
                visible = loadedState.isMiniCalendarOpen,
                enter = slideInHorizontally(initialOffsetX = { it }),
                exit = slideOutHorizontally(targetOffsetX = { it }),
            ) {
                MiniCalendarPanel(
                    monthAnchor = loadedState.anchor,
                    selectedDate = loadedState.selectedDate,
                    today = loadedState.today,
                    onDateSelected = { date ->
                        onIntent(CalendarIntent.DayClicked(date))
                        onIntent(CalendarIntent.DismissMiniCalendar)
                    },
                    onClose = { onIntent(CalendarIntent.DismissMiniCalendar) },
                )
            }
        }
    }
}
