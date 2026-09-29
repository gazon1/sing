package com.singularity.todo.feature.calendar.presentation.components.calendar

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.feature.calendar.domain.logic.YearMonth
import com.singularity.todo.feature.calendar.domain.logic.monthGridDates
import com.singularity.todo.feature.calendar.domain.logic.pageForYearMonth
import com.singularity.todo.feature.calendar.domain.logic.toLocalDate
import com.singularity.todo.feature.calendar.domain.logic.toYearMonth
import com.singularity.todo.feature.calendar.domain.logic.yearMonthForPage
import com.singularity.todo.feature.calendar.domain.model.CalendarTaskUi
import com.singularity.todo.feature.calendar.presentation.state.CalendarIntent
import com.singularity.todo.feature.calendar.presentation.theme.LocalCalendarPalette
import kotlinx.datetime.LocalDate

/**
 * Swipeable month grid. Hosts a [HorizontalPager] of [MonthGridPage]s, one per
 * (YearMonth) page, indexed via [PagerState]. The initial page corresponds to
 * the anchor month; user swipes shift the page index and the committed month
 * is reported up to the caller via [onMonthPageChanged] (fired only when
 * [PagerState.settledPage] differs from the current anchor).
 *
 * Page count is fixed at [pageCount]; long-range jumps are handled by the
 * mini-calendar panel which dispatches [CalendarIntent.MonthPageChanged]
 * directly (the caller is responsible for re-anchoring [pagerState] then).
 *
 * @param monthAnchor Reference month — pager starts here. Recomputing this
 *   triggers [LaunchedEffect] to animate the pager to the matching page.
 * @param pageCount Total pages in the pager (default 240 ⇒ ±120 months).
 * @param onEmptyCellLongPress Called when user long-presses an empty current-month cell.
 */
@Composable
fun MonthGridView(
    monthAnchor: LocalDate,
    today: LocalDate,
    selectedDate: LocalDate,
    tasksByDate: Map<LocalDate, List<CalendarTaskUi>>,
    onDayClick: (LocalDate) -> Unit,
    onTaskClick: (CalendarTaskUi) -> Unit,
    onMonthPageChanged: (YearMonth) -> Unit,
    onEmptyCellLongPress: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
    pageCount: Int = 240,
    pagerState: PagerState = rememberPagerState(initialPage = pageCount / 2) { pageCount },
) {
    val palette = LocalCalendarPalette.current
    val anchorYearMonth = remember(monthAnchor) { monthAnchor.toYearMonth() }
    val weekdayLabels = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

    Column(modifier = modifier.fillMaxSize().background(palette.background)) {
        // Fixed weekday header — does not scroll with pages.
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            weekdayLabels.forEach { label ->
                Text(
                    text = label,
                    color = palette.textSecondary,
                    fontSize = 13.sp,
                    modifier = Modifier.weight(1f).padding(start = 12.dp),
                )
            }
        }

        HorizontalPager(
            state = pagerState,
            beyondViewportPageCount = 1,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            val pageYearMonth = yearMonthForPage(anchorYearMonth, page)
            MonthGridPage(
                pageAnchor = pageYearMonth.toLocalDate(),
                today = today,
                selectedDate = selectedDate,
                tasksByDate = tasksByDate,
                onDayClick = onDayClick,
                onTaskClick = onTaskClick,
                onEmptyCellLongPress = onEmptyCellLongPress,
            )
        }
    }

    // Settled-page → commit. Dedupes against the current anchor to avoid
    // spurious Room re-subscribes when the fling settles on the same page.
    LaunchedEffect(pagerState.settledPage, anchorYearMonth) {
        val settledMonth = yearMonthForPage(anchorYearMonth, pagerState.settledPage)
        if (settledMonth != anchorYearMonth) {
            onMonthPageChanged(settledMonth)
        }
    }

    // External jump: if a different intent (e.g. mini-calendar pick) sets
    // [monthAnchor] to a month not equal to the current page, animate to it.
    LaunchedEffect(monthAnchor) {
        val targetMonth = monthAnchor.toYearMonth()
        val targetPage = pageForYearMonth(anchorYearMonth, targetMonth)
        if (targetPage != null && targetPage != pagerState.currentPage) {
            pagerState.animateScrollToPage(targetPage)
        }
    }
}

/**
 * Single 6×7 month grid (Mon-anchored). Pure content — no pager state.
 * Extracted so [HorizontalPager] can host it as one page.
 */
@Composable
private fun MonthGridPage(
    pageAnchor: LocalDate,
    today: LocalDate,
    selectedDate: LocalDate,
    tasksByDate: Map<LocalDate, List<CalendarTaskUi>>,
    onDayClick: (LocalDate) -> Unit,
    onTaskClick: (CalendarTaskUi) -> Unit,
    onEmptyCellLongPress: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalCalendarPalette.current
    val gridDates = remember(pageAnchor) { monthGridDates(pageAnchor) }
    val weeks = gridDates.chunked(7)

    Column(modifier = modifier.fillMaxSize()) {
        weeks.forEach { week ->
            Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
                week.forEach { date ->
                    MonthDayCell(
                        date = date,
                        isCurrentMonth = date.month == pageAnchor.month,
                        isToday = date == today,
                        isSelected = date == selectedDate,
                        tasks = tasksByDate[date].orEmpty(),
                        onClick = { onDayClick(date) },
                        onTaskClick = onTaskClick,
                        onEmptyCellLongPress = onEmptyCellLongPress,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MonthDayCell(
    date: LocalDate,
    isCurrentMonth: Boolean,
    isToday: Boolean,
    isSelected: Boolean,
    tasks: List<CalendarTaskUi>,
    onClick: () -> Unit,
    onTaskClick: (CalendarTaskUi) -> Unit,
    onEmptyCellLongPress: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalCalendarPalette.current
    val visibleTasks = remember(tasks) { tasks.take(3) }
    val overflow = tasks.size - visibleTasks.size
    val dayNumberColor = if (isCurrentMonth) palette.textPrimary else palette.textMuted
    val hasNoTasks = tasks.isEmpty() && isCurrentMonth

    Box(
        modifier = modifier
            .testTag(
                TestTags.calendarDay(
                    "${date.year}-${date.monthNumber.toString().padStart(
                        2,
                        '0',
                    )}-${date.dayOfMonth.toString().padStart(2, '0')}",
                ),
            )
            .border(width = 0.5.dp, color = palette.divider)
            .let { box ->
                if (isSelected) box.background(palette.surface) else box
            }
            .combinedClickable(
                onClick = onClick,
                onLongClick = if (hasNoTasks) {
                    { onEmptyCellLongPress(date) }
                } else {
                    {}
                },
            )
            .padding(6.dp),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isToday) {
                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .clip(CircleShape)
                            .background(palette.todayBadge),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "${date.day}",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                } else {
                    Text(
                        text = "${date.day}",
                        color = dayNumberColor,
                        fontSize = 13.sp,
                        fontWeight = if (isCurrentMonth) FontWeight.Medium else FontWeight.Normal,
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                visibleTasks.forEach { task ->
                    TaskChip(
                        task = task,
                        onClick = { onTaskClick(task) },
                    )
                }
                if (overflow > 0) {
                    MoreTasksLabel(count = overflow, onClick = {})
                }
            }
        }
    }
}
