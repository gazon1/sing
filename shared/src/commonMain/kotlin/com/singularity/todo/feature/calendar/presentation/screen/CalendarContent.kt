package com.singularity.todo.feature.calendar.presentation.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.singularity.todo.core.platform.todayInSystemZone
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
 * @param today Today's date (used for highlighting).
 */
@Composable
fun CalendarContent(
    state: CalendarUiState,
    onIntent: (CalendarIntent) -> Unit,
    onTaskClick: (LocalDate) -> Unit = {},
    today: LocalDate = todayInSystemZone(),
    modifier: Modifier = Modifier,
) {
    val loadedState: CalendarUiState.Loaded? = (state as? CalendarUiState.Loaded)

    Surface(modifier = modifier.fillMaxSize()) {
        if (loadedState == null) {
            // Loading state — could add a loading indicator here
            return@Surface
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
                    today = today,
                    onIntent = onIntent,
                )

                when (loadedState.viewMode) {
                    CalendarViewMode.MONTH -> MonthGridView(
                        monthAnchor = loadedState.anchor,
                        tasksByDate = loadedState.tasksByDate,
                        today = today,
                        selectedDate = loadedState.selectedDate,
                        onDayClick = { onIntent(CalendarIntent.DayClicked(it)) },
                        onTaskClick = { task -> onIntent(CalendarIntent.TaskClicked(task.id)) },
                        modifier = Modifier.fillMaxSize(),
                    )

                    else -> TimeGridView(
                        dates = loadedState.visibleDates,
                        tasksByDate = loadedState.tasksByDate,
                        today = today,
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
                    today = today,
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
