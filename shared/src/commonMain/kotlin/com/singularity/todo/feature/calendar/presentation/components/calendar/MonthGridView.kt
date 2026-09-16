package com.singularity.todo.feature.calendar.presentation.components.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import com.singularity.todo.feature.calendar.domain.logic.monthGridDates
import com.singularity.todo.feature.calendar.domain.model.CalendarTaskUi
import com.singularity.todo.feature.calendar.presentation.theme.LocalCalendarPalette
import kotlinx.datetime.LocalDate

/**
 * Month grid view (6 weeks × 7 days).
 * Mirrors the mock's [MonthGridView] — colour tokens from [LocalCalendarPalette].
 */
@Composable
fun MonthGridView(
    monthAnchor: LocalDate,
    tasksByDate: Map<LocalDate, List<CalendarTaskUi>>,
    today: LocalDate,
    selectedDate: LocalDate,
    onDayClick: (LocalDate) -> Unit,
    onTaskClick: (CalendarTaskUi) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val palette = LocalCalendarPalette.current
    val gridDates = remember(monthAnchor) { monthGridDates(monthAnchor) }
    val weeks = gridDates.chunked(7)
    val weekdayLabels = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

    Column(modifier = modifier.fillMaxSize().background(palette.background)) {
        // Weekday header row
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

        // Day cells
        Column(modifier = Modifier.fillMaxSize()) {
            weeks.forEach { week ->
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                ) {
                    week.forEach { date ->
                        MonthDayCell(
                            date = date,
                            isCurrentMonth = date.month == monthAnchor.month,
                            isToday = date == today,
                            isSelected = date == selectedDate,
                            tasks = tasksByDate[date].orEmpty(),
                            onClick = { onDayClick(date) },
                            onTaskClick = onTaskClick,
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MonthDayCell(
    date: LocalDate,
    isCurrentMonth: Boolean,
    isToday: Boolean,
    isSelected: Boolean,
    tasks: List<CalendarTaskUi>,
    onClick: () -> Unit,
    onTaskClick: (CalendarTaskUi) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalCalendarPalette.current
    val visibleTasks = remember(tasks) { tasks.take(3) }
    val overflow = tasks.size - visibleTasks.size
    val dayNumberColor = if (isCurrentMonth) palette.textPrimary else palette.textMuted

    Box(
        modifier = modifier
            .border(width = 0.5.dp, color = palette.divider)
            .let { box ->
                if (isSelected) box.background(palette.surface) else box
            }
            .clickable { onClick() }
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
                            text = "${date.dayOfMonth}",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                } else {
                    Text(
                        text = "${date.dayOfMonth}",
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
