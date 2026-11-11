package com.singularity.todo.feature.calendar.presentation.components.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.singularity.todo.core.format.formatPadded
import com.singularity.todo.core.ui.LocalHaptic
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.core.ui.TouchTarget
import com.singularity.todo.core.ui.components.platformVerticalScrollbar
import com.singularity.todo.feature.calendar.domain.logic.dayOfWeekShort
import com.singularity.todo.feature.calendar.domain.logic.isoLocalDate
import com.singularity.todo.feature.calendar.domain.model.CalendarTaskUi
import com.singularity.todo.feature.calendar.presentation.theme.CalendarPalette
import com.singularity.todo.feature.calendar.presentation.theme.LocalCalendarPalette
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

private val HOUR_ROW_HEIGHT = 64.dp
private val TIME_COLUMN_WIDTH = 56.dp

/**
 * The narrowest day column that still has room for a title chip: 12 dp of
 * horizontal chip padding and up to ~20 dp of leading icon/emoji/marker leave
 * roughly 40 dp for the title at this width — about five characters. Below it
 * the title is an unreadable fragment, so the strip shows a compact marker.
 *
 * Measured at 360 dp: `(360 − 56) / 7 ≈ 43.4 dp` per week column — compact.
 * At 600 dp: `(600 − 56) / 7 ≈ 77.7 dp` — title chips.
 * See ADR `2026-11-11-week-calendar-all-day-responsive-layout`.
 */
private val MIN_TITLE_CHIP_DAY_WIDTH = 72.dp

/**
 * The most vertical space the single-day all-day strip takes before it scrolls.
 * Without a bound, a day with many all-day tasks would push the hour grid off
 * the screen and clip the last tasks with no way to reach them.
 */
private val SINGLE_DAY_ALL_DAY_MAX_HEIGHT = 160.dp

/**
 * Time-based grid for Day / 4 days / Week view.
 * Left column: hour labels 00:00–23:00.
 * Top row: day headers (day number + weekday abbreviation).
 * Second row: all-day task strip.
 * Remaining rows: hourly cells with timed tasks.
 *
 * The all-day strip is responsive: a day column narrower than
 * [MIN_TITLE_CHIP_DAY_WIDTH] shows a compact marker per task, because a title
 * chip there renders as a two-or-three-character fragment. A multi-day grid's
 * date header and overflow label open that date in DAY view, where every task
 * is rendered in full — a date column is not the day view.
 *
 * @param onDayClick Opens [date] in DAY view. Only called from a multi-day grid;
 *   the single-day view has nowhere to go.
 */
@Composable
fun TimeGridView(
    dates: ImmutableList<LocalDate>,
    tasksByDate: ImmutableMap<LocalDate, ImmutableList<CalendarTaskUi>>,
    today: LocalDate,
    selectedTaskId: String? = null,
    onTaskClick: (CalendarTaskUi) -> Unit = {},
    onDayClick: ((LocalDate) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val palette: CalendarPalette = LocalCalendarPalette.current
    val haptic = LocalHaptic.current
    val scope = rememberCoroutineScope()
    val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
    val showNowLineToday = today in dates
    val listState = rememberLazyListState()
    LaunchedEffect(today, now.hour) {
        if (showNowLineToday) listState.animateScrollToItem(now.hour.coerceIn(0, 23))
    }
    BoxWithConstraints(modifier = modifier.fillMaxSize().background(palette.background)) {
        val dayWidth = (maxWidth - TIME_COLUMN_WIDTH) / dates.size.toFloat()
        val compactDays = dayWidth < MIN_TITLE_CHIP_DAY_WIDTH
        // A single-day column is already the day view; there is nothing to open.
        val openDay: ((LocalDate) -> Unit)? = if (dates.size > 1) onDayClick else null

        Column(modifier = Modifier.fillMaxSize()) {
            DayHeaderRow(
                dates = dates,
                today = today,
                compact = compactDays,
                palette = palette,
                onDayClick = openDay,
            )
            AllDayStrip(
                dates = dates,
                tasksByDate = tasksByDate,
                selectedTaskId = selectedTaskId,
                compact = compactDays,
                // The view mode decides the cap; whether a day action was
                // supplied only decides whether the header and +N navigate.
                showAll = dates.size == 1,
                onTaskClick = { task ->
                    scope.launch { haptic.perform() }
                    onTaskClick(task)
                },
                onOpenDay = openDay,
                palette = palette,
            )

            // Hourly grid
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().platformVerticalScrollbar(listState),
            ) {
                items(24, key = { it }) { hour ->
                    HourRow(
                        hour = hour,
                        dates = dates,
                        tasksByDate = tasksByDate,
                        selectedTaskId = selectedTaskId,
                        onTaskClick = { task ->
                            scope.launch { haptic.perform() }
                            onTaskClick(task)
                        },
                        palette = palette,
                        showNowLine = showNowLineToday && hour == now.hour,
                        nowMinute = now.minute,
                    )
                }
            }
        }
    }
}

@Composable
private fun DayHeaderRow(
    dates: ImmutableList<LocalDate>,
    today: LocalDate,
    compact: Boolean,
    palette: CalendarPalette,
    onDayClick: ((LocalDate) -> Unit)?,
) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Spacer(modifier = Modifier.width(TIME_COLUMN_WIDTH))
        dates.forEach { date ->
            DayHeaderCell(
                date = date,
                isToday = date == today,
                compact = compact,
                palette = palette,
                onClick = onDayClick?.let { open -> { open(date) } },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun AllDayStrip(
    dates: ImmutableList<LocalDate>,
    tasksByDate: ImmutableMap<LocalDate, ImmutableList<CalendarTaskUi>>,
    selectedTaskId: String?,
    compact: Boolean,
    showAll: Boolean,
    onTaskClick: (CalendarTaskUi) -> Unit,
    onOpenDay: ((LocalDate) -> Unit)?,
    palette: CalendarPalette,
) {
    // Derived once per map: the strip renders one cell per day, and filtering
    // the day's tasks inside the cell rebuilt the list for every day on every
    // recomposition.
    val allDayTasksByDate =
        remember(tasksByDate) {
            tasksByDate.mapValues { (_, tasks) -> tasks.filter { it.isAllDay }.toImmutableList() }
        }
    Row(modifier = Modifier.fillMaxWidth()) {
        Spacer(
            modifier =
                Modifier
                    .width(TIME_COLUMN_WIDTH)
                    .align(Alignment.Top),
        )
        dates.forEach { date ->
            AllDayCell(
                tasks = allDayTasksByDate[date] ?: persistentListOf(),
                selectedTaskId = selectedTaskId,
                compact = compact,
                showAll = showAll,
                onTaskClick = onTaskClick,
                onOpenDay = onOpenDay?.let { open -> { open(date) } },
                palette = palette,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun DayHeaderCell(
    date: LocalDate,
    isToday: Boolean,
    compact: Boolean,
    palette: CalendarPalette,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val dayTag = TestTags.calendarDay(isoLocalDate(date))
    Column(
        // The cell is tagged so a flow can address a day of the day grid
        // without reading the app's date format; the day it holds is tagged by
        // date on the number inside it, exactly as the month grid tags its
        // cells.
        modifier =
            modifier
                .testTag(TestTags.CALENDAR_DAY_CELL)
                .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
                .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        val dayNumber: @Composable () -> Unit = {
            if (isToday) {
                Box(
                    modifier =
                        Modifier
                            .size(if (compact) 20.dp else 22.dp)
                            .clip(CircleShape)
                            .background(palette.todayBadge),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "${date.day}",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.testTag(dayTag),
                    )
                }
            } else {
                Text(
                    text = "${date.day}",
                    color = palette.textPrimary,
                    fontSize = if (compact) 14.sp else 15.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.testTag(dayTag),
                )
            }
        }
        val weekday: @Composable () -> Unit = {
            Text(
                text = date.dayOfWeekShort(),
                color = palette.textSecondary,
                fontSize = if (compact) 11.sp else 14.sp,
                // One line: in a narrow column "Wed" used to break into single
                // letters.
                maxLines = 1,
                softWrap = false,
            )
        }
        if (compact) {
            // ~43 dp cannot hold the number and the weekday side by side (a
            // 20 dp badge plus the label). Stack them; each stays one line.
            Column {
                dayNumber()
                weekday()
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                dayNumber()
                Spacer(modifier = Modifier.width(6.dp))
                weekday()
            }
        }
    }
}

/**
 * One day's all-day tasks.
 *
 * A multi-day grid caps the list: a title chip where the column is wide enough,
 * otherwise up to two 48 dp marker rows, with the rest behind a `+N` that opens
 * the day. The single-day view is the complete day list, so it renders every
 * task, capped at [SINGLE_DAY_ALL_DAY_MAX_HEIGHT] and scrolled beyond that.
 */
@Composable
private fun AllDayCell(
    tasks: ImmutableList<CalendarTaskUi>,
    selectedTaskId: String?,
    compact: Boolean,
    showAll: Boolean,
    onTaskClick: (CalendarTaskUi) -> Unit,
    onOpenDay: (() -> Unit)?,
    palette: CalendarPalette,
    modifier: Modifier = Modifier,
) {
    val visible =
        remember(tasks, showAll, compact) {
            when {
                showAll -> tasks
                compact -> tasks.take(2)
                else -> tasks.take(3)
            }
        }
    val overflow = tasks.size - visible.size
    val scrollState = rememberScrollState()

    Column(
        modifier =
            modifier
                .testTag(TestTags.CALENDAR_ALL_DAY_CELL)
                .padding(horizontal = 2.dp)
                .border(width = 0.5.dp, color = palette.divider)
                .then(
                    if (showAll) {
                        Modifier
                            .heightIn(max = SINGLE_DAY_ALL_DAY_MAX_HEIGHT)
                            .verticalScroll(scrollState)
                    } else {
                        Modifier
                    },
                ).padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        visible.forEach { task ->
            val isSelected = task.id.value == selectedTaskId
            if (compact) {
                NarrowTaskMarker(
                    task = task,
                    isSelected = isSelected,
                    onClick = { onTaskClick(task) },
                )
            } else {
                TaskChip(
                    task = task,
                    isSelected = isSelected,
                    onClick = { onTaskClick(task) },
                )
            }
        }
        if (overflow > 0) {
            MoreTasksLabel(count = overflow, onClick = onOpenDay, compact = compact)
        }
    }
}

/**
 * One task in a narrow all-day column.
 *
 * The visual is an 8 dp dot, but the target is the column's width by 48 dp: a
 * 48 dp-wide target does not fit a ~43 dp column, and a smaller one is below the
 * project's minimum. Two are shown; the rest are behind the `+N` label that
 * opens the day.
 */
@Composable
private fun NarrowTaskMarker(
    task: CalendarTaskUi,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val markerColor = taskMarkerBackground(task, isSelected)
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(TouchTarget.MinSize)
                .clickable(onClick = onClick)
                .semantics { contentDescription = task.title }
                .testTag(TestTags.CALENDAR_ALL_DAY_MARKER),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier
                    .size(8.dp)
                    .background(markerColor, CircleShape),
        )
    }
}

@Composable
private fun HourRow(
    hour: Int,
    dates: ImmutableList<LocalDate>,
    tasksByDate: ImmutableMap<LocalDate, ImmutableList<CalendarTaskUi>>,
    selectedTaskId: String?,
    onTaskClick: (CalendarTaskUi) -> Unit,
    palette: CalendarPalette,
    showNowLine: Boolean = false,
    nowMinute: Int = 0,
) {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(HOUR_ROW_HEIGHT),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(HOUR_ROW_HEIGHT),
        ) {
            // Hour label on the left
            Box(
                modifier =
                    Modifier
                        .width(TIME_COLUMN_WIDTH)
                        .fillMaxHeight(),
                contentAlignment = Alignment.TopEnd,
            ) {
                Text(
                    text = "${formatPadded(hour)}:00",
                    color = palette.textMuted,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(end = 8.dp, top = 2.dp),
                )
            }

            // Day columns
            dates.forEach { date ->
                val timedTasks =
                    tasksByDate[date].orEmpty().filter {
                        !it.isAllDay && it.startTime?.hour == hour
                    }
                Box(
                    modifier =
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .border(width = 0.5.dp, color = palette.gridLine),
                ) {
                    Column(modifier = Modifier.padding(2.dp)) {
                        timedTasks.forEach { task ->
                            TaskChip(
                                task = task,
                                isSelected = task.id.value == selectedTaskId,
                                onClick = { onTaskClick(task) },
                            )
                        }
                    }
                }
            }
            if (showNowLine) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .offset(y = (nowMinute / 60f * HOUR_ROW_HEIGHT.value).dp)
                            .height(2.dp)
                            .background(palette.todayBadge),
                )
            }
        }
    }
}
