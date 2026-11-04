package com.singularity.todo.feature.calendar.presentation.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.unit.dp
import com.singularity.todo.core.ui.components.StatefulContent
import com.singularity.todo.feature.calendar.domain.logic.YearMonth
import com.singularity.todo.feature.calendar.domain.logic.headerLabel
import com.singularity.todo.feature.calendar.domain.logic.toLocalDate
import com.singularity.todo.feature.calendar.domain.logic.yearMonthForPage
import com.singularity.todo.feature.calendar.domain.model.CalendarViewMode
import com.singularity.todo.feature.calendar.presentation.components.calendar.CalendarSelectionBar
import com.singularity.todo.feature.calendar.presentation.components.calendar.CalendarTopBar
import com.singularity.todo.feature.calendar.presentation.components.calendar.MiniCalendarInlineMinWidth
import com.singularity.todo.feature.calendar.presentation.components.calendar.MiniCalendarPanel
import com.singularity.todo.feature.calendar.presentation.components.calendar.MiniCalendarPanelWidth
import com.singularity.todo.feature.calendar.presentation.components.calendar.MonthGridView
import com.singularity.todo.feature.calendar.presentation.components.calendar.TimeGridView
import com.singularity.todo.feature.calendar.domain.state.CalendarIntent
import com.singularity.todo.feature.calendar.domain.state.CalendarUiState
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
    onBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    pagerState: PagerState? = null,
) {
    Surface(modifier = modifier.fillMaxSize()) {
        StatefulContent(
            state =
                when (state) {
                    is CalendarUiState.Loaded -> {
                        com.singularity.todo.core.ui.components.ContentState
                            .Ready(state)
                    }

                    else -> {
                        com.singularity.todo.core.ui.components.ContentState.Loading
                    }
                },
            emptyTitle = "Calendar",
            modifier = Modifier.fillMaxSize(),
        ) { loadedState, contentModifier ->
            // Top bar derives its header label live from the current pager page —
            // this avoids a VM round-trip per swipe frame and ensures the label
            // updates instantly as the user drags, not just on settle.
            val liveMonth: YearMonth? =
                pagerState?.let { ps ->
                    val anchorYm =
                        remember(loadedState.anchor) {
                            YearMonth(loadedState.anchor.year, loadedState.anchor.month)
                        }
                    yearMonthForPage(anchorYm, ps.currentPage)
                }
            val liveHeaderLabel =
                liveMonth?.toLocalDate()?.let { anchorDate ->
                    headerLabel(anchorDate, loadedState.viewMode)
                }

            BoxWithConstraints(modifier = contentModifier.fillMaxSize()) {
                // The mini calendar takes a third of a phone's width. Below
                // [MiniCalendarInlineMinWidth] the two do not fit side by side — a
                // 340 dp panel beside a 360 dp phone leaves the grid a sliver and
                // crushes the calendar's own controls — so the panel floats over
                // the grid, which keeps its full width, and the grid stays visible
                // beside the panel.
                val isNarrow = maxWidth < MiniCalendarInlineMinWidth
                val panelWidth =
                    minOf(
                        MiniCalendarPanelWidth,
                        // At most 70% of the screen: the panel covers the grid's
                        // right-hand part, not the whole of it.
                        (maxWidth * 0.7f).coerceAtLeast(0.dp),
                    )

                Row(modifier = Modifier.fillMaxSize()) {
                    // Main calendar area: Column carries the weight so child views fill height
                    Column(
                        modifier =
                            Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                    ) {
                        CalendarTopBar(
                            state = loadedState,
                            today = loadedState.today,
                            onIntent = onIntent,
                            headerLabelOverride = liveHeaderLabel,
                            onBack = onBack,
                        )

                        // The grid takes the rest of the height, so the selection bar below
                        // it has room. Without the weight the grid would take all of it and
                        // the bar would be pushed off the screen.
                        Box(
                            modifier = Modifier.weight(1f),
                        ) {
                            CalendarGrid(
                                loadedState = loadedState,
                                onIntent = onIntent,
                                pagerState = pagerState,
                            )

                            // On a narrow screen the panel floats over the grid, under
                            // the top bar: the controls of the calendar itself stay
                            // visible and reachable while the panel is open.
                            if (isNarrow) {
                                MiniCalendarPanelSlot(
                                    loadedState = loadedState,
                                    onIntent = onIntent,
                                    modifier = Modifier.align(Alignment.CenterEnd),
                                    panelModifier =
                                        Modifier
                                            .width(panelWidth)
                                            .shadow(8.dp),
                                )
                            }
                        }
                    }

                    // Mini calendar side panel (slides in from right).
                    //
                    // Hidden while the days are being picked: two grids whose taps mean
                    // different things — "open this day" and "pick this day" — is a trap
                    // the user walks into without being told.
                    if (!isNarrow) {
                        MiniCalendarPanelSlot(
                            loadedState = loadedState,
                            onIntent = onIntent,
                            panelModifier = Modifier.width(MiniCalendarPanelWidth),
                        )
                    }

                    // The bar that offers to turn the picked days into one task. It lives
                    // under the grid, not over it: the grid is what the user is picking in.
                    if (loadedState.isDateSelectionMode) {
                        CalendarSelectionBar(
                            selectedCount = loadedState.selectedDates.size,
                            onCreateTask = { onIntent(CalendarIntent.CreateTaskOnSelectedDates) },
                            onCancel = { onIntent(CalendarIntent.ExitDateSelectionMode) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * The mini calendar panel and the way it appears.
 *
 * @param modifier Where the panel sits — on a narrow screen it is aligned over
 *   the grid, on a wide one it takes its place in the row beside it.
 * @param panelModifier The panel's own placement: its width, and on a narrow
 *   screen the shadow that separates it from the grid it floats over.
 */
@Composable
private fun MiniCalendarPanelSlot(
    loadedState: CalendarUiState.Loaded,
    onIntent: (CalendarIntent) -> Unit,
    modifier: Modifier = Modifier,
    panelModifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = loadedState.isMiniCalendarOpen && !loadedState.isDateSelectionMode,
        enter = slideInHorizontally(initialOffsetX = { it }),
        exit = slideOutHorizontally(targetOffsetX = { it }),
        modifier = modifier,
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
            modifier = panelModifier,
        )
    }
}

/**
 * The grid of the calendar — the month grid, or the time grid of a week.
 *
 * Its own composable so that [CalendarContent] stays inside the complexity
 * budget: the grid is a whole interaction, and inlining it pushed the screen
 * over.
 */
@Composable
private fun CalendarGrid(
    loadedState: CalendarUiState.Loaded,
    onIntent: (CalendarIntent) -> Unit,
    pagerState: PagerState?,
) {
    when (loadedState.viewMode) {
        CalendarViewMode.MONTH -> {
            MonthGridView(
                monthAnchor = loadedState.anchor,
                today = loadedState.today,
                selectedDate = loadedState.selectedDate,
                tasksByDate = loadedState.tasksByDate,
                onDayClick = { onIntent(CalendarIntent.DayClicked(it)) },
                onTaskClick = { task -> onIntent(CalendarIntent.TaskClicked(task.id)) },
                onMonthPageChanged = { month -> onIntent(CalendarIntent.MonthPageChanged(month)) },
                onEmptyCellLongPress = { date -> onIntent(CalendarIntent.EmptyCellLongPressed(date)) },
                selectedDates = loadedState.selectedDates,
                onDayLongPress = { date -> onIntent(CalendarIntent.EnterDateSelectionMode(date)) },
                modifier = Modifier.fillMaxSize(),
                pagerState = pagerState ?: rememberPagerState(initialPage = 120) { 240 },
            )
        }

        else -> {
            TimeGridView(
                dates = loadedState.visibleDates,
                tasksByDate = loadedState.tasksByDate,
                today = loadedState.today,
                selectedTaskId = loadedState.selectedTaskId,
                onTaskClick = { task -> onIntent(CalendarIntent.TaskClicked(task.id)) },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
