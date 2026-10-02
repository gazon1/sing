package com.singularity.todo.feature.calendar.presentation.components.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.singularity.todo.feature.calendar.domain.logic.dayOfWeekShort
import com.singularity.todo.feature.calendar.domain.model.CalendarTaskUi
import com.singularity.todo.feature.calendar.presentation.theme.CalendarPalette
import com.singularity.todo.feature.calendar.presentation.theme.LocalCalendarPalette
import kotlinx.datetime.LocalDate

private val HOUR_ROW_HEIGHT = 64.dp
private val TIME_COLUMN_WIDTH = 56.dp

/**
 * Time-based grid for Day / 4 days / Week view.
 * Left column: hour labels 00:00–23:00.
 * Top row: day headers (day number + weekday abbreviation).
 * Second row: all-day task strip.
 * Remaining rows: hourly cells with timed tasks.
 */
@Composable
fun TimeGridView(
    dates: List<LocalDate>,
    tasksByDate: Map<LocalDate, List<CalendarTaskUi>>,
    today: LocalDate,
    selectedTaskId: String? = null,
    onTaskClick: (CalendarTaskUi) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val palette: CalendarPalette = LocalCalendarPalette.current

    Column(modifier = modifier.fillMaxSize().background(palette.background)) {
        // Day header row
        Row(modifier = Modifier.fillMaxWidth()) {
            Spacer(modifier = Modifier.width(TIME_COLUMN_WIDTH))
            dates.forEach { date ->
                DayHeaderCell(
                    date = date,
                    isToday = date == today,
                    palette = palette,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        // All-day strip
        Row(modifier = Modifier.fillMaxWidth()) {
            Spacer(
                modifier = Modifier
                    .width(TIME_COLUMN_WIDTH)
                    .align(Alignment.Top),
            )
            dates.forEach { date ->
                AllDayCell(
                    tasks = tasksByDate[date].orEmpty().filter { it.isAllDay },
                    selectedTaskId = selectedTaskId,
                    onTaskClick = onTaskClick,
                    palette = palette,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        // Hourly grid
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(24, key = { it }) { hour ->
                HourRow(
                    hour = hour,
                    dates = dates,
                    tasksByDate = tasksByDate,
                    selectedTaskId = selectedTaskId,
                    onTaskClick = onTaskClick,
                    palette = palette,
                )
            }
        }
    }
}

@Composable
private fun DayHeaderCell(date: LocalDate, isToday: Boolean, palette: CalendarPalette, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(vertical = 8.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (isToday) {
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(palette.todayBadge),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "${date.day}",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            } else {
                Text(
                    text = "${date.day}",
                    color = palette.textPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = date.dayOfWeekShort(),
                color = palette.textSecondary,
                fontSize = 14.sp,
            )
        }
    }
}

@Composable
private fun AllDayCell(
    tasks: List<CalendarTaskUi>,
    selectedTaskId: String?,
    onTaskClick: (CalendarTaskUi) -> Unit,
    palette: CalendarPalette,
    modifier: Modifier = Modifier,
) {
    val visible = remember(tasks) { tasks.take(3) }
    val overflow = tasks.size - visible.size

    Column(
        modifier = modifier
            .padding(horizontal = 2.dp)
            .border(width = 0.5.dp, color = palette.divider)
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        visible.forEach { task ->
            TaskChip(
                task = task,
                isSelected = task.id.value == selectedTaskId,
                onClick = { onTaskClick(task) },
            )
        }
        if (overflow > 0) {
            // No action: this is the all-day cell of a day the user is already
            // viewing, so "open the day" would be a no-op. Plain text beats a
            // control that does nothing.
            MoreTasksLabel(count = overflow, onClick = null)
        }
    }
}

@Composable
private fun HourRow(
    hour: Int,
    dates: List<LocalDate>,
    tasksByDate: Map<LocalDate, List<CalendarTaskUi>>,
    selectedTaskId: String?,
    onTaskClick: (CalendarTaskUi) -> Unit,
    palette: CalendarPalette,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(HOUR_ROW_HEIGHT),
    ) {
        // Hour label on the left
        Box(
            modifier = Modifier
                .width(TIME_COLUMN_WIDTH)
                .fillMaxHeight(),
            contentAlignment = Alignment.TopEnd,
        ) {
            Text(
                text = "%02d:00".format(hour),
                color = palette.textMuted,
                fontSize = 11.sp,
                modifier = Modifier.padding(end = 8.dp, top = 2.dp),
            )
        }

        // Day columns
        dates.forEach { date ->
            val timedTasks = tasksByDate[date].orEmpty().filter {
                !it.isAllDay && it.startTime?.hour == hour
            }
            Box(
                modifier = Modifier
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
    }
}
