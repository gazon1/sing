package com.singularity.todo.feature.calendar.presentation.screen

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.singularity.todo.core.refresh.RefreshableContent
import com.singularity.todo.core.ui.components.CollectEvents
import com.singularity.todo.feature.calendar.domain.model.CalendarViewMode
import com.singularity.todo.feature.calendar.domain.state.CalendarUiEvent
import com.singularity.todo.feature.calendar.presentation.nav.LocalCalendarNavigator
import com.singularity.todo.feature.calendar.presentation.theme.ProvideCalendarPalette
import com.singularity.todo.feature.calendar.presentation.viewmodel.CalendarViewModel
import kotlinx.coroutines.launch
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
fun CalendarScreen(
    anchorDate: LocalDate,
    modifier: Modifier = Modifier,
) {
    val vm: CalendarViewModel =
        koinViewModel {
            parametersOf(anchorDate.year, anchorDate.month, CalendarViewMode.MONTH)
        }
    val state by vm.stateFlow.collectAsStateWithLifecycle()

    val navigator = LocalCalendarNavigator.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    CollectEvents(vm.events) { event ->
        when (event) {
            is CalendarUiEvent.NavigateToTask -> {
                navigator.openTask(event.taskId)
            }

            is CalendarUiEvent.ShowCreateTaskSheet -> {
                navigator.openCreateTask(event.initialDueDate)
            }

            is CalendarUiEvent.NavigateToCreateWithDates -> {
                navigator.openCreateTaskWithDates(event.draftKey)
            }

            is CalendarUiEvent.ShowError -> {
                scope.launch { snackbarHostState.showSnackbar(event.message) }
            }
        }
    }

    ProvideCalendarPalette {
        RefreshableContent {
            Box(modifier = modifier.fillMaxSize()) {
                // The calendar's root is where the tab starts, so it publishes no
                // Back icon: the shell's own arrow knows where the shell is, and
                // the root has nowhere to go back to. A nested route (a day) is
                // pushed on top of the root and keeps the arrow that returns to it
                // (#3093).
                val onBack: (() -> Unit)? =
                    if (navigator.canGoBack()) {
                        { navigator.back() }
                    } else {
                        null
                    }
                CalendarContent(
                    state = state,
                    onIntent = vm::onIntent,
                    onBack = onBack,
                    modifier = Modifier.fillMaxSize(),
                )
                SnackbarHost(
                    hostState = snackbarHostState,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }
    }
}
