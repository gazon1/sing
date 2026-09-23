package com.singularity.todo.feature.calendar.presentation.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.singularity.todo.core.platform.todayInSystemZone
import com.singularity.todo.feature.calendar.domain.model.CalendarViewMode
import com.singularity.todo.feature.calendar.presentation.nav.LocalCalendarNavigator
import com.singularity.todo.feature.calendar.presentation.state.CalendarUiEvent
import com.singularity.todo.feature.calendar.presentation.theme.ProvideCalendarPalette
import com.singularity.todo.feature.calendar.presentation.viewmodel.CalendarViewModel
import kotlinx.datetime.LocalDate
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * Root composable for the Calendar screen.
 *
 * Registered as a nav entry via [com.singularity.todo.feature.calendar.presentation.nav.CalendarNavGraph].
 * Uses [LocalCalendarNavigator] provided by the nav graph to handle task-click navigation.
 *
 * @param anchorDate The date to anchor the calendar view on (1st of the displayed month).
 */
@Composable
fun CalendarScreen(anchorDate: LocalDate, modifier: Modifier = Modifier) {
    val vm: CalendarViewModel = koinViewModel {
        parametersOf(anchorDate.year, anchorDate.monthNumber, CalendarViewMode.MONTH)
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val today = todayInSystemZone()

    // Obtain navigator from the nav graph context
    val navigator = LocalCalendarNavigator.current

    // Handle NavigateToTask events by opening the task in the outer tasks graph
    LaunchedEffect(Unit) {
        vm.events.collect { event ->
            when (event) {
                is CalendarUiEvent.NavigateToTask -> navigator.openTask(event.taskId)
                is CalendarUiEvent.ShowCreateTaskSheet -> navigator.openCreateTask(event.initialDueDate)
                is CalendarUiEvent.ShowError -> { /* handled separately */ }
            }
        }
    }

    ProvideCalendarPalette {
        CalendarContent(
            state = state,
            onIntent = vm::onIntent,
            today = today,
            modifier = modifier,
        )
    }
}
