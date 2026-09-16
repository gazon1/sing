package com.singularity.todo.feature.tasks.presentation.components.upcoming

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.singularity.todo.feature.tasks.presentation.state.UpcomingIntent
import com.singularity.todo.feature.tasks.presentation.theme.TaskListColors
import com.singularity.todo.feature.tasks.presentation.theme.TaskListSpacing
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlinx.datetime.plus

/**
 * Horizontal day picker row: 7 day chips + prev/next week chevron buttons.
 *
 * Displays the Mon–Sun week containing [windowStart], highlights [selectedDate]
 * with the active chip style, and scrolls the LazyRow when a new week is selected.
 */
@Composable
fun DaySwitcherRow(
    windowStart: LocalDate,
    selectedDate: LocalDate,
    onIntent: (UpcomingIntent) -> Unit,
) {
    val days: List<LocalDate> = remember(windowStart) {
        listOf(
            windowStart,
            windowStart.plus(1, DateTimeUnit.DAY),
            windowStart.plus(2, DateTimeUnit.DAY),
            windowStart.plus(3, DateTimeUnit.DAY),
            windowStart.plus(4, DateTimeUnit.DAY),
            windowStart.plus(5, DateTimeUnit.DAY),
            windowStart.plus(6, DateTimeUnit.DAY),
        )
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = TaskListSpacing.Md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LazyRow(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            contentPadding = PaddingValues(horizontal = TaskListSpacing.Lg),
        ) {
            items(days, key = { it.toString() }) { day ->
                DayChip(
                    date = day,
                    isSelected = day == selectedDate,
                    onClick = { onIntent(UpcomingIntent.SelectDate(day)) },
                )
            }
        }

        IconButton(
            onClick = { onIntent(UpcomingIntent.PrevWeek) },
            modifier = Modifier.size(32.dp),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                contentDescription = "Previous week",
                tint = TaskListColors.TextSecondary,
            )
        }

        IconButton(
            onClick = { onIntent(UpcomingIntent.NextWeek) },
            modifier = Modifier.size(32.dp),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = "Next week",
                tint = TaskListColors.Accent,
            )
        }
    }
}

@Composable
private fun DayChip(
    date: LocalDate,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val backgroundColor = if (isSelected) {
        TaskListColors.SurfaceElevated
    } else {
        TaskListColors.Background
    }
    val textColor = if (isSelected) {
        TaskListColors.TextPrimary
    } else {
        TaskListColors.TextSecondary
    }

    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(backgroundColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = shortDayName(date.dayOfWeek),
            color = textColor,
            fontSize = 13.sp,
        )
        Spacer(Modifier.size(2.dp))
        Text(
            text = date.dayOfMonth.toString(),
            color = textColor,
            fontSize = 15.sp,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

private fun shortDayName(dow: kotlinx.datetime.DayOfWeek): String = when (dow) {
    kotlinx.datetime.DayOfWeek.MONDAY -> "Mo."
    kotlinx.datetime.DayOfWeek.TUESDAY -> "Tu."
    kotlinx.datetime.DayOfWeek.WEDNESDAY -> "We."
    kotlinx.datetime.DayOfWeek.THURSDAY -> "Th."
    kotlinx.datetime.DayOfWeek.FRIDAY -> "Fr."
    kotlinx.datetime.DayOfWeek.SATURDAY -> "Sa."
    kotlinx.datetime.DayOfWeek.SUNDAY -> "Su."
}
